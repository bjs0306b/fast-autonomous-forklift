"""10 Hz Orin pose telemetry defined by ``orin-pose-spec.md``.

This is deliberately separate from the legacy backend bridge.  The two
interfaces use different topic names, payloads and QoS policies and must not
silently share configuration.
"""

import csv
import json
import math
import os
import time
from pathlib import Path
import paho.mqtt.client as mqtt
import rclpy
from nav_msgs.msg import Odometry
from rclpy.duration import Duration
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from rclpy.time import Time
from std_msgs.msg import String
from tf2_ros import Buffer, TransformException, TransformListener

from fast_mqtt_bridge.orin_telemetry_model import (
    SCALE,
    VALID_STATES,
    align_slam_pose,
    telemetry_payload,
    yaw_from_quaternion,
)


class OrinTelemetryNode(Node):
    """Publish aligned TF pose and record one SLAM lap as CSV."""

    def __init__(self, mqtt_client=None) -> None:
        super().__init__("orin_telemetry")
        defaults = {
            "vehicle_id": "fk01",
            "map_frame": "map",
            "base_frame": "base_link",
            "odom_topic": "/odometry/filtered",
            "status_topic": "/vehicle/telemetry_state",
            "publish_rate_hz": 10.0,
            "pose_timeout_sec": 2.0,
            "velocity_timeout_sec": 0.5,
            "origin_configured": False,
            "origin_x_m": 0.0,
            "origin_y_m": 0.0,
            "origin_yaw_rad": 0.0,
            "mqtt_enabled": False,
            "mqtt_host": "i15a304.p.ssafy.io",
            "mqtt_port": 8883,
            "mqtt_username": "",
            "mqtt_password_env": "MQTT_PASSWORD",
            "mqtt_client_id": "fk01-orin-telemetry",
            "mqtt_keepalive": 30,
            "mqtt_ca_cert": "",
            "mqtt_tls_insecure": False,
            "mqtt_topic": "fast/v1/vehicle/fk01/telemetry",
            "fork_height_m": 0.0,
            "loaded": False,
            "cargo_id": "",
            "task_id": "",
            "battery": 100.0,
            "state_override": "",
            "moving_threshold_mps": 0.01,
            "trajectory_csv": "/tmp/fk01_slam_route.csv",
            "trajectory_spacing_m": 0.05,
            "lap_min_distance_m": 2.0,
            "lap_closure_distance_m": 0.15,
            "warehouse_width_m": 2.0,
            "warehouse_height_m": 3.0,
            "warehouse_bounds_tolerance_m": 0.05,
        }
        for name, default in defaults.items():
            self.declare_parameter(name, default)

        self._map_frame = str(self._value("map_frame"))
        self._base_frame = str(self._value("base_frame"))
        self._origin_configured = bool(self._value("origin_configured"))
        self._origin = (
            float(self._value("origin_x_m")),
            float(self._value("origin_y_m")),
            float(self._value("origin_yaw_rad")),
        )
        rate = float(self._value("publish_rate_hz"))
        if not math.isclose(rate, 10.0, rel_tol=0.0, abs_tol=1e-6):
            raise ValueError("orin-pose-spec requires publish_rate_hz=10.0")
        self._pose_timeout = float(self._value("pose_timeout_sec"))
        self._velocity_timeout = float(self._value("velocity_timeout_sec"))
        self._moving_threshold = float(self._value("moving_threshold_mps"))

        self._tf_buffer = Buffer(cache_time=Duration(seconds=10.0))
        self._tf_listener = TransformListener(self._tf_buffer, self)
        self._linear = 0.0
        self._angular = 0.0
        self._velocity_received_at = -math.inf
        self._dynamic = {}
        self._odom_subscription = self.create_subscription(
            Odometry, str(self._value("odom_topic")), self._on_odom,
            qos_profile_sensor_data,
        )
        self._status_subscription = self.create_subscription(
            String, str(self._value("status_topic")), self._on_status, 10,
        )

        self._trajectory_path = str(self._value("trajectory_csv")).strip()
        self._trajectory_file = None
        self._trajectory_writer = None
        self._last_recorded = None
        self._lap_start = None
        self._lap_distance = 0.0
        self._lap_complete = False
        self._open_trajectory()

        self._mqtt_enabled = bool(self._value("mqtt_enabled"))
        self._mqtt_connected = False
        self._mqtt = mqtt_client
        if self._mqtt_enabled:
            if not self._origin_configured:
                raise ValueError(
                    "mqtt_enabled=true requires origin_configured=true; "
                    "never publish unaligned SLAM coordinates"
                )
            self._start_mqtt()
        elif not self._origin_configured:
            self.get_logger().warning(
                "Warehouse origin is not configured: recording SLAM-local "
                "coordinates only; MQTT publication is disabled."
            )

        self._timer = self.create_timer(0.1, self._on_timer)

    def _value(self, name: str):
        return self.get_parameter(name).value

    def _open_trajectory(self) -> None:
        if not self._trajectory_path:
            return
        path = Path(os.path.expanduser(self._trajectory_path)).resolve()
        path.parent.mkdir(parents=True, exist_ok=True)
        self._trajectory_file = path.open("w", newline="", encoding="utf-8")
        self._trajectory_writer = csv.writer(self._trajectory_file)
        self._trajectory_writer.writerow([
            "ts_ms", "slam_x_m", "slam_y_m", "slam_yaw_rad",
            "warehouse_x_m", "warehouse_y_m", "warehouse_yaw_rad",
            "mqtt_x", "mqtt_y", "lap_distance_m",
        ])
        self._trajectory_file.flush()
        self.get_logger().info(f"SLAM route CSV: {path}")

    def _new_mqtt_client(self):
        client_id = str(self._value("mqtt_client_id"))
        try:
            return mqtt.Client(
                callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
                client_id=client_id,
                clean_session=True,
            )
        except (AttributeError, TypeError):
            return mqtt.Client(client_id=client_id, clean_session=True)

    def _start_mqtt(self) -> None:
        self._mqtt = self._mqtt or self._new_mqtt_client()
        username = str(self._value("mqtt_username"))
        password = os.environ.get(str(self._value("mqtt_password_env")), "")
        if not username or not password:
            raise ValueError(
                "MQTT username and password environment variable are required"
            )
        self._mqtt.username_pw_set(username, password)
        ca_cert = os.path.expanduser(str(self._value("mqtt_ca_cert")))
        if not ca_cert or not os.path.isfile(ca_cert):
            raise ValueError("mqtt_ca_cert must point to FAST-MQTT-CA")
        self._mqtt.tls_set(ca_certs=ca_cert)
        insecure = bool(self._value("mqtt_tls_insecure"))
        self._mqtt.tls_insecure_set(insecure)
        if insecure:
            self.get_logger().warning(
                "TLS hostname verification is disabled because the field "
                "certificate is issued to an IP. Use only with FAST-MQTT-CA."
            )
        self._mqtt.on_connect = self._on_connect
        self._mqtt.on_disconnect = self._on_disconnect
        self._mqtt.reconnect_delay_set(min_delay=1, max_delay=30)
        self._mqtt.connect_async(
            str(self._value("mqtt_host")), int(self._value("mqtt_port")),
            int(self._value("mqtt_keepalive")),
        )
        self._mqtt.loop_start()

    def _on_connect(self, client, userdata, flags, reason_code, properties=None):
        code = int(getattr(reason_code, "value", reason_code))
        self._mqtt_connected = code == 0
        if self._mqtt_connected:
            self.get_logger().info(
                f"MQTT telemetry connected: {self._value('mqtt_topic')} QoS 0"
            )
        else:
            self.get_logger().error(f"MQTT connection failed: {reason_code}")

    def _on_disconnect(self, client, userdata, flags, reason_code=None,
                       properties=None):
        self._mqtt_connected = False
        self.get_logger().warning("MQTT telemetry disconnected")

    def _on_odom(self, message: Odometry) -> None:
        self._linear = float(message.twist.twist.linear.x)
        self._angular = float(message.twist.twist.angular.z)
        self._velocity_received_at = time.monotonic()

    def _on_status(self, message: String) -> None:
        try:
            update = json.loads(message.data)
            if not isinstance(update, dict):
                raise ValueError("status must be a JSON object")
        except (json.JSONDecodeError, TypeError, ValueError) as error:
            self.get_logger().warning(f"Invalid telemetry state ignored: {error}")
            return
        allowed = {
            "forkHeight", "loaded", "cargoId", "state", "taskId", "battery"
        }
        self._dynamic.update({key: update[key] for key in allowed if key in update})

    def _lookup_pose(self):
        try:
            transform = self._tf_buffer.lookup_transform(
                self._map_frame, self._base_frame, Time()
            )
        except TransformException as error:
            self.get_logger().warning(
                f"Pose unavailable; skipping telemetry cycle: {error}",
                throttle_duration_sec=5.0,
            )
            return None
        stamp = Time.from_msg(transform.header.stamp)
        if stamp.nanoseconds > 0:
            age = (self.get_clock().now() - stamp).nanoseconds / 1e9
            if age > self._pose_timeout:
                self.get_logger().warning(
                    f"Pose is stale ({age:.2f}s); skipping telemetry cycle",
                    throttle_duration_sec=5.0,
                )
                return None
        translation = transform.transform.translation
        rotation = transform.transform.rotation
        return (
            float(translation.x), float(translation.y),
            yaw_from_quaternion(rotation.x, rotation.y, rotation.z, rotation.w),
        )

    def _record(self, slam_pose, warehouse_pose, timestamp_ms: int) -> None:
        if self._trajectory_writer is None:
            return
        spacing = float(self._value("trajectory_spacing_m"))
        if self._last_recorded is not None:
            step = math.hypot(
                warehouse_pose[0] - self._last_recorded[0],
                warehouse_pose[1] - self._last_recorded[1],
            )
            if step < spacing:
                return
            self._lap_distance += step
        else:
            self._lap_start = warehouse_pose[:2]
        self._last_recorded = warehouse_pose[:2]
        self._trajectory_writer.writerow([
            timestamp_ms,
            round(slam_pose[0], 4), round(slam_pose[1], 4),
            round(slam_pose[2], 5), round(warehouse_pose[0], 4),
            round(warehouse_pose[1], 4), round(warehouse_pose[2], 5),
            round(warehouse_pose[0] * SCALE, 3),
            round(warehouse_pose[1] * SCALE, 3),
            round(self._lap_distance, 3),
        ])
        self._trajectory_file.flush()

        if self._lap_complete or self._lap_start is None:
            return
        closure = math.hypot(
            warehouse_pose[0] - self._lap_start[0],
            warehouse_pose[1] - self._lap_start[1],
        )
        if (
            self._lap_distance >= float(self._value("lap_min_distance_m"))
            and closure <= float(self._value("lap_closure_distance_m"))
        ):
            self._lap_complete = True
            self.get_logger().info(
                f"One lap complete: distance={self._lap_distance:.2f}m, "
                f"closure_error={closure:.3f}m"
            )

    def _state(self, linear: float, angular: float) -> str:
        state = str(self._dynamic.get("state") or self._value("state_override"))
        state = state.upper().strip()
        if not state:
            state = "MOVING" if max(abs(linear), abs(angular)) >= self._moving_threshold else "IDLE"
        if state not in VALID_STATES:
            self.get_logger().warning(f"Unknown state {state}; using ERROR")
            return "ERROR"
        return state

    def _on_timer(self) -> None:
        slam_pose = self._lookup_pose()
        if slam_pose is None:
            return
        warehouse_pose = (
            align_slam_pose(*slam_pose, *self._origin)
            if self._origin_configured else slam_pose
        )
        now_ms = int(time.time() * 1000)
        self._record(slam_pose, warehouse_pose, now_ms)
        if not self._mqtt_enabled:
            return

        tolerance = float(self._value("warehouse_bounds_tolerance_m"))
        if not (
            -tolerance <= warehouse_pose[0]
            <= float(self._value("warehouse_width_m")) + tolerance
            and -tolerance <= warehouse_pose[1]
            <= float(self._value("warehouse_height_m")) + tolerance
        ):
            self.get_logger().error(
                "Aligned pose is outside the 2 x 3 m warehouse; skipping MQTT "
                f"x={warehouse_pose[0]:.3f}, y={warehouse_pose[1]:.3f}",
                throttle_duration_sec=5.0,
            )
            return
        # QoS 0 telemetry is current-state data. Do not queue stale samples
        # while disconnected and burst them after reconnection.
        if not self._mqtt_connected:
            return

        if time.monotonic() - self._velocity_received_at > self._velocity_timeout:
            linear = angular = 0.0
        else:
            linear, angular = self._linear, self._angular
        payload = telemetry_payload(
            vehicle_id=str(self._value("vehicle_id")), timestamp_ms=now_ms,
            x_m=warehouse_pose[0], y_m=warehouse_pose[1], yaw=warehouse_pose[2],
            linear_mps=linear, angular_rps=angular,
            fork_height_m=float(self._dynamic.get("forkHeight", self._value("fork_height_m"))),
            loaded=bool(self._dynamic.get("loaded", self._value("loaded"))),
            cargo_id=self._dynamic.get("cargoId") or str(self._value("cargo_id")) or None,
            state=self._state(linear, angular),
            task_id=self._dynamic.get("taskId") or str(self._value("task_id")) or None,
            battery=float(self._dynamic.get("battery", self._value("battery"))),
        )
        info = self._mqtt.publish(
            str(self._value("mqtt_topic")),
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            qos=0,
            retain=False,
        )
        if getattr(info, "rc", mqtt.MQTT_ERR_SUCCESS) != mqtt.MQTT_ERR_SUCCESS:
            self.get_logger().warning(f"MQTT publish failed rc={info.rc}")

    def destroy_node(self) -> bool:
        if self._trajectory_file is not None:
            self._trajectory_file.close()
        if self._mqtt is not None:
            try:
                self._mqtt.disconnect()
                self._mqtt.loop_stop()
            except Exception:
                pass
        return super().destroy_node()


def main(args=None) -> None:
    rclpy.init(args=args)
    node = OrinTelemetryNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
