from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch_ros.actions import Node
import os


def generate_launch_description():
    package_share = get_package_share_directory("forklift_teleop")
    parameters = os.path.join(package_share, "config", "teleop.yaml")
    return LaunchDescription(
        [
            Node(
                package="forklift_teleop",
                executable="uart_teleop_bridge",
                name="uart_teleop_bridge",
                output="screen",
                parameters=[parameters],
            )
        ]
    )
