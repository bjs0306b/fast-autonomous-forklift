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

Then straight reverse until there is room ahead, and hand it back. This is
exactly what tools/back_off.py does by hand; the value here is that nobody has
to be standing there.

⚠️ **Only from NAV.** During ALIGN the vehicle is deliberately closing on a
   pallet and the guard may well object -- reversing then would undo the
   approach, or pull the forks out of a pallet they are already inside.

⚠️ **Straight only.** With the rear wheels turned this vehicle cannot start
   from rest at any duty the drivetrain has (verified to 100%). Straightening
   first is what makes the escape possible at all, and mapping.py waits for
   the servo to actually get there before granting the straight-reverse duty.

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

        self._stuck_for = float(self.get_parameter("stuck_for_sec").value)
        self._rolling = float(self.get_parameter("rolling_mps").value)
        self._speed = abs(float(self.get_parameter("reverse_speed_mps").value))
        self._target = float(self.get_parameter("clearance_target_m").value)
        self._rear_limit = float(self.get_parameter("rear_limit_m").value)
        self._max_reverse = float(self.get_parameter("max_reverse_sec").value)
        self._max_attempts = int(self.get_parameter("max_attempts").value)

        self._status = None
        self._speed_measured = 0.0
        self._mode = NAV
        self._blocked_since = None
        self._recovering_until = None
        self._attempts = 0
        self._last_report = ""

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
        self._mode_publisher.publish(String(data=RECOVER))
        self._say(
            f"갇힘 감지 (앞 {self._ahead():.2f} m) -- 후진으로 빠져나온다 "
            f"({self._attempts}/{self._max_attempts})"
        )

    def _reverse(self) -> None:
        now = time.monotonic()
        done = None
        if self._ahead() >= self._target:
            done = f"확보됨 (앞 {self._ahead():.2f} m)"
        elif self._rear() < self._rear_limit:
            done = f"뒤가 {self._rear():.2f} m 로 가까워졌다 -- 중단"
        elif now >= self._recovering_until:
            done = f"시간 초과 -- 앞 {self._ahead():.2f} m 에서 그만둔다"

        if done is None:
            command = Twist()
            command.linear.x = -self._speed
            # 조향은 중립이다. 꺾인 채로는 정지에서 못 뜬다.
            self._command.publish(command)
            return

        self._command.publish(Twist())
        self._recovering_until = None
        self._mode_publisher.publish(String(data=NAV))
        if self._ahead() >= self._target:
            # 성공했으니 다음 갇힘은 새 사건으로 센다.
            self._attempts = 0
        self._say(f"{done} -- 운전대를 NAV 로 돌려준다")


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
