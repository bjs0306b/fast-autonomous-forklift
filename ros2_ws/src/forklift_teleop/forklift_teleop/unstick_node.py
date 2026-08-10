"""Back the vehicle out of a corner nav2 cannot see it is stuck in.

When the guard decides something is too close it publishes a zero command, and
the controller downstream of it produces nothing. Nav2's progress checker only
runs while the controller is producing commands, so it never notices, never
aborts, and never reaches the BackUp recovery that would fix this. The vehicle
sits against a wall being told to go forward, indefinitely. Every mapping run
on 2026-08-08 ended this way and a person pushed it out by hand.

Nothing inside nav2 can see this, because from nav2's side nothing is wrong.
It has to be watched from outside the guard:

    guard says STOP  +  wheels not turning  +  long enough  ->  take the wheel

Then reverse until there is room ahead, and hand it back. This is exactly what
tools/back_off.py does by hand; the value here is that nobody has to be
standing there.

⚠️ **Only from NAV.** During ALIGN the vehicle is deliberately closing on a
   pallet and the guard may well object -- reversing then would undo the
   approach, or pull the forks out of a pallet they are already inside.

⚠️ **곧게 떠서, 구르기 시작하면 꺾는다.** 곧게만 물러나면 들어온 호를 그대로
   되짚어 **방향이 제자리로 돌아온다** -- 다음 전진이 같은 각도로 같은 벽을
   만난다. 그래서 이탈만 직선이고, 엔코더가 구르는 것을 확인한 뒤에 조향을
   넣어 방향을 바꿔 놓고 끝낸다(tools/back_off.py 가 손으로 하던 그 순서).

   종전 주석은 "꺾인 채로는 정지에서 못 뜬다(2026-08-07)" 였는데 **그 측정은
   뒤집혔다.** mapping.py 의 08-09 재측정이 후륜 25.6°·43.8°·50.2° 에서 모두
   출발을 기록했고, 그 근거로 straighten_to_start 가 False 로 바뀌었다.
   그래도 이탈은 직선으로 둔다 -- 공짜이고, 어려운 쪽을 굳이 고를 이유가 없다.

⚠️ **부호를 뒤집지 않는다.** map_twist 가 곡률을 angular_z / linear_x 로 내므로
   후진이면 후륜각이 **저절로** 뒤집힌다. 같은 요레이트 부호를 유지하는 것만
   으로 바퀴가 반대로 꺾이고 회전이 누적된다. 여기서 한 번 더 뒤집으면 전진
   구간과 정확히 상쇄되어 앞뒤로 흔들리기만 한다(test_pivot 이 잡은 버그).

⚠️ **꼬리는 도는 쪽의 반대로 쓸린다.** 좌회전 후진이면 꼬리가 오른쪽으로
   나간다. 그래서 여유 확인은 rear_right 를 본다 -- 가장 뒤집어 쓰기 쉬운
   지점이다.

⚠️ **Rear sensing is lidar only.** The ToF pair faces forward, so a low object
   behind is invisible to everything. rear_stop_distance_m holds the guard
   back, but only for what the lidar can see.
"""

import json
import math
import time

from geometry_msgs.msg import Twist, TwistWithCovarianceStamped
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile
from std_msgs.msg import String

from forklift_teleop.obstacle_fusion import (
    corridors_from_status,
    escape_yaw_sign,
    preferred_avoidance_side,
    tail_swing_side_clearance,
)

NAV = "NAV"
RECOVER = "RECOVER"


class UnstickNode(Node):
    def __init__(self) -> None:
        super().__init__("unstick_node")

        self.declare_parameter("status_topic", "/obstacle_avoidance/status")
        self.declare_parameter("encoder_topic", "/wheel/twist")
        self.declare_parameter("mode_topic", "/drive/mode")
        self.declare_parameter("recover_topic", "/cmd_vel_recover")
        self.declare_parameter("report_topic", "/drive/unstick_status")
        # 갇혔다고 부르기까지. 짧으면 잠깐의 정지에도 끼어들고, 길면 데모가
        # 그만큼 서 있는다. nav2 의 진행 판정(12초)보다 짧아야 의미가 있다.
        self.declare_parameter("stuck_for_sec", 4.0)
        self.declare_parameter("rolling_mps", 0.02)
        self.declare_parameter("reverse_speed_mps", 0.12)
        self.declare_parameter("clearance_target_m", 0.60)
        self.declare_parameter("rear_limit_m", 0.45)
        self.declare_parameter("max_reverse_sec", 5.0)
        # 빠져나온 뒤에도 곧바로 다시 갇히면 무한 반복이 된다. 연속 실패가
        # 이만큼 쌓이면 손을 떼고 사람을 부른다.
        self.declare_parameter("max_attempts", 3)

        # 탈출 후진의 곡률을 **반경**으로 잡는다. 요레이트로 고정하면
        # reverse_speed_mps 를 올릴 때 후륜각이 같이 커져, 구동 모터가 못
        # 이기는 영역으로 들어간다. 반경으로 두면 속도와 무관하게
        # atan(축간거리/R) 로 각이 고정된다.
        self.declare_parameter("escape_turn_radius_m", 0.40)
        self.declare_parameter("escape_rolling_mps", 0.05)
        # 꺾은 채로 출발을 시도하는 시간. 이 안에 한 번도 못 구르면 각을 펴고
        # 곧게 간다 -- 후진은 조향륜이 앞장서서 옆으로 갈아내므로 전진보다
        # 불리하고, 못 뜨면 탈출이 통째로 무효가 된다.
        self.declare_parameter("escape_breakaway_sec", 1.5)
        self.declare_parameter("escape_steer_sec", 2.0)
        self.declare_parameter("escape_tail_clearance_m", 0.50)
        # 가드와 같은 값이어야 방향 판단이 어긋나지 않는다.
        self.declare_parameter("tof_imbalance_m", 0.10)
        self.declare_parameter("lidar_clearance_margin_m", 0.15)
        # 기동 가능한 조향 한계를 반경으로 표현한 값(teleop.yaml 과 같은 뜻).
        # 탈출 반경이 이보다 작으면 map_twist 가 조용히 잘라 버려서, 왜 각이
        # 다른지 알 수 없게 된다. 그래서 기동을 거부한다.
        self.declare_parameter("min_command_turning_radius_m", 0.25)

        self._stuck_for = float(self.get_parameter("stuck_for_sec").value)
        self._rolling = float(self.get_parameter("rolling_mps").value)
        self._speed = abs(float(self.get_parameter("reverse_speed_mps").value))
        self._target = float(self.get_parameter("clearance_target_m").value)
        self._rear_limit = float(self.get_parameter("rear_limit_m").value)
        self._max_reverse = float(self.get_parameter("max_reverse_sec").value)
        self._max_attempts = int(self.get_parameter("max_attempts").value)

        self._escape_radius = float(
            self.get_parameter("escape_turn_radius_m").value)
        self._escape_rolling = float(
            self.get_parameter("escape_rolling_mps").value)
        self._breakaway_sec = float(
            self.get_parameter("escape_breakaway_sec").value)
        self._steer_sec = float(self.get_parameter("escape_steer_sec").value)
        self._tail_limit = float(
            self.get_parameter("escape_tail_clearance_m").value)
        self._tof_imbalance = float(
            self.get_parameter("tof_imbalance_m").value)
        self._lidar_margin = float(
            self.get_parameter("lidar_clearance_margin_m").value)
        minimum_radius = float(
            self.get_parameter("min_command_turning_radius_m").value)
        if self._escape_radius > 0.0 and self._escape_radius < minimum_radius:
            raise ValueError(
                f"escape_turn_radius_m ({self._escape_radius}) 이 기동 한계 "
                f"{minimum_radius} m 보다 작다 -- map_twist 가 조용히 잘라서 "
                "실제 조향각이 명령과 달라진다"
            )
        if self._escape_rolling <= self._rolling:
            raise ValueError(
                f"escape_rolling_mps ({self._escape_rolling}) 은 갇힘 임계 "
                f"{self._rolling} 보다 커야 한다 -- 임계 바로 위에서 조향을 "
                "넣으면 다시 선다"
            )

        self._status = None
        self._speed_measured = 0.0
        self._mode = NAV
        self._blocked_since = None
        self._recovering_until = None
        self._attempts = 0
        self._last_report = ""
        self._steer_sign = 0.0
        self._rolling_since = None
        self._steer_until = None
        self._steer_spent = False
        self._escape_started = None

        self.create_subscription(
            String, str(self.get_parameter("status_topic").value),
            self._on_status, 10)
        self.create_subscription(
            TwistWithCovarianceStamped,
            str(self.get_parameter("encoder_topic").value),
            lambda m: setattr(self, "_speed_measured", m.twist.twist.linear.x),
            10)

        latched = QoSProfile(depth=1)
        latched.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
        self.create_subscription(
            String, str(self.get_parameter("mode_topic").value),
            self._on_mode, latched)
        self._mode_publisher = self.create_publisher(
            String, str(self.get_parameter("mode_topic").value), latched)
        self._report = self.create_publisher(
            String, str(self.get_parameter("report_topic").value), latched)

        self._command = self.create_publisher(
            Twist, str(self.get_parameter("recover_topic").value), 10)
        self.create_timer(0.05, self._tick)

        self.get_logger().info(
            f"Unstick ready: STOP + still for {self._stuck_for:.1f}s -> "
            f"reverse until {self._target:.2f} m ahead"
        )

    def _on_status(self, message: String) -> None:
        try:
            self._status = json.loads(message.data)
        except json.JSONDecodeError:
            self._status = None

    def _on_mode(self, message: String) -> None:
        self._mode = message.data.strip().upper()

    def _ahead(self) -> float:
        if not self._status:
            return math.inf
        values = [v for v in (self._status.get("roofLidar", {}).get("center"),
                              self._status.get("frontTof", {}).get("path"))
                  if v is not None]
        return min(values) if values else math.inf

    def _rear(self) -> float:
        if not self._status:
            return math.inf
        value = self._status.get("roofLidar", {}).get("rear")
        return value if value is not None else math.inf

    def _corridors(self):
        """status 를 코리도로 되돌린다. 없으면 전부 무한(= 트임)."""
        return corridors_from_status(self._status or {})

    def _tail_clearance(self, sign: float) -> float:
        """그 부호로 돌 때 **꼬리가 나가는 쪽**의 여유."""
        lidar, _ = self._corridors()
        return tail_swing_side_clearance(lidar, sign)

    def _pick_escape_sign(self) -> float:
        """어느 쪽으로 돌며 물러날지. 0.0 이면 곧게(= 종전 동작).

        각 단을 기록한다 -- 나중에 로그만 보고 왜 그쪽으로 돌았는지 읽을 수
        있어야 한다.
        """
        lidar, tof = self._corridors()
        side, source = preferred_avoidance_side(
            lidar, tof, self._tof_imbalance, self._lidar_margin)
        sign = escape_yaw_sign(side)

        if sign == 0.0:
            # 앞이 대칭이면 가려던 쪽이 없다. 뒤가 더 트인 쪽으로 꼬리를
            # 보내는 것은 새 선택이지, 뒤집기가 아니다.
            if lidar.rear_right_m > lidar.rear_left_m:
                sign, source = 1.0, "앞이 대칭 -- 후방 우측이 더 트임"
            elif lidar.rear_left_m > lidar.rear_right_m:
                sign, source = -1.0, "앞이 대칭 -- 후방 좌측이 더 트임"
            else:
                return 0.0

        # ⚠️ **막혔다고 부호를 뒤집지 않는다. 곧게 간다.**
        #
        #    뒤집으면 후진의 자동 반전과 겹쳐 **두 번** 뒤집히고, 후륜이
        #    전진 때와 같은 쪽으로 꺾인다 -- 밖에서 보면 "후진할 때 조향을
        #    안 한다" 로 보인다(2026-08-10 실측: 전진 7489, 후진 7020 으로
        #    둘 다 중립 아래). 게다가 뒤집힌 부호는 **가려던 방향과 반대로**
        #    차를 돌려놓아서, 다음 전진의 접근각이 오히려 나빠진다.
        #
        #    방향을 못 바꾸는 것보다 나쁜 것은 틀린 방향으로 바꾸는 것이다.
        if self._tail_clearance(sign) < self._tail_limit:
            self._say(
                f"{'좌' if sign > 0 else '우'}회전으로 물러나고 싶은데 꼬리 쪽이 "
                f"{self._tail_clearance(sign):.2f} m 뿐이다 -- 곧게 물러난다"
            )
            return 0.0

        self._say(f"탈출 방향: {'좌' if sign > 0 else '우'}회전 ({source})")
        return sign

    def _escape_yaw(self) -> float:
        """반경에서 요레이트를 낸다 -- 속도가 바뀌어도 조향각은 그대로."""
        if self._escape_radius <= 0.0:
            return 0.0
        return self._steer_sign * (self._speed / self._escape_radius)

    def _say(self, text: str) -> None:
        if text != self._last_report:
            self._last_report = text
            self._report.publish(String(data=text))
            self.get_logger().info(text)

    def _tick(self) -> None:
        if self._recovering_until is not None:
            self._reverse()
            return

        blocked = (
            self._mode == NAV
            and self._status is not None
            and self._status.get("action") == "STOP"
            and abs(self._speed_measured) < self._rolling
        )
        if not blocked:
            self._blocked_since = None
            return

        now = time.monotonic()
        if self._blocked_since is None:
            self._blocked_since = now
            return
        if now - self._blocked_since < self._stuck_for:
            return

        if self._attempts >= self._max_attempts:
            self._say(
                f"갇혔는데 {self._attempts}번 시도해도 못 나왔다 -- 사람이 필요하다"
            )
            return
        if self._rear() < self._rear_limit:
            self._say(
                f"갇혔는데 뒤도 {self._rear():.2f} m 뿐이다 -- 사람이 필요하다"
            )
            return

        self._attempts += 1
        self._blocked_since = None
        self._recovering_until = now + self._max_reverse
        self._rolling_since = None
        self._steer_until = None
        self._steer_spent = False
        self._escape_started = now
        self._steer_sign = self._pick_escape_sign()
        self._mode_publisher.publish(String(data=RECOVER))
        self._say(
            f"갇힘 감지 (앞 {self._ahead():.2f} m) -- 후진으로 빠져나온다 "
            f"({self._attempts}/{self._max_attempts})"
        )

    def _reverse(self) -> None:
        now = time.monotonic()
        yaw = self._steer_yaw(now)
        # 조향을 아직 못 쓴 채로 앞이 트였다고 끝내면, 방향이 안 바뀐 채
        # 들어온 호를 되짚기만 한 것이다 -- 기능이 조용히 무효가 된다.
        steer_owed = self._steer_sign != 0.0 and not self._steer_spent

        done = None
        if self._ahead() >= self._target and not steer_owed:
            done = f"확보됨 (앞 {self._ahead():.2f} m)"
        elif self._rear() < self._rear_limit:
            # 안전 출구는 조향 예산과 무관하게 즉시 선다.
            done = f"뒤가 {self._rear():.2f} m 로 가까워졌다 -- 중단"
        elif now >= self._recovering_until:
            done = f"시간 초과 -- 앞 {self._ahead():.2f} m 에서 그만둔다"

        if done is None:
            command = Twist()
            command.linear.x = -self._speed
            # 이탈은 곧게, 구르기 시작하면 꺾는다. 부호는 안 뒤집는다 --
            # 후진이라 후륜각이 저절로 반대가 된다.
            command.angular.z = yaw
            self._command.publish(command)
            return

        self._command.publish(Twist())
        self._recovering_until = None
        self._steer_sign = 0.0
        self._mode_publisher.publish(String(data=NAV))
        if self._ahead() >= self._target:
            # 성공했으니 다음 갇힘은 새 사건으로 센다.
            self._attempts = 0
        self._say(f"{done} -- 운전대를 NAV 로 돌려준다")

    def _steer_yaw(self, now: float) -> float:
        """이번 주기에 실을 요레이트. 조건이 하나라도 어긋나면 0(직선).

        ⚠️ **꺾은 채로 출발한다.** 종전에는 곧게 떠서 구르기 시작한 뒤에
           꺾었는데, 그러면 후진 거리의 앞부분을 방향 안 바꾼 채로 써 버리고
           목업처럼 좁은 곳에서는 **꺾기도 전에 뒤가 막혀 끝난다.**

           곧게 먼저 가던 근거는 "꺾인 채로는 정지에서 못 뜬다(2026-08-07)"
           였는데 그 측정은 08-09 에 뒤집혔다(mapping.py: 후륜 25.6°·43.8°·
           50.2° 에서 모두 출발). 탈출 각 19.8° 는 그보다 한참 아래다.

           대신 못 뜨는 경우를 위해 escape_breakaway_sec 안에 한 번도 못 구르면
           각을 펴고 곧게 간다. 후진은 조향륜이 앞장서 옆으로 갈아내므로
           전진보다 불리하다는 것은 그대로 사실이다.
        """
        if self._steer_sign == 0.0 or self._steer_spent:
            return 0.0

        rolling = abs(self._speed_measured) >= self._escape_rolling
        if rolling and self._rolling_since is None:
            self._rolling_since = now

        if self._rolling_since is None:
            # 아직 한 번도 못 굴렀다. 꺾은 채로 버텨 보다가, 그래도 안 되면
            # 각이 이 바닥에 과한 것이므로 편다.
            if (self._escape_started is not None
                    and now - self._escape_started >= self._breakaway_sec):
                self._steer_spent = True
                self._say("꺾은 채로 못 떴다 -- 각을 펴고 곧게 물러난다")
                return 0.0
        elif not rolling and self._steer_until is not None:
            # 굴러가다 꺾은 채로 섰다. 듀티로 밀지 않고 곧게 돌아간다 --
            # 08-07 에 밀어붙여 실패한 방법이다.
            self._steer_spent = True
            self._say("조향 중 멈췄다 -- 각을 접고 곧게 물러난다")
            return 0.0

        if self._tail_clearance(self._steer_sign) < self._tail_limit:
            self._steer_spent = True
            self._say("꼬리 쪽이 닫혔다 -- 각을 접고 곧게 물러난다")
            return 0.0

        # 예산은 **구르기 시작한 뒤부터** 센다. 출발이 늦었다고 각을 쓸 시간이
        # 줄면, 좁은 곳에서 정작 필요한 회전을 못 한다.
        if self._rolling_since is not None:
            if self._steer_until is None:
                self._steer_until = self._rolling_since + self._steer_sec
            if now >= self._steer_until:
                self._steer_spent = True
                return 0.0
        return self._escape_yaw()


def main(args=None) -> None:
    rclpy.init(args=args)
    node = UnstickNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
