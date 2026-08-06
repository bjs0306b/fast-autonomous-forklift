"""Route nominal velocity through the obstacle guard before UART output."""

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
        Node(
            package="forklift_teleop",
            executable="obstacle_avoidance",
            name="obstacle_avoidance",
            output="screen",
            parameters=[avoidance_parameters],
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
