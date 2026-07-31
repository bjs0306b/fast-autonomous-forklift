from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch_ros.actions import Node
import os


# Vehicle coordinate convention, matching lidar_odometry.launch.py:
#   base_link: center of the front drive axle
#   +X: fork direction, +Y: left, +Z: up
#
# PLACEHOLDER: replace each of these with the measured offset before trusting
# any downstream result. robot_localization and the costmap transform sensor
# data through these, so a wrong value fails silently.
SENSOR_TRANSFORMS = (
    # (name, parent, child, x, y, z, roll, pitch, yaw)
    ("base_link_to_imu_link", "base_link", "imu_link",
     "0.0", "0.0", "0.05", "0.0", "0.0", "0.0"),
    ("base_link_to_tof_left_link", "base_link", "tof_left_link",
     "0.10", "0.06", "0.05", "0.0", "0.0", "0.0"),
    ("base_link_to_tof_right_link", "base_link", "tof_right_link",
     "0.10", "-0.06", "0.05", "0.0", "0.0", "0.0"),
)


def generate_launch_description():
    package_share = get_package_share_directory("forklift_teleop")
    parameters = os.path.join(package_share, "config", "sensors.yaml")

    actions = [
        Node(
            package="forklift_teleop",
            executable="sensor_bridge",
            name="sensor_bridge",
            output="screen",
            parameters=[parameters],
        )
    ]

    for name, parent, child, x, y, z, roll, pitch, yaw in SENSOR_TRANSFORMS:
        actions.append(
            Node(
                package="tf2_ros",
                executable="static_transform_publisher",
                name=name,
                output="screen",
                arguments=[
                    "--x", x,
                    "--y", y,
                    "--z", z,
                    "--roll", roll,
                    "--pitch", pitch,
                    "--yaw", yaw,
                    "--frame-id", parent,
                    "--child-frame-id", child,
                ],
            )
        )

    return LaunchDescription(actions)
