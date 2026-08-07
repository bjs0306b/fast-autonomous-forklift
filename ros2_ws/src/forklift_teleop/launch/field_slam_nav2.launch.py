"""Bring up LiDAR, front ToFs, SLAM, Nav2, avoidance and 10 Hz telemetry.

Driving is intentionally opt-in.  Start once with ``drive_enabled:=false``
and verify all sensor/TF topics before allowing the UART bridge to move the
vehicle.
"""

import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import (
    DeclareLaunchArgument,
    IncludeLaunchDescription,
    SetEnvironmentVariable,
)
from launch.conditions import IfCondition
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node
from launch_ros.parameter_descriptions import ParameterValue
from nav2_common.launch import RewrittenYaml


def include(package: str, launch_file: str, **kwargs):
    return IncludeLaunchDescription(
        PythonLaunchDescriptionSource(os.path.join(
            get_package_share_directory(package), "launch", launch_file
        )),
        launch_arguments=kwargs.items(),
    )


def generate_launch_description() -> LaunchDescription:
    teleop_share = get_package_share_directory("forklift_teleop")
    mqtt_share = get_package_share_directory("fast_mqtt_bridge")
    nav2_share = get_package_share_directory("nav2_bringup")

    nav2_params = LaunchConfiguration("nav2_params_file")
    configured_nav2 = RewrittenYaml(
        source_file=nav2_params,
        root_key="",
        param_rewrites={
            "default_nav_to_pose_bt_xml": os.path.join(
                teleop_share, "behavior_trees",
                "navigate_to_pose_replanning.xml"
            )
        },
        convert_types=True,
    )

    arguments = [
        # Shared memory off for everything this launch starts. A killed node
        # leaves its /dev/shm port lock without the segment, and every later
        # participant that lands on that port loses it silently -- on
        # 2026-08-07 that swallowed an action goal response while the vehicle
        # drove off, and made a live obstacle guard look dead. See
        # config/fastdds_no_shm.xml.
        SetEnvironmentVariable(
            "FASTRTPS_DEFAULT_PROFILES_FILE",
            os.path.join(teleop_share, "config", "fastdds_no_shm.xml"),
        ),
        DeclareLaunchArgument(
            "nav2_params_file",
            description="Absolute path to the field-tuned nav2_params.yaml",
        ),
        DeclareLaunchArgument(
            "slam_params_file",
            default_value=os.path.join(
                teleop_share, "config", "mapper_params_field.yaml"
            ),
        ),
        DeclareLaunchArgument("drive_enabled", default_value="false"),
        DeclareLaunchArgument("auto_lap_enabled", default_value="false"),
        DeclareLaunchArgument(
            "lap_params_file",
            default_value=os.path.join(
                teleop_share, "config", "field_lap.yaml"
            ),
        ),
        DeclareLaunchArgument("mqtt_enabled", default_value="false"),
        DeclareLaunchArgument("origin_configured", default_value="false"),
        DeclareLaunchArgument("origin_x_m", default_value="0.0"),
        DeclareLaunchArgument("origin_y_m", default_value="0.0"),
        DeclareLaunchArgument("origin_yaw_rad", default_value="0.0"),
        DeclareLaunchArgument("mqtt_username", default_value=""),
        DeclareLaunchArgument("mqtt_ca_cert", default_value=""),
        DeclareLaunchArgument("mqtt_tls_insecure", default_value="true"),
        DeclareLaunchArgument(
            "trajectory_csv", default_value="/tmp/fk01_slam_route.csv"
        ),
    ]

    sensors = include("forklift_teleop", "sensor_usb.launch.py")
    lidar_odom = include("forklift_teleop", "lidar_odometry.launch.py")
    slam = include(
        "slam_toolbox", "online_async_launch.py",
        slam_params_file=LaunchConfiguration("slam_params_file"),
        use_sim_time="false",
    )
    nav2 = IncludeLaunchDescription(
        PythonLaunchDescriptionSource(
            os.path.join(nav2_share, "launch", "navigation_launch.py")
        ),
        launch_arguments={
            "params_file": configured_nav2,
            "use_sim_time": "false",
            "autostart": "true",
        }.items(),
    )
    guarded_drive = IncludeLaunchDescription(
        PythonLaunchDescriptionSource(
            os.path.join(teleop_share, "launch", "obstacle_avoidance.launch.py")
        ),
        condition=IfCondition(LaunchConfiguration("drive_enabled")),
    )
    telemetry = Node(
        package="fast_mqtt_bridge",
        executable="orin_telemetry",
        name="orin_telemetry",
        output="screen",
        parameters=[
            os.path.join(mqtt_share, "config", "orin_telemetry.yaml"),
            {
                "mqtt_enabled": ParameterValue(
                    LaunchConfiguration("mqtt_enabled"), value_type=bool
                ),
                "origin_configured": ParameterValue(
                    LaunchConfiguration("origin_configured"), value_type=bool
                ),
                "origin_x_m": ParameterValue(
                    LaunchConfiguration("origin_x_m"), value_type=float
                ),
                "origin_y_m": ParameterValue(
                    LaunchConfiguration("origin_y_m"), value_type=float
                ),
                "origin_yaw_rad": ParameterValue(
                    LaunchConfiguration("origin_yaw_rad"), value_type=float
                ),
                "mqtt_username": LaunchConfiguration("mqtt_username"),
                "mqtt_ca_cert": LaunchConfiguration("mqtt_ca_cert"),
                "mqtt_tls_insecure": ParameterValue(
                    LaunchConfiguration("mqtt_tls_insecure"), value_type=bool
                ),
                "trajectory_csv": LaunchConfiguration("trajectory_csv"),
            },
        ],
    )
    lap_mission = Node(
        package="forklift_teleop",
        executable="field_lap_mission",
        name="field_lap_mission",
        output="screen",
        condition=IfCondition(LaunchConfiguration("auto_lap_enabled")),
        parameters=[
            LaunchConfiguration("lap_params_file"),
            {
                "mission_armed": True,
                "drive_enabled": ParameterValue(
                    LaunchConfiguration("drive_enabled"), value_type=bool
                ),
                "origin_configured": ParameterValue(
                    LaunchConfiguration("origin_configured"), value_type=bool
                ),
                "origin_x_m": ParameterValue(
                    LaunchConfiguration("origin_x_m"), value_type=float
                ),
                "origin_y_m": ParameterValue(
                    LaunchConfiguration("origin_y_m"), value_type=float
                ),
                "origin_yaw_rad": ParameterValue(
                    LaunchConfiguration("origin_yaw_rad"), value_type=float
                ),
            },
        ],
    )

    return LaunchDescription([
        *arguments, sensors, lidar_odom, slam, nav2, guarded_drive, telemetry,
        lap_mission,
    ])
