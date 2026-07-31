#!/usr/bin/env python3
"""/cmd_vel (속도 명령) 을 지게차 관절 지령으로 바꿔서 /joint_command 로 보낸다.

Nav2 같은 주행 스택은 "앞으로 1m/s, 왼쪽으로 0.3rad/s 돌아라" 라는 식의
Twist 메시지를 /cmd_vel 로 보낸다. 하지만 지게차가 실제로 받아야 하는 건
"조향 바퀴를 몇 도로 꺾고, 구동 바퀴를 몇 rad/s 로 돌려라" 이다.
그 변환을 이 노드가 담당한다.

실행:
    ros2 run forklift_control cmd_vel_to_joints
"""
import math

import rclpy
from geometry_msgs.msg import Twist
from rclpy.node import Node
from sensor_msgs.msg import JointState
from std_msgs.msg import Float64

from . import fast_params as P

# 이보다 느리면 정지로 간주한다. 0 으로 나누는 것을 막는 역할도 한다.
MIN_SPEED = 1e-3


def twist_to_steer_and_wheel(v: float, w: float):
    """전진속도 v(m/s), 회전속도 w(rad/s) -> (조향각 rad, 구동륜 각속도 rad/s).

    후륜 조향 자전거 모델. 기준점은 비조향축(앞쪽 롤러축) 중심이다.

    - 조향각이 클수록 같은 속도에서 더 많이 돈다:  w = -v * tan(delta) / L
      이를 delta 에 대해 풀면  delta = atan(-w * L / v)
    - 조향 바퀴는 비스듬히 향하므로 차체 속도보다 빨리 굴러야 한다:
      v_wheel = v / cos(delta)
    """
    if abs(v) < MIN_SPEED:
        # 후륜 조향 차량은 제자리 회전을 할 수 없다. 전진 없이 회전만 요청받으면
        # 물리적으로 불가능하므로 정지시킨다. (명세서 EPIC-3: 제자리 회전 불가)
        return 0.0, 0.0

    steer = math.atan(-w * P.WHEEL_BASE_M / v)

    # 실물 서보가 낼 수 있는 각도를 넘지 않게 자른다.
    limit = math.radians(P.STEER_LIMIT_DEG)
    steer = max(-limit, min(limit, steer))

    wheel_speed = v / math.cos(steer)
    wheel_omega = wheel_speed / P.DRIVE_WHEEL_RADIUS_M
    return steer, wheel_omega


class CmdVelToJoints(Node):
    def __init__(self):
        super().__init__("cmd_vel_to_joints")

        self._fork_target_sim_m = 0.0

        self._pub = self.create_publisher(JointState, P.TOPIC_JOINT_COMMAND, 10)
        self.create_subscription(Twist, P.TOPIC_CMD_VEL, self._on_cmd_vel, 10)
        self.create_subscription(Float64, P.TOPIC_FORK_CMD, self._on_fork_cmd, 10)

        self.get_logger().info(
            f"{P.TOPIC_CMD_VEL} -> {P.TOPIC_JOINT_COMMAND} 변환 시작 "
            f"(축거 {P.WHEEL_BASE_M}m, 조향한계 +-{P.STEER_LIMIT_DEG}도)"
        )

    def _on_fork_cmd(self, msg: Float64):
        """포크 목표 높이. 입력은 실물 기준 m 이므로 시뮬 축척으로 환산한다."""
        height = max(0.0, min(P.FORK_STROKE_REAL_M, msg.data))
        if height != msg.data:
            self.get_logger().warn(
                f"포크 목표 {msg.data:.3f}m 가 행정범위를 벗어나 {height:.3f}m 로 제한됨"
            )
        self._fork_target_sim_m = P.real_to_sim(height)

    def _on_cmd_vel(self, msg: Twist):
        steer, wheel_omega = twist_to_steer_and_wheel(msg.linear.x, msg.angular.z)

        if abs(msg.linear.x) < MIN_SPEED and abs(msg.angular.z) > MIN_SPEED:
            self.get_logger().warn(
                "전진 없이 회전만 요청됨 - 후륜 조향 차량은 제자리 회전 불가. 정지합니다.",
                throttle_duration_sec=2.0,
            )

        out = JointState()
        out.header.stamp = self.get_clock().now().to_msg()
        # 세 배열의 길이와 순서가 반드시 일치해야 한다.
        out.name = [P.JOINT_STEER, P.JOINT_DRIVE, P.JOINT_LIFT]
        #            조향은 각도로,  구동은 속도로,  포크는 위치로 제어된다.
        out.position = [steer, 0.0, self._fork_target_sim_m]
        out.velocity = [0.0, wheel_omega, 0.0]
        self._pub.publish(out)


def main(args=None):
    rclpy.init(args=args)
    node = CmdVelToJoints()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
