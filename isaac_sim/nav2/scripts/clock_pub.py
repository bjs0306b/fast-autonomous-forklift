"""Isaac 시뮬 시간을 /clock 으로 발행한다 (Script Editor 용).

씬의 ActionGraph 에 있는 ROS2 Publish Clock 노드가 없거나 멈췄을 때 대체한다.
use_sim_time 을 쓰는 Nav2 는 /clock 이 없으면 통째로 얼어붙으므로, 이게
살아있어야 나머지가 전부 동작한다.

이 노드 자신은 use_sim_time=False 여야 한다(자기가 clock 을 만드는 쪽이므로).

사용 (Isaac Script Editor):
    exec(open('/home/ubuntu/forklift_ws/nav2/scripts/clock_pub.py').read())

중지:
    clock_pub.stop()
"""
import omni.kit.app
import omni.timeline
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSProfile, QoSReliabilityPolicy, QoSHistoryPolicy
from rosgraph_msgs.msg import Clock


class ClockPublisher(Node):
    def __init__(self):
        super().__init__("isaac_clock_pub")
        # BEST_EFFORT + depth 1: 시간은 최신값만 의미가 있다.
        self.pub = self.create_publisher(
            Clock, "/clock",
            QoSProfile(depth=1,
                       history=QoSHistoryPolicy.KEEP_LAST,
                       reliability=QoSReliabilityPolicy.BEST_EFFORT))
        self.tl = omni.timeline.get_timeline_interface()
        self._sub = (
            omni.kit.app.get_app()
            .get_update_event_stream()
            .create_subscription_to_pop(self._on_update, name="isaac_clock_pub")
        )
        self.get_logger().info("clock 발행 시작")

    def _on_update(self, event):
        t = self.tl.get_current_time()
        msg = Clock()
        msg.clock.sec = int(t)
        msg.clock.nanosec = int((t - int(t)) * 1e9)
        self.pub.publish(msg)

    def stop(self):
        self._sub = None
        self.destroy_node()
        print("clock 발행 중지")


if not rclpy.ok():
    rclpy.init()

try:
    clock_pub.stop()          # 이전 인스턴스가 있으면 정리
except NameError:
    pass

clock_pub = ClockPublisher()
print("clock_pub 생성됨. 중지하려면 clock_pub.stop()")
