#!/usr/bin/env python3
"""Isaac Sim 이 내보내는 센서값을 실물 축척으로 변환한다.

시뮬 세계는 실물의 SCALE 배 크기다. 시뮬이 발행하는 raw 토픽은 시뮬 단위이므로
그대로 두면 Nav2 가 실물과 다른 숫자를 보게 되고, 시뮬에서 튜닝한 파라미터가
실물로 전이되지 않는다.

이 노드가 ROS2 경계에서 한 번만 나눠주면, 그 위쪽(Nav2·관제·MQTT)은 전부
실물 축척으로 통일된다. "눈에 보이는 3D만 크고, 숫자는 전부 실물 기준."

    /SIM_F01/raw/scan  ──┐                  ┌──> /SIM_F01/scan
                          ├─ 이 노드 (÷N) ─┤
    /SIM_F01/raw/odom  ──┘                  └──> /SIM_F01/odom

구독 2개, 발행 2개, 노드는 1개다.

실행:
    ros2 run forklift_control sensor_scaler --ros-args -r __ns:=/SIM_F01
"""
import rclpy
from nav_msgs.msg import Odometry
from rclpy.node import Node
from sensor_msgs.msg import LaserScan

from . import fast_params as P


class SensorScaler(Node):
    def __init__(self):
        super().__init__("sensor_scaler")

        # --- 발행 2개 ---
        self._scan_pub = self.create_publisher(LaserScan, "scan", 10)
        self._odom_pub = self.create_publisher(Odometry, "odom", 10)

        # --- 구독 2개 ---
        # 메시지가 도착할 때마다 뒤에 적은 콜백 함수가 자동으로 불린다.
        self.create_subscription(LaserScan, "raw/scan", self._on_scan, 10)
        self.create_subscription(Odometry, "raw/odom", self._on_odom, 10)

        self.get_logger().info(f"축척 변환 시작 — 시뮬 값을 {P.SCALE} 로 나눠 발행")

    # ------------------------------------------------------------------
    # 라이다
    # ------------------------------------------------------------------
    def _on_scan(self, msg: LaserScan):
        """거리만 나눈다. 각도와 시간은 축척과 무관하므로 건드리지 않는다."""
        out = LaserScan()
        out.header = msg.header

        # 각도 — 그대로. 축척이 바뀌어도 각도는 변하지 않는다.
        out.angle_min = msg.angle_min
        out.angle_max = msg.angle_max
        out.angle_increment = msg.angle_increment

        # 시간 — 그대로.
        out.time_increment = msg.time_increment
        out.scan_time = msg.scan_time

        # 거리 — 나눈다.
        out.range_min = P.sim_to_real(msg.range_min)
        out.range_max = P.sim_to_real(msg.range_max)
        # 유효하지 않은 값(-1, inf 등)은 그대로 통과시킨다.
        # 나눠버리면 "측정 실패" 표시가 그럴듯한 거리값으로 둔갑한다.
        out.ranges = [
            P.sim_to_real(r) if r >= 0 and r != float("inf") else r
            for r in msg.ranges
        ]

        out.intensities = msg.intensities  # 반사 강도는 거리 단위가 아니다
        self._scan_pub.publish(out)

    # ------------------------------------------------------------------
    # 오도메트리
    # ------------------------------------------------------------------
    def _on_odom(self, msg: Odometry):
        """위치와 선속도만 나눈다. 방향과 각속도는 그대로."""
        out = Odometry()
        out.header = msg.header
        out.child_frame_id = msg.child_frame_id

        # 위치 — 나눈다 (m).
        p = msg.pose.pose.position
        out.pose.pose.position.x = P.sim_to_real(p.x)
        out.pose.pose.position.y = P.sim_to_real(p.y)
        out.pose.pose.position.z = P.sim_to_real(p.z)

        # 방향 — 그대로. 쿼터니언은 회전이라 길이 단위가 없다.
        out.pose.pose.orientation = msg.pose.pose.orientation

        # 선속도 — 나눈다 (m/s).
        lv = msg.twist.twist.linear
        out.twist.twist.linear.x = P.sim_to_real(lv.x)
        out.twist.twist.linear.y = P.sim_to_real(lv.y)
        out.twist.twist.linear.z = P.sim_to_real(lv.z)

        # 각속도 — 그대로 (rad/s). 각도라 축척과 무관하다.
        out.twist.twist.angular = msg.twist.twist.angular

        # 공분산은 이번 범위에서 쓰지 않으므로 그대로 넘긴다.
        # (엄밀히는 위치 공분산이 SCALE^2 로 스케일되어야 한다)
        out.pose.covariance = msg.pose.covariance
        out.twist.covariance = msg.twist.covariance

        self._odom_pub.publish(out)


def main(args=None):
    rclpy.init(args=args)
    node = SensorScaler()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
