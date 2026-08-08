"""Arbitrate who is driving, then route that through the guard to UART."""

import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch_ros.actions import Node


def generate_launch_description():
    package_share = get_package_share_directory("forklift_teleop")
    avoidance_parameters = os.path.join(
        package_share, "config", "obstacle_avoidance.yaml"
    )
    teleop_parameters = os.path.join(
        package_share, "config", "teleop.yaml"
    )
    return LaunchDescription([
        # ⚠️ 가드 **앞**에 중재가 온다. nav2 와 포크 정렬 노드가 각자 Twist 를
        #    내는데, 둘 다 /cmd_vel 에 쓰면 마지막에 온 것이 이긴다 -- 에러도
        #    경고도 없고 차만 이상하게 움직인다. 여기서 하나만 통과시킨다.
        #
        #    nav2 는 건드리지 않는다. 가드의 input_cmd_vel_topic 만 중재 결과를
        #    보게 바꾸면 되고, 그 아래(가드·브리지·펌웨어 워치독)는 그대로다.
        Node(
            package="forklift_teleop",
            executable="drive_mux",
            name="drive_mux",
            output="screen",
        ),
        Node(
            package="forklift_teleop",
            executable="obstacle_avoidance",
            name="obstacle_avoidance",
            output="screen",
            parameters=[
                avoidance_parameters,
                {"input_cmd_vel_topic": "/cmd_vel_arbitrated"},
            ],
        ),
        Node(
            package="forklift_teleop",
            executable="uart_teleop_bridge",
            name="uart_teleop_bridge",
            output="screen",
            parameters=[
                teleop_parameters,
                {"cmd_vel_topic": "/cmd_vel_safe"},
            ],
        ),
    ])
