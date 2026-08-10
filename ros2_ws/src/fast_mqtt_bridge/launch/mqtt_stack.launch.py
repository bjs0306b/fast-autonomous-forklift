"""MQTT 세 노드를 한 번에 올린다 — 브리지·텔레메트리·시뮬 수신기.

따로 띄우면 매번 셋을 손으로 올려야 하고, 그때마다 접속 값을 다시 적어야 해서
하나만 빠뜨려도 조용히 반쪽만 돈다. 실제로 그렇게 당했다: 브리지만 떠 있고
텔레메트리는 `mqtt_enabled=false` 로 떠서 관제에 아무것도 안 갔다.

⚠️ **비밀번호는 환경변수로만 받는다.** 저장소에 넣지 않는다. 기본 이름은
   `MQTT_PASSWORD` 이고, 없으면 노드가 인증 없이 붙으려다 거절당한다.

⚠️ **호스트는 IP 로 넣어야 한다.** 현장 인증서가 호스트명이 아니라 IP 로
   발급돼 있어(CN = 3.38.178.143, SAN 도 IP 하나뿐), 도메인으로 붙으면
   TLS 검증이 `Hostname mismatch` 로 실패한다(2026-08-10 실측).

⚠️ **텔레메트리는 원점 설정이 필요하다.** `origin_configured` 가 거짓이면
   SLAM 로컬 좌표만 기록하고 **MQTT 발행 자체를 끈다.** 정적 지도를 쓰면
   map 이 곧 창고 좌표계라 원점은 0 으로 두고 켜기만 하면 된다.
"""

import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node
from launch_ros.parameter_descriptions import ParameterValue


def generate_launch_description():
    share = get_package_share_directory("fast_mqtt_bridge")
    telemetry_config = os.path.join(share, "config", "orin_telemetry.yaml")
    bridge_config = os.path.join(share, "config", "mqtt_bridge.yaml")

    # 기본값은 환경변수에서 읽는다 -- 셸에서 한 번 export 해두면 런치 인자를
    # 매번 적을 필요가 없고, 비밀번호는 애초에 인자로 지나가지 않는다.
    host = os.environ.get("MQTT_BROKER_HOST", "3.38.178.143")
    port = os.environ.get("MQTT_BROKER_PORT", "8883")
    username = os.environ.get("MQTT_USERNAME", "fast-backend")
    ca_cert = os.environ.get(
        "MQTT_CA_CERT", "/home/orin/S15P11A304/infra/mqtt-ca.crt")

    arguments = {
        "mqtt_host": host,
        "mqtt_port": port,
        "mqtt_username": username,
        "mqtt_ca_cert": ca_cert,
        "vehicle_id": os.environ.get("VEHICLE_ID", "REAL-F01"),
    }
    declarations = [
        DeclareLaunchArgument(name, default_value=value)
        for name, value in arguments.items()
    ]

    def text(name):
        return ParameterValue(LaunchConfiguration(name), value_type=str)

    def number(name):
        return ParameterValue(LaunchConfiguration(name), value_type=int)

    bridge = Node(
        package="fast_mqtt_bridge",
        executable="mqtt_bridge",
        name="fast_mqtt_bridge",
        output="screen",
        parameters=[
            bridge_config,
            {
                "mqtt_host": text("mqtt_host"),
                "mqtt_port": number("mqtt_port"),
                "mqtt_username": text("mqtt_username"),
                "mqtt_ca_cert": text("mqtt_ca_cert"),
                "mqtt_tls_enabled": True,
                "vehicle_id": text("vehicle_id"),
            },
        ],
    )
    telemetry = Node(
        package="fast_mqtt_bridge",
        executable="orin_telemetry",
        name="orin_telemetry",
        output="screen",
        parameters=[
            telemetry_config,
            {
                "mqtt_enabled": True,
                "mqtt_host": text("mqtt_host"),
                "mqtt_port": number("mqtt_port"),
                "mqtt_username": text("mqtt_username"),
                "mqtt_ca_cert": text("mqtt_ca_cert"),
                # 정적 지도에서는 map 이 곧 창고 좌표계다.
                "origin_configured": True,
            },
        ],
    )
    receiver = Node(
        package="fast_mqtt_bridge",
        executable="sim_task_receiver",
        name="sim_task_receiver",
        output="screen",
        parameters=[{
            "mqtt_enabled": True,
            "mqtt_host": text("mqtt_host"),
            "mqtt_port": number("mqtt_port"),
            "mqtt_username": text("mqtt_username"),
            "mqtt_ca_cert": text("mqtt_ca_cert"),
        }],
    )
    return LaunchDescription([*declarations, bridge, telemetry, receiver])
