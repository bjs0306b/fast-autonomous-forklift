"""Switch who is driving from inside RViz, by right-clicking the vehicle.

`drive_mux` decides which node's commands reach the motor, and until now the
only way to change that was `ros2 topic pub` from a terminal. During a demo
there is no terminal -- there is RViz, a person, and a forklift doing something
unexpected. The one thing they need at that moment is to take the wheel away
from whoever has it.

RViz has no button panel without a C++ plugin, but it does render interactive
markers with right-click context menus, and those are served from plain Python.
So the vehicle itself becomes the control: right-click it, pick a mode.

    NAV      nav2 가 운전한다 (기본)
    ALIGN    파렛 정렬 노드가 운전한다
    RECOVER  탈출 후진이 운전한다
    IDLE     아무도 운전하지 않는다 -- 즉시 정지

⚠️ **IDLE 은 정지이지 비상정지가 아니다.** 중재기가 0 을 계속 발행하므로 차는
   서지만, 전원이나 펌웨어 워치독을 대신하지 않는다.

⚠️ 마커는 `base_link` 에 붙는다. 그래서 지도 위 차 위치에 그대로 따라다니고,
   자세 추정이 틀어지면 마커도 함께 틀어진 곳에 있다 -- 그것 자체가 신호다.
"""

from interactive_markers import InteractiveMarkerServer, MenuHandler
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile
from std_msgs.msg import String
from visualization_msgs.msg import (
    InteractiveMarker,
    InteractiveMarkerControl,
    Marker,
)

MODES = ("NAV", "ALIGN", "RECOVER", "IDLE")


class ModeMarker(Node):
    def __init__(self) -> None:
        super().__init__("mode_marker")
        self.declare_parameter("frame_id", "base_link")
        self.declare_parameter("mode_topic", "/drive/mode")
        self.declare_parameter("marker_size_m", 0.30)

        latched = QoSProfile(depth=1)
        latched.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
        self._publisher = self.create_publisher(
            String, str(self.get_parameter("mode_topic").value), latched)
        self.create_subscription(
            String, str(self.get_parameter("mode_topic").value),
            self._on_mode, latched)
        self._mode = "NAV"

        self._server = InteractiveMarkerServer(self, "drive_mode")
        self._menu = MenuHandler()
        self._entries = {}
        for mode in MODES:
            entry = self._menu.insert(
                mode, callback=self._make_callback(mode))
            self._entries[mode] = entry
            self._menu.setCheckState(entry, MenuHandler.UNCHECKED)
        self._menu.setCheckState(self._entries["NAV"], MenuHandler.CHECKED)

        self._server.insert(self._marker())
        self._menu.apply(self._server, "drive_mode")
        self._server.applyChanges()

        self.get_logger().info(
            "RViz 에서 차를 우클릭하면 운전 모드를 바꿀 수 있다 "
            f"({' / '.join(MODES)})"
        )

    def _marker(self) -> InteractiveMarker:
        size = float(self.get_parameter("marker_size_m").value)
        marker = InteractiveMarker()
        marker.header.frame_id = str(self.get_parameter("frame_id").value)
        marker.name = "drive_mode"
        marker.description = "운전 모드 (우클릭)"
        marker.scale = size

        box = Marker()
        box.type = Marker.CUBE
        box.scale.x = size * 0.6
        box.scale.y = size * 0.6
        box.scale.z = size * 0.2
        # 반투명 -- 차체와 라이다 점을 가리면 안 된다.
        box.color.r, box.color.g, box.color.b, box.color.a = 0.1, 0.6, 1.0, 0.5

        control = InteractiveMarkerControl()
        control.interaction_mode = InteractiveMarkerControl.MENU
        control.always_visible = True
        control.markers.append(box)
        marker.controls.append(control)
        return marker

    def _make_callback(self, mode: str):
        def callback(_feedback) -> None:
            self._publisher.publish(String(data=mode))
            self.get_logger().info(f"RViz 에서 운전 모드를 {mode} 로 바꿨다")
        return callback

    def _on_mode(self, message: String) -> None:
        """다른 노드가 바꾼 모드도 체크 표시에 반영한다.

        탈출 후진은 스스로 RECOVER 를 잡았다가 NAV 로 돌려준다. 메뉴가 사람이
        고른 것만 기억하면, 차가 실제로 무엇에 끌려가는지와 화면이 어긋난다.
        """
        mode = message.data.strip().upper()
        if mode not in MODES or mode == self._mode:
            return
        self._mode = mode
        for name, entry in self._entries.items():
            self._menu.setCheckState(
                entry,
                MenuHandler.CHECKED if name == mode else MenuHandler.UNCHECKED)
        self._menu.reApply(self._server)
        self._server.applyChanges()


def main(args=None) -> None:
    rclpy.init(args=args)
    node = ModeMarker()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
