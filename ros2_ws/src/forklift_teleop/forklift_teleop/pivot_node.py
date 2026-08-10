"""Turn the vehicle in place by shuttling forward and back on full steering.

목업 통로에서 차가 방향을 못 바꾸는 일이 반복됐다. 경로도 컨트롤러도 정상인데
(플래너는 +y 로 2.22 m 가는 경로를 내고, RPP 는 w 0.1~0.39 로 좌회전을 명령했다)
차는 앞뒤로만 흔들리고 yaw 가 -1° 에서 안 움직였다. 돌 자리가 없어서였다.

사람은 이럴 때 한 번에 안 돌고 **꺾어서 조금 가고, 반대로 꺾어서 조금 물러나기**
를 되풀이한다. 이 노드가 그것이다.

⚠️ **핵심은 전진과 후진에서 조향을 반대로 준다는 것이다.** 후륜 조향차는
   ICR 이 전축선 위에 있어 `R = wheelbase / tan(δ)` 이고, 요레이트는 `v·tan(δ)/L`
   이다. 그래서

       전진(+v) · 좌조향(+δ)  ->  yaw 증가
       후진(-v) · 좌조향(+δ)  ->  yaw **감소**  (되돌아간다)
       후진(-v) · 우조향(-δ)  ->  yaw 증가      (계속 쌓인다)

   같은 조향으로 물러나면 방금 튼 것을 그대로 푼다 -- 그게 지금까지 "앞뒤앞뒤
   하는데 방향이 안 바뀌던" 이유다. 부호를 뒤집어야 한 방향으로 누적된다.

   앞뒤 이동은 서로 상쇄되므로 차는 거의 제자리에서 돈다.

⚠️ **완전 조향 상태에서는 정지에서 못 뜬다** (실측 100% 듀티까지 확인). 그래서
   방향을 바꿀 때마다 **조향을 먼저 주고 잠깐 기다린 뒤** 구동을 넣는다.
   uart_teleop_bridge 의 StartupKick 이 서보가 자리잡기를 기다리는 것과 같은
   이유이고, 여기서 서두르면 한 발도 못 간다.

⚠️ **가드는 그대로 산다.** 앞뒤 여유는 `/obstacle_avoidance/status` 를 보고
   직접 줄인다 -- 가드가 STOP 을 걸 거리까지 가면 그 방향은 그만두고 반대로
   넘어간다. 벽을 밀며 헛도는 것을 막는 것이 이 노드의 절반이다.
"""

import json
import math
import time
from typing import Optional

from geometry_msgs.msg import Twist
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile
from std_msgs.msg import String
from tf2_ros import Buffer, TransformListener

NAV = "NAV"
PIVOT = "PIVOT"


def shuttle_command(
    *, going_forward: bool, turn_left: bool, speed: float, curvature: float,
) -> Twist:
    """한 구간의 명령. **요레이트는 두 구간 모두 같은 부호다.**

    `angular.z` 는 이미 "원하는 요레이트" 이지 조향각이 아니다. 같은 방향으로
    쌓으려면 두 구간이 같은 요레이트를 요구해야 한다.

    조향은 저절로 뒤집힌다. mapping.py 가 곡률 = w/v 로 조향을 만드는데
    후진이면 v 가 음수라 곡률의 부호가 뒤집히기 때문이다:

        전진 v=+0.22 w=+1.98 -> 곡률 +9  -> 후륜 +54° (좌)  -> yaw +1.98
        후진 v=-0.22 w=+1.98 -> 곡률 -9  -> 후륜 -54° (우)  -> yaw +1.98

    여기서 부호를 한 번 더 뒤집으면 두 구간이 정확히 상쇄되어 차는 앞뒤로
    흔들리기만 한다 -- 처음에 그렇게 짰고 test_pivot 이 잡았다.
    """
    command = Twist()
    command.linear.x = speed if going_forward else -speed
    command.angular.z = abs(speed) * curvature * (1.0 if turn_left else -1.0)
    return command


class PivotNode(Node):
    def __init__(self) -> None:
        super().__init__("pivot_node")

        self.declare_parameter("request_topic", "/pivot/request")
        self.declare_parameter("result_topic", "/pivot/result")
        self.declare_parameter("status_topic", "/obstacle_avoidance/status")
        self.declare_parameter("mode_topic", "/drive/mode")
        self.declare_parameter("command_topic", "/cmd_vel_pivot")
        self.declare_parameter("map_frame", "map")
        self.declare_parameter("base_frame", "base_link")
        # 꺾인 채로 정지에서 뜨려면 속도가 필요하다. 느리면 조향륜이
        # 바닥을 옆으로 긁는 저항을 못 이긴다.
        self.declare_parameter("speed_mps", 0.32)
        # 최소 회전반경 0.090 m 의 역수가 11.1 이다. 조금 남겨 둔다.
        self.declare_parameter("curvature_per_m", 9.0)
        # 한 구간의 최대 길이. 길면 제자리에서 벗어나고, 짧으면 정지마찰을
        # 이기는 데만 쓰다 끝난다.
        self.declare_parameter("leg_seconds", 2.0)
        # 방향을 바꾼 뒤 서보가 자리잡기를 기다리는 시간.
        self.declare_parameter("steering_settle_sec", 0.5)
        self.declare_parameter("front_limit_m", 0.35)
        self.declare_parameter("rear_limit_m", 0.40)
        self.declare_parameter("tolerance_rad", 0.15)
        self.declare_parameter("max_legs", 16)

        self._speed = abs(float(self.get_parameter("speed_mps").value))
        self._curvature = abs(float(self.get_parameter("curvature_per_m").value))
        self._leg = float(self.get_parameter("leg_seconds").value)
        self._settle = float(self.get_parameter("steering_settle_sec").value)
        self._front_limit = float(self.get_parameter("front_limit_m").value)
        self._rear_limit = float(self.get_parameter("rear_limit_m").value)
        self._tolerance = float(self.get_parameter("tolerance_rad").value)
        self._max_legs = int(self.get_parameter("max_legs").value)
        self._map_frame = str(self.get_parameter("map_frame").value)
        self._base_frame = str(self.get_parameter("base_frame").value)

        self._status: Optional[dict] = None
        self._pending: Optional[dict] = None
        self._running = False

        self.create_subscription(
            String, str(self.get_parameter("status_topic").value),
            self._on_status, 10)
        self.create_subscription(
            String, str(self.get_parameter("request_topic").value),
            self._on_request, 10)

        latched = QoSProfile(depth=1)
        latched.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
        self._mode = self.create_publisher(
            String, str(self.get_parameter("mode_topic").value), latched)
        self._result = self.create_publisher(
            String, str(self.get_parameter("result_topic").value), latched)
        self._command = self.create_publisher(
            Twist, str(self.get_parameter("command_topic").value), 10)

        self._buffer = Buffer()
        TransformListener(self._buffer, self)

        self.get_logger().info(
            "Pivot ready: /pivot/request 에 {\"deltaYaw\": 1.57} 또는 "
            "{\"targetYaw\": 1.57} 을 넣으면 제자리에서 방향을 바꾼다"
        )

    # ---- 입력 -------------------------------------------------------

    def _on_status(self, message: String) -> None:
        try:
            self._status = json.loads(message.data)
        except (TypeError, ValueError):
            self._status = None

    def _on_request(self, message: String) -> None:
        if self._running:
            self.get_logger().warning("이미 도는 중이라 새 요청을 무시한다")
            return
        try:
            self._pending = json.loads(message.data)
        except (TypeError, ValueError) as error:
            self._say(False, f"요청을 못 읽었다: {error}")

    # ---- 상태 -------------------------------------------------------

    def _ahead(self) -> float:
        if not self._status:
            return math.inf
        values = [v for v in (self._status.get("roofLidar", {}).get("center"),
                              self._status.get("frontTof", {}).get("path"))
                  if v is not None]
        return min(values) if values else math.inf

    def _behind(self) -> float:
        if not self._status:
            return math.inf
        value = self._status.get("roofLidar", {}).get("rear")
        return math.inf if value is None else value

    def _yaw(self) -> Optional[float]:
        try:
            t = self._buffer.lookup_transform(
                self._map_frame, self._base_frame, rclpy.time.Time())
        except Exception:
            return None
        z, w = t.transform.rotation.z, t.transform.rotation.w
        return math.atan2(2 * w * z, 1 - 2 * z * z)

    def _say(self, ok: bool, detail: str) -> None:
        self._result.publish(String(data=json.dumps(
            {"state": "DONE" if ok else "ERROR", "detail": detail})))
        (self.get_logger().info if ok else self.get_logger().error)(detail)

    def _spin(self, seconds: float) -> None:
        deadline = time.monotonic() + seconds
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(self, timeout_sec=0.02)

    # ---- 동작 -------------------------------------------------------

    def run(self, request: dict) -> bool:
        yaw = self._yaw()
        if yaw is None:
            self._say(False, f"{self._map_frame}->{self._base_frame} 을 못 받았다")
            return False

        if "targetYaw" in request:
            remaining = _wrap(float(request["targetYaw"]) - yaw)
        elif "deltaYaw" in request:
            remaining = _wrap(float(request["deltaYaw"]))
        else:
            self._say(False, "deltaYaw 나 targetYaw 가 없다")
            return False

        turn_left = remaining > 0.0
        target = _wrap(yaw + remaining)
        self.get_logger().info(
            f"제자리 전환: {math.degrees(yaw):+.0f}° -> {math.degrees(target):+.0f}° "
            f"({'좌' if turn_left else '우'}, {math.degrees(abs(remaining)):.0f}° 남음)"
        )

        self._running = True
        self._mode.publish(String(data=PIVOT))
        self._spin(0.3)
        going_forward = self._ahead() >= self._behind()
        moved = 0.0
        try:
            for leg in range(self._max_legs):
                yaw = self._yaw()
                if yaw is None:
                    self._say(False, "도중에 자세를 잃었다")
                    return False
                remaining = _wrap(target - yaw)
                if abs(remaining) <= self._tolerance:
                    self._say(True, f"방향 맞춤 ({math.degrees(yaw):+.0f}°, "
                                    f"{leg}구간, 누적 {math.degrees(moved):.0f}°)")
                    return True

                room = self._ahead() if going_forward else self._behind()
                limit = self._front_limit if going_forward else self._rear_limit
                if room < limit:
                    # 이쪽은 더 못 간다. 반대로 넘긴다. 두 방향 다 막혔으면
                    # 애초에 여기서 돌 수 없다는 뜻이다.
                    other = self._behind() if going_forward else self._ahead()
                    other_limit = (self._rear_limit if going_forward
                                   else self._front_limit)
                    if other < other_limit:
                        self._say(False,
                                  f"앞 {self._ahead():.2f} m · 뒤 "
                                  f"{self._behind():.2f} m -- 여기서는 못 돈다")
                        return False
                    going_forward = not going_forward
                    continue

                before = yaw
                self._drive_one_leg(going_forward, turn_left)
                after = self._yaw()
                if after is not None:
                    moved += abs(_wrap(after - before))
                going_forward = not going_forward

            self._say(False, f"{self._max_legs}구간을 써도 방향을 못 맞췄다 "
                             f"(누적 {math.degrees(moved):.0f}°)")
            return False
        finally:
            self._command.publish(Twist())
            self._mode.publish(String(data=NAV))
            self._running = False

    def _drive_one_leg(self, going_forward: bool, turn_left: bool) -> None:
        command = shuttle_command(
            going_forward=going_forward, turn_left=turn_left,
            speed=self._speed, curvature=self._curvature)

        # ⚠️ **조향만 따로 보낼 수는 없다.** 곡률이 w/v 라 선속도가 0 이면
        #    조향각이 정의되지 않고, mapping.py 는 중립(9000)을 내보낸다.
        #    처음에는 여기서 v=0 으로 "조향만 미리 주고 기다리기" 를 했는데,
        #    그러면 대기 구간이 통째로 버려지고 남은 시간은 브리지의 시동 킥이
        #    서보를 기다리는 데 다 쓰인다 -- 16구간을 돌고도 6° 만 쌓였다.
        #
        #    그래서 한 구간을 통째로 같은 명령으로 보낸다. 서보가 도는 동안
        #    구동이 안 걸리는 것은 브리지의 StartupKick 이 이미 그렇게 만들고
        #    있으므로(_steering_has_settled), 여기서 또 나눌 이유가 없다.
        #    settle 만큼 구간을 길게 잡아 그 시간을 보전한다.
        deadline = time.monotonic() + self._settle + self._leg
        while rclpy.ok() and time.monotonic() < deadline:
            room = self._ahead() if going_forward else self._behind()
            limit = self._front_limit if going_forward else self._rear_limit
            if room < limit:
                break
            self._command.publish(command)
            rclpy.spin_once(self, timeout_sec=0.02)
        self._command.publish(Twist())

    def pump(self) -> None:
        if self._pending is None:
            return
        request, self._pending = self._pending, None
        self.run(request)


def _wrap(angle: float) -> float:
    return (angle + math.pi) % (2 * math.pi) - math.pi


def main(args=None) -> None:
    rclpy.init(args=args)
    node = PivotNode()
    try:
        while rclpy.ok():
            rclpy.spin_once(node, timeout_sec=0.05)
            node.pump()
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
