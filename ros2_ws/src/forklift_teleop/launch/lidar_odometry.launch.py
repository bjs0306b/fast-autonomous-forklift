import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import LifecycleNode, Node


# Vehicle coordinate convention:
#   base_link: center of the front drive axle
#   +X: fork direction, +Y: left, +Z: up
# Measured base_link -> laser_frame translation in meters.
LIDAR_TRANSLATION = ("-0.025", "0.0", "0.202")
# The X4 is mounted turned a quarter turn, so its zero angle points along
# base_link +Y instead of +X. Found in RViz: a wall behind the vehicle drew
# itself on the left. Only yaw is affected -- the unit is still level.
#
# This is not cosmetic. rf2o measures displacement in the laser frame, so
# without the yaw the odometry has the vehicle sliding sideways whenever it
# drives forward, and every pose the EKF builds on that is wrong too.
LIDAR_ROTATION = ("0.0", "0.0", "1.5708")  # roll, pitch, yaw


def generate_launch_description():
    ydlidar_share = get_package_share_directory("ydlidar_ros2_driver")
    parameter_file = LaunchConfiguration("params_file")

    params_argument = DeclareLaunchArgument(
        "params_file",
        default_value=os.path.join(
            ydlidar_share,
            "params",
            "ydlidar.yaml",
        ),
        description="YDLidar ROS2 parameter file",
    )

    driver_node = LifecycleNode(
        package="ydlidar_ros2_driver",
        executable="ydlidar_ros2_driver_node",
        name="ydlidar_ros2_driver_node",
        namespace="/",
        output="screen",
        emulate_tty=True,
        parameters=[parameter_file],
    )

    x, y, z = LIDAR_TRANSLATION
    roll, pitch, yaw = LIDAR_ROTATION
    lidar_tf = Node(
        package="tf2_ros",
        executable="static_transform_publisher",
        name="base_link_to_laser_frame",
        output="screen",
        arguments=[
            "--x", x,
            "--y", y,
            "--z", z,
            "--roll", roll,
            "--pitch", pitch,
            "--yaw", yaw,
            "--frame-id", "base_link",
            "--child-frame-id", "laser_frame",
        ],
    )

    # Each TF link needs exactly one publisher. rf2o hands odom -> base_link
    # over to the EKF below, so the two are launched together and must stay
    # together: rf2o alone with publish_tf false would leave the chain broken.
    rf2o_node = Node(
        package="rf2o_laser_odometry",
        executable="rf2o_laser_odometry_node",
        name="rf2o_laser_odometry",
        output="screen",
        parameters=[
            {
                "laser_scan_topic": "/scan",
                "odom_topic": "/odom_rf2o",
                "publish_tf": False,
                "base_frame_id": "base_link",
                "odom_frame_id": "odom",
                "init_pose_from_topic": "",
                "freq": 10.0,
            }
        ],
    )

    ekf_node = Node(
        package="robot_localization",
        executable="ekf_node",
        name="ekf_filter_node",
        output="screen",
        parameters=[
            os.path.join(
                get_package_share_directory("forklift_teleop"),
                "config",
                "ekf.yaml",
            )
        ],
    )

    return LaunchDescription(
        [
            params_argument,
            driver_node,
            lidar_tf,
            rf2o_node,
            ekf_node,
        ]
    )
