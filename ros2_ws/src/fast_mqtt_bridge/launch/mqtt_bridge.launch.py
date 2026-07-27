from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node
from launch_ros.parameter_descriptions import ParameterValue
import os


def generate_launch_description():
    config = os.path.join(
        get_package_share_directory("fast_mqtt_bridge"),
        "config",
        "mqtt_bridge.yaml",
    )
    arguments = {
        "vehicle_id": "REAL-F01",
        "mqtt_host": "localhost",
        "mqtt_port": "1883",
        "mqtt_username": "",
        "mqtt_password_env": "MQTT_PASSWORD",
        "mqtt_qos": "1",
        "mqtt_tls_enabled": "false",
        "mqtt_ca_cert": "",
        "ros_namespace": "",
        "status_topic": "",
        "location_topic": "",
        "path_topic": "",
        "command_topic": "",
        "command_result_topic": "",
        "location_publish_interval_ms": "100",
        "heartbeat_interval_ms": "1000",
    }
    declarations = [
        DeclareLaunchArgument(name, default_value=value)
        for name, value in arguments.items()
    ]
    node = Node(
        package="fast_mqtt_bridge",
        executable="mqtt_bridge",
        name="fast_mqtt_bridge",
        namespace=LaunchConfiguration("ros_namespace"),
        output="screen",
        parameters=[
            config,
            {
                name: ParameterValue(
                    LaunchConfiguration(name),
                    value_type=int if name in {
                        "mqtt_port",
                        "mqtt_qos",
                        "location_publish_interval_ms",
                        "heartbeat_interval_ms",
                    } else bool if name == "mqtt_tls_enabled" else str,
                )
                for name in arguments
            },
        ],
    )
    return LaunchDescription([*declarations, node])
