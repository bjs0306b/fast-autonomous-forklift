from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch_ros.actions import Node
import os


# PLACEHOLDER: replace with the measured offset from base_link to the IMU
# before trusting any downstream result. robot_localization transforms IMU data
# through this transform, so a wrong value fails silently.
IMU_TRANSLATION = ("0.0", "0.0", "0.05")
IMU_ROTATION = ("0.0", "0.0", "0.0")  # roll, pitch, yaw


def generate_launch_description():
    package_share = get_package_share_directory("forklift_teleop")
    parameters = os.path.join(package_share, "config", "sensors.yaml")
    x, y, z = IMU_TRANSLATION
    roll, pitch, yaw = IMU_ROTATION

    return LaunchDescription(
        [
            Node(
                package="forklift_teleop",
                executable="imu_bridge",
                name="imu_bridge",
                output="screen",
                parameters=[parameters],
            ),
            Node(
                package="tf2_ros",
                executable="static_transform_publisher",
                name="base_link_to_imu_link",
                output="screen",
                arguments=[
                    "--x", x,
                    "--y", y,
                    "--z", z,
                    "--roll", roll,
                    "--pitch", pitch,
                    "--yaw", yaw,
                    "--frame-id", "base_link",
                    "--child-frame-id", "imu_link",
                ],
            ),
        ]
    )
