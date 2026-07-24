"""Configuration helpers with environment-over-parameter precedence."""

from dataclasses import dataclass
import os
from typing import Any, Callable


def _environment(name: str, default: Any, cast: Callable[[str], Any] = str) -> Any:
    value = os.getenv(name)
    if value is None or value == "":
        return default
    return cast(value)


@dataclass(frozen=True)
class BridgeConfig:
    mqtt_host: str = "localhost"
    mqtt_port: int = 1883
    mqtt_username: str = ""
    mqtt_password: str = ""
    mqtt_client_id: str = "fast-mqtt-bridge"
    mqtt_keepalive: int = 60
    mqtt_qos: int = 1
    mqtt_retained: bool = False
    vehicle_id: str = "REAL-F01"
    ros_namespace: str = ""
    heartbeat_interval_ms: int = 1000
    location_publish_interval_ms: int = 100
    command_cache_ttl_sec: float = 3600.0
    command_cache_max_entries: int = 1000

    @classmethod
    def from_parameters(cls, get_parameter: Callable[[str, Any], Any]) -> "BridgeConfig":
        """Resolve environment > ROS parameter > safe default."""
        defaults = cls()
        password_env = str(get_parameter("mqtt_password_env", "MQTT_PASSWORD"))
        return cls(
            mqtt_host=_environment(
                "MQTT_BROKER_HOST",
                get_parameter("mqtt_host", defaults.mqtt_host),
            ),
            mqtt_port=_environment(
                "MQTT_BROKER_PORT",
                get_parameter("mqtt_port", defaults.mqtt_port),
                int,
            ),
            mqtt_username=_environment(
                "MQTT_USERNAME",
                get_parameter("mqtt_username", defaults.mqtt_username),
            ),
            mqtt_password=_environment(
                password_env,
                get_parameter("mqtt_password", defaults.mqtt_password),
            ),
            mqtt_client_id=_environment(
                "MQTT_CLIENT_ID",
                get_parameter("mqtt_client_id", defaults.mqtt_client_id),
            ),
            mqtt_keepalive=_environment(
                "MQTT_KEEPALIVE",
                get_parameter("mqtt_keepalive", defaults.mqtt_keepalive),
                int,
            ),
            mqtt_qos=int(get_parameter("mqtt_qos", defaults.mqtt_qos)),
            mqtt_retained=False,
            vehicle_id=_environment(
                "VEHICLE_ID",
                get_parameter("vehicle_id", defaults.vehicle_id),
            ),
            ros_namespace=_environment(
                "ROS_NAMESPACE",
                get_parameter("ros_namespace", defaults.ros_namespace),
            ),
            heartbeat_interval_ms=int(
                get_parameter(
                    "heartbeat_interval_ms",
                    defaults.heartbeat_interval_ms,
                )
            ),
            location_publish_interval_ms=int(
                get_parameter(
                    "location_publish_interval_ms",
                    defaults.location_publish_interval_ms,
                )
            ),
            command_cache_ttl_sec=float(
                get_parameter(
                    "command_cache_ttl_sec",
                    defaults.command_cache_ttl_sec,
                )
            ),
            command_cache_max_entries=int(
                get_parameter(
                    "command_cache_max_entries",
                    defaults.command_cache_max_entries,
                )
            ),
        )

    def validate(self) -> None:
        if not self.vehicle_id.strip():
            raise ValueError("vehicle_id must not be blank")
        if not 1 <= self.mqtt_port <= 65535:
            raise ValueError("mqtt_port must be between 1 and 65535")
        if self.mqtt_qos != 1:
            raise ValueError("all vehicle MQTT topics require QoS 1")
        if self.mqtt_retained:
            raise ValueError("vehicle MQTT publishes must not be retained")
        if self.mqtt_keepalive <= 0:
            raise ValueError("mqtt_keepalive must be positive")
        if self.heartbeat_interval_ms <= 0:
            raise ValueError("heartbeat_interval_ms must be positive")
        if self.location_publish_interval_ms < 100:
            raise ValueError("location_publish_interval_ms must be at least 100")
        if self.command_cache_ttl_sec <= 0 or self.command_cache_max_entries <= 0:
            raise ValueError("command cache limits must be positive")

