"""ROS2 node that bridges configured vehicle adapters to MQTT."""

import json
from queue import Empty, PriorityQueue, Queue
from typing import Any, Optional

import paho.mqtt.client as mqtt
import rclpy
from rclpy.node import Node

from fast_mqtt_bridge.bridge_core import BridgeCore
from fast_mqtt_bridge.command_handler import UnavailableCommandAdapter
from fast_mqtt_bridge.config import BridgeConfig
from fast_mqtt_bridge.dto import (
    CommandMessage,
    LocationMessage,
    PathMessage,
    StatusMessage,
    quaternion_to_heading,
)
from fast_mqtt_bridge.mqtt_policy import (
    configure_tls,
    publish_vehicle_message,
    subscribe_vehicle_command,
)


class MqttBridgeNode(Node):
    PARAMETER_DEFAULTS = {
        "vehicle_id": "REAL-F01",
        "mqtt_host": "localhost",
        "mqtt_port": 1883,
        "mqtt_username": "",
        "mqtt_password": "",
        "mqtt_password_env": "MQTT_PASSWORD",
        "mqtt_client_id": "fast-mqtt-bridge",
        "mqtt_keepalive": 60,
        "mqtt_qos": 1,
        "mqtt_tls_enabled": False,
        "mqtt_ca_cert": "",
        "ros_namespace": "",
        "status_topic": "",
        "location_topic": "",
        "path_topic": "",
        "command_topic": "",
        "command_result_topic": "",
        "location_publish_interval_ms": 100,
        "heartbeat_interval_ms": 1000,
        "command_cache_ttl_sec": 3600.0,
        "command_cache_max_entries": 1000,
        "path_max_points": 1000,
    }

    def __init__(self, mqtt_client: Optional[mqtt.Client] = None) -> None:
        super().__init__("fast_mqtt_bridge")
        for name, default in self.PARAMETER_DEFAULTS.items():
            self.declare_parameter(name, default)

        self._config = BridgeConfig.from_parameters(self._parameter_value)
        self._config.validate()
        vehicle_id = self._config.vehicle_id
        self._topics = {
            "status": f"forklift/{vehicle_id}/status",
            "location": f"forklift/{vehicle_id}/location",
            "path": f"forklift/{vehicle_id}/path",
            "command": self._parameter_value(
                "command_topic", f"forklift/{vehicle_id}/command"
            ) or f"forklift/{vehicle_id}/command",
            "command_result": self._parameter_value(
                "command_result_topic",
                f"forklift/{vehicle_id}/command-result",
            ) or f"forklift/{vehicle_id}/command-result",
        }
        self._incoming: PriorityQueue[tuple[int, int, CommandMessage]] = PriorityQueue()
        self._publish_retry: Queue[tuple[str, str]] = Queue()
        self._sequence = 0
        self._connected = False
        self._mqtt = mqtt_client or self._new_mqtt_client()
        self._configure_mqtt()
        self._core = BridgeCore(
            vehicle_id,
            UnavailableCommandAdapter(),
            self._publish,
            self._config.location_publish_interval_ms,
            self._config.command_cache_ttl_sec,
            self._config.command_cache_max_entries,
            topic_overrides={"command_result": self._topics["command_result"]},
        )
        self._command_timer = self.create_timer(0.02, self._drain_commands)
        self._retry_timer = self.create_timer(1.0, self._drain_publish_retries)
        self._heartbeat_timer = self.create_timer(
            self._config.heartbeat_interval_ms / 1000.0,
            self._publish_heartbeat,
        )
        self._configure_optional_ros_adapters()
        self._start_mqtt()

    def _parameter_value(self, name: str, default: Any) -> Any:
        value = self.get_parameter(name).value
        return default if value is None else value

    def _new_mqtt_client(self) -> mqtt.Client:
        try:
            return mqtt.Client(
                callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
                client_id=self._config.mqtt_client_id,
                clean_session=True,
            )
        except (AttributeError, TypeError):
            return mqtt.Client(client_id=self._config.mqtt_client_id, clean_session=True)

    def _configure_mqtt(self) -> None:
        if self._config.mqtt_username:
            self._mqtt.username_pw_set(
                self._config.mqtt_username,
                self._config.mqtt_password or None,
            )
        configure_tls(
            self._mqtt,
            self._config.mqtt_tls_enabled,
            self._config.mqtt_ca_cert,
        )
        self._mqtt.reconnect_delay_set(min_delay=1, max_delay=60)
        self._mqtt.on_connect = self._on_connect
        self._mqtt.on_disconnect = self._on_disconnect
        self._mqtt.on_message = self._on_message

    def _start_mqtt(self) -> None:
        try:
            self._mqtt.connect_async(
                self._config.mqtt_host,
                self._config.mqtt_port,
                self._config.mqtt_keepalive,
            )
            self._mqtt.loop_start()
        except Exception as error:
            self.get_logger().error(f"[MQTT] initial connection failed: {error}")

    def _on_connect(self, client, userdata, flags, reason_code, properties=None) -> None:
        code = int(getattr(reason_code, "value", reason_code))
        if code != 0:
            self._connected = False
            self.get_logger().error(f"[MQTT] connection failed reason={reason_code}")
            return
        self._connected = True
        topic = self._topics["command"]
        subscribe_vehicle_command(client, topic)
        self.get_logger().info(
            f"[MQTT] connected broker={self._config.mqtt_host}:{self._config.mqtt_port} "
            f"tls={'enabled' if self._config.mqtt_tls_enabled else 'disabled'}"
        )
        self.get_logger().info(f"[MQTT] subscribed topic={topic} qos=1")

    def _on_disconnect(self, client, userdata, disconnect_flags, reason_code=None, properties=None) -> None:
        self._connected = False
        code = reason_code if reason_code is not None else disconnect_flags
        self.get_logger().warning(f"[MQTT] disconnected reason={code}; reconnect scheduled")

    def _on_message(self, client, userdata, message) -> None:
        try:
            command = CommandMessage.from_json(message.payload)
            self._sequence += 1
            priority = 0 if command.command == "EMERGENCY_STOP" else 10
            self._incoming.put((priority, self._sequence, command))
            self.get_logger().info(
                f"[COMMAND] received commandId={command.command_id or '<missing>'} "
                f"command={command.command or '<missing>'}"
            )
        except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as error:
            self.get_logger().warning(f"[COMMAND] invalid JSON/message discarded: {error}")

    def _drain_commands(self) -> None:
        for _ in range(50):
            try:
                _, _, command = self._incoming.get_nowait()
            except Empty:
                return
            if command.command == "EMERGENCY_STOP":
                retained = []
                while True:
                    try:
                        queued = self._incoming.get_nowait()
                    except Empty:
                        break
                    if queued[2].command == "MOVE":
                        self._core.cancel_pending_move(queued[2])
                    else:
                        retained.append(queued)
                for queued in retained:
                    self._incoming.put(queued)
            accepted = self._core.process_command(command)
            if not accepted:
                self.get_logger().warning(
                    f"[COMMAND] rejected/duplicate commandId={command.command_id or '<missing>'}"
                )

    def _publish(self, topic: str, payload: str, retry: bool = True) -> bool:
        try:
            info = publish_vehicle_message(self._mqtt, topic, payload)
            if getattr(info, "rc", mqtt.MQTT_ERR_SUCCESS) != mqtt.MQTT_ERR_SUCCESS:
                raise RuntimeError(f"publish rc={info.rc}")
            return True
        except Exception as error:
            self.get_logger().error(f"[MQTT] publish failed topic={topic}: {error}")
            if retry:
                self._publish_retry.put((topic, payload))
            return False

    def _drain_publish_retries(self) -> None:
        if not self._connected:
            return
        for _ in range(50):
            try:
                topic, payload = self._publish_retry.get_nowait()
            except Empty:
                return
            if not self._publish(topic, payload, retry=False):
                self._publish_retry.put((topic, payload))
                return

    def publish_status(self, status: StatusMessage) -> None:
        self._core.publish_status(status)

    def _publish_heartbeat(self) -> None:
        self._core.publish_heartbeat()

    def publish_location(self, location: LocationMessage) -> bool:
        return self._core.publish_location(location)

    def publish_path(self, path: PathMessage) -> bool:
        return self._core.publish_path(path)

    def _configure_optional_ros_adapters(self) -> None:
        """Only bind standard types when the team explicitly configures source topics."""
        location_topic = str(self._parameter_value("location_topic", ""))
        path_topic = str(self._parameter_value("path_topic", ""))
        status_topic = str(self._parameter_value("status_topic", ""))
        if status_topic:
            self.get_logger().warning(
                "[ROS2] status_topic configured but source message type is unconfirmed; "
                "adapter not attached"
            )
        if location_topic:
            from nav_msgs.msg import Odometry
            self.create_subscription(Odometry, location_topic, self._on_odometry, 10)
        if path_topic:
            from nav_msgs.msg import Path
            self.create_subscription(Path, path_topic, self._on_path, 10)

    def _on_odometry(self, message) -> None:
        pose = message.pose.pose
        q = pose.orientation
        try:
            location = LocationMessage.create(
                self._config.vehicle_id,
                pose.position.x,
                pose.position.y,
                quaternion_to_heading(q.x, q.y, q.z, q.w),
                message.header.frame_id or "map",
            )
            self.publish_location(location)
        except ValueError as error:
            self.get_logger().warning(f"[ROS2] invalid location discarded: {error}")

    def _on_path(self, message) -> None:
        try:
            if not message.poses:
                self.get_logger().info(
                    "[ROS2] empty path suppressed because backend requires goal"
                )
                return
            max_points = int(self._parameter_value("path_max_points", 1000))
            points = []
            for stamped in message.poses:
                pose = stamped.pose
                q = pose.orientation
                points.append(
                    (
                        pose.position.x,
                        pose.position.y,
                        quaternion_to_heading(q.x, q.y, q.z, q.w),
                    )
                )
            path = PathMessage.create(
                self._config.vehicle_id,
                message.header.frame_id or "map",
                points,
                max_points=max_points,
            )
            self.publish_path(path)
        except ValueError as error:
            self.get_logger().warning(f"[ROS2] invalid path discarded: {error}")

    def destroy_node(self) -> bool:
        try:
            self._mqtt.disconnect()
            self._mqtt.loop_stop()
        except Exception:
            pass
        return super().destroy_node()


def main(args=None) -> None:
    rclpy.init(args=args)
    node = MqttBridgeNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
