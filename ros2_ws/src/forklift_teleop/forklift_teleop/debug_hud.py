"""Show, on the vehicle in RViz, why it is or is not moving.

주행이 멈추면 이유가 최소 네 가지다. 가드가 세웠거나, 플래너가 경로를 못
냈거나, 컨트롤러가 경로를 갖고도 0 을 내거나, 조향 명령이 서보까지 못 갔거나.
**밖에서는 넷이 똑같이 "차가 안 움직인다" 로 보인다.** 그래서 매번 로그를
뒤졌고, 그러는 동안 차는 그 자리에 서 있었다.

이 노드는 이미 있는 토픽만 모아 `base_link` 위에 글자로 띄운다. 새로 재는 것은
없고, 흩어져 있던 것을 한 화면에 놓을 뿐이다:

    가드   SLOW — front obstacle at 0.67m
    운전   NAV: driving
    nav2   v=+0.12  w=+0.30
    서보   +18.7°  듀티 75%  엔코더 +0.26
    경로   108점 (0.4초 전)
    미션   T-0042 NAV

이 여섯 줄이면 위 네 가지가 갈린다. 경로가 0점이면 플래너, 경로가 있는데
nav2 가 0 이면 컨트롤러, nav2 가 내는데 서보가 0 이면 브리지 아래쪽이다.

⚠️ **경로는 점 수가 아니라 "몇 초 전"이 중요하다.** 재계획이 도는 동안에는
   점 수가 그대로여도 시각이 계속 새로 찍힌다. 굳어 있으면 플래너가 포기한
   것이다 -- 이 구분이 "멈춘 건가 다른 경로를 만드는 중인가" 의 답이다.

⚠️ **`/teleop/command` 는 가드 출력이 아니라 실제 UART 로 나간 값이다.**
   그 사이에 킥·속도제어·곡률제한이 들어가므로 둘은 다르다. 2026-08-08 에
   가드 출력만 보고 "조향 57도 나간다" 고 했는데 브리지는 죽어 있었다.
"""

import json
import time
from typing import Optional

from geometry_msgs.msg import Twist
from nav_msgs.msg import Path
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile
from std_msgs.msg import String
from visualization_msgs.msg import Marker, MarkerArray

# 가드 판정별 색. 흰색은 "정상", 노랑은 "가고는 있다", 빨강은 "서 있다".
COLORS = {
    "CLEAR": (1.0, 1.0, 1.0),
    "SLOW": (1.0, 0.85, 0.2),
    "AVOID_LEFT": (1.0, 0.85, 0.2),
    "AVOID_RIGHT": (1.0, 0.85, 0.2),
    "STOP": (1.0, 0.3, 0.3),
    "SENSOR_TIMEOUT": (1.0, 0.3, 0.3),
}


def _age(stamp: Optional[float], now: float) -> str:
    """마지막으로 온 지 얼마나 됐나. 값 자체보다 이게 답인 경우가 많다."""
    if stamp is None:
        return "없음"
    delta = now - stamp
    return f"{delta:.1f}초 전" if delta < 100.0 else "오래됨"


class DebugHud(Node):
    def __init__(self) -> None:
        super().__init__("debug_hud")

        self.declare_parameter("frame_id", "base_link")
        self.declare_parameter("marker_topic", "/debug/hud")
        self.declare_parameter("publish_rate_hz", 5.0)
        self.declare_parameter("text_height_m", 0.06)
        self.declare_parameter("text_z_m", 0.45)

        self._frame = str(self.get_parameter("frame_id").value)
        self._height = float(self.get_parameter("text_height_m").value)
        self._z = float(self.get_parameter("text_z_m").value)

        self._guard: Optional[dict] = None
        self._mux = "?"
        self._mode = "?"
        self._nav: Optional[Twist] = None
        self._nav_at: Optional[float] = None
        self._teleop: Optional[dict] = None
        self._plan_points = 0
        self._plan_at: Optional[float] = None
        self._mission: Optional[dict] = None

        latched = QoSProfile(depth=1)
        latched.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL

        self.create_subscription(
            String, "/obstacle_avoidance/status", self._on_guard, 10)
        self.create_subscription(
            String, "/teleop/command", self._on_teleop, 10)
        self.create_subscription(Twist, "/cmd_vel", self._on_nav, 10)
        self.create_subscription(Path, "/plan", self._on_plan, 10)
        self.create_subscription(
            String, "/drive/mux_status",
            lambda m: setattr(self, "_mux", m.data), latched)
        self.create_subscription(
            String, "/drive/mode",
            lambda m: setattr(self, "_mode", m.data.strip().upper()), latched)
        self.create_subscription(
            String, "/mission/status", self._on_mission, latched)

        self._publisher = self.create_publisher(
            MarkerArray, str(self.get_parameter("marker_topic").value), 1)

        rate = float(self.get_parameter("publish_rate_hz").value)
        if rate <= 0.0:
            raise ValueError("publish_rate_hz must be positive")
        self.create_timer(1.0 / rate, self._on_timer)

        self.get_logger().info(
            "Debug HUD ready: RViz 에 MarkerArray "
            f"{self.get_parameter('marker_topic').value} 를 추가할 것"
        )

    # ---- 모으기 -----------------------------------------------------

    def _on_guard(self, message: String) -> None:
        try:
            self._guard = json.loads(message.data)
        except (TypeError, ValueError):
            self._guard = None

    def _on_teleop(self, message: String) -> None:
        try:
            self._teleop = json.loads(message.data)
        except (TypeError, ValueError):
            self._teleop = None

    def _on_nav(self, message: Twist) -> None:
        self._nav = message
        self._nav_at = time.monotonic()

    def _on_plan(self, message: Path) -> None:
        self._plan_points = len(message.poses)
        self._plan_at = time.monotonic()

    def _on_mission(self, message: String) -> None:
        try:
            self._mission = json.loads(message.data)
        except (TypeError, ValueError):
            self._mission = None

    # ---- 보여주기 ---------------------------------------------------

    def _lines(self, now: float):
        guard_action = "-"
        if self._guard:
            guard_action = str(self._guard.get("action", "-"))
            reason = str(self._guard.get("reason", ""))
            guard = f"가드   {guard_action}"
            if reason:
                guard += f" — {reason}"
        else:
            guard = "가드   (없음 — /cmd_vel 이 흐를 때만 발행한다)"

        drive = f"운전   {self._mux}"
        if self._mode not in ("?", "") and self._mode not in self._mux:
            drive += f"  [모드 {self._mode}]"

        if self._nav is None:
            nav = "nav2   없음"
        else:
            nav = (f"nav2   v={self._nav.linear.x:+.2f}  "
                   f"w={self._nav.angular.z:+.2f}  "
                   f"({_age(self._nav_at, now)})")

        if self._teleop is None:
            servo = "서보   /teleop/command 없음 — 브리지 확인"
        else:
            measured = self._teleop.get("measuredMps")
            encoder = "모름" if measured is None else f"{measured:+.3f}"
            servo = (f"서보   {self._teleop.get('rearSteeringDeg', 0.0):+.1f}°  "
                     f"듀티 {self._teleop.get('drivePercent', 0)}%  "
                     f"엔코더 {encoder}")

        # 점 수가 아니라 갱신 시각이 "재계획 중인가" 를 가른다.
        plan = f"경로   {self._plan_points}점 ({_age(self._plan_at, now)})"
        if self._plan_points == 0:
            plan += "  ← 플래너가 못 냈다"

        if self._mission is None:
            mission = "미션   없음"
        else:
            mission = (f"미션   {self._mission.get('taskId', '?')} "
                       f"{self._mission.get('stage', '?')}")
            detail = self._mission.get("detail")
            if detail:
                mission += f" — {detail}"

        return guard_action, [guard, drive, nav, servo, plan, mission]

    def _on_timer(self) -> None:
        now = time.monotonic()
        action, lines = self._lines(now)
        red, green, blue = COLORS.get(action, (0.7, 0.7, 0.7))

        marker = Marker()
        marker.header.frame_id = self._frame
        marker.header.stamp = self.get_clock().now().to_msg()
        marker.ns = "debug_hud"
        marker.id = 0
        marker.type = Marker.TEXT_VIEW_FACING
        marker.action = Marker.ADD
        marker.pose.position.z = self._z
        marker.pose.orientation.w = 1.0
        marker.scale.z = self._height
        marker.color.r, marker.color.g, marker.color.b = red, green, blue
        marker.color.a = 1.0
        marker.text = "\n".join(lines)

        array = MarkerArray()
        array.markers.append(marker)
        self._publisher.publish(array)


def main(args=None) -> None:
    rclpy.init(args=args)
    node = DebugHud()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
