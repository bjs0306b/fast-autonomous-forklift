"""ROS2 guard for roof LiDAR and two forward-facing fork-level ToFs."""

import json
import math
import struct
import time

from geometry_msgs.msg import Twist
import rclpy
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from rclpy.time import Time
from sensor_msgs.msg import LaserScan, PointCloud2, PointField
from std_msgs.msg import String
from tf2_ros import Buffer, TransformException, TransformListener

from forklift_teleop.obstacle_fusion import (
    AvoidanceAction,
    AvoidanceConfig,
    FrontTofClearance,
    LidarCorridors,
    VisionDetection,
    apply_decision,
    decide_avoidance,
    front_tof_distance_from_points,
    lidar_corridors_from_points,
)


class ObstacleAvoidanceNode(Node):
    def __init__(self) -> None:
        super().__init__("obstacle_avoidance")

        self.declare_parameter("base_frame_id", "base_link")
        self.declare_parameter("input_cmd_vel_topic", "/cmd_vel")
        self.declare_parameter("output_cmd_vel_topic", "/cmd_vel_safe")
        self.declare_parameter("scan_topic", "/scan")
        self.declare_parameter("front_tof_topics", [
            "/tof/left/points", "/tof/right/points"
        ])
        self.declare_parameter("vision_topic", "/camera/obstacles")
        self.declare_parameter("status_topic", "/obstacle_avoidance/status")
        self.declare_parameter("control_rate_hz", 20.0)
        self.declare_parameter("command_timeout_sec", 0.5)
        self.declare_parameter("sensor_timeout_sec", 0.5)
        self.declare_parameter("vision_timeout_sec", 0.5)
        self.declare_parameter("require_roof_lidar", True)
        self.declare_parameter("require_both_front_tof", True)
        self.declare_parameter("require_vision", False)
        self.declare_parameter("lidar_front_half_angle_deg", 70.0)
        self.declare_parameter("lidar_center_half_angle_deg", 18.0)
        self.declare_parameter("tof_front_half_angle_deg", 35.0)
        self.declare_parameter("tof_min_height_m", 0.02)
        self.declare_parameter("tof_max_height_m", 0.80)
        self.declare_parameter("minimum_sector_hits", 2)
        self.declare_parameter("stop_distance_m", 0.45)
        self.declare_parameter("slowdown_distance_m", 1.00)
        self.declare_parameter("minimum_speed_scale", 0.25)
        self.declare_parameter("avoidance_yaw_rate_rps", 0.18)
        self.declare_parameter("max_abs_yaw_rate_rps", 0.35)
        self.declare_parameter("tof_imbalance_m", 0.10)
        self.declare_parameter("lidar_clearance_margin_m", 0.15)
        self.declare_parameter("minimum_turn_clearance_m", 0.55)
        self.declare_parameter("vision_confidence_threshold", 0.60)
        self.declare_parameter("dynamic_object_stop_distance_m", 1.50)
        self.declare_parameter("dynamic_labels", [
            "person", "pedestrian", "forklift", "vehicle", "car", "truck"
        ])

        self._base_frame = str(self.get_parameter("base_frame_id").value)
        rate_hz = float(self.get_parameter("control_rate_hz").value)
        self._command_timeout = float(
            self.get_parameter("command_timeout_sec").value
        )
        self._sensor_timeout = float(
            self.get_parameter("sensor_timeout_sec").value
        )
        self._vision_timeout = float(
            self.get_parameter("vision_timeout_sec").value
        )
        if min(rate_hz, self._command_timeout, self._sensor_timeout) <= 0.0:
            raise ValueError("rate and timeout parameters must be positive")

        self._require_lidar = bool(
            self.get_parameter("require_roof_lidar").value
        )
        self._require_tofs = bool(
            self.get_parameter("require_both_front_tof").value
        )
        self._require_vision = bool(
            self.get_parameter("require_vision").value
        )
        self._lidar_front_angle = math.radians(float(
            self.get_parameter("lidar_front_half_angle_deg").value
        ))
        self._lidar_center_angle = math.radians(float(
            self.get_parameter("lidar_center_half_angle_deg").value
        ))
        self._tof_front_angle = math.radians(float(
            self.get_parameter("tof_front_half_angle_deg").value
        ))
        if not 0.0 < self._lidar_center_angle < self._lidar_front_angle:
            raise ValueError("LiDAR center angle must be inside front angle")
        self._tof_min_height = float(
            self.get_parameter("tof_min_height_m").value
        )
        self._tof_max_height = float(
            self.get_parameter("tof_max_height_m").value
        )
        if self._tof_max_height <= self._tof_min_height:
            raise ValueError("ToF maximum height must exceed minimum height")
        self._minimum_hits = int(
            self.get_parameter("minimum_sector_hits").value
        )
        if self._minimum_hits < 1:
            raise ValueError("minimum_sector_hits must be at least one")

        self._policy = AvoidanceConfig(
            stop_distance_m=float(
                self.get_parameter("stop_distance_m").value
            ),
            slowdown_distance_m=float(
                self.get_parameter("slowdown_distance_m").value
            ),
            minimum_speed_scale=float(
                self.get_parameter("minimum_speed_scale").value
            ),
            avoidance_yaw_rate_rps=float(
                self.get_parameter("avoidance_yaw_rate_rps").value
            ),
            tof_imbalance_m=float(
                self.get_parameter("tof_imbalance_m").value
            ),
            lidar_clearance_margin_m=float(
                self.get_parameter("lidar_clearance_margin_m").value
            ),
            minimum_turn_clearance_m=float(
                self.get_parameter("minimum_turn_clearance_m").value
            ),
            vision_confidence_threshold=float(
                self.get_parameter("vision_confidence_threshold").value
            ),
            dynamic_object_stop_distance_m=float(
                self.get_parameter("dynamic_object_stop_distance_m").value
            ),
            dynamic_labels=tuple(
                str(value) for value in
                self.get_parameter("dynamic_labels").value
            ),
        )
        self._policy.validate()
        self._max_yaw_rate = float(
            self.get_parameter("max_abs_yaw_rate_rps").value
        )

        self._tf_buffer = Buffer()
        self._tf_listener = TransformListener(self._tf_buffer, self)
        self._command = Twist()
        self._command_time = -math.inf
        self._lidar = LidarCorridors()
        self._lidar_time = -math.inf
        self._tof_distance = [math.inf, math.inf]
        self._tof_time = [-math.inf, -math.inf]
        self._vision = []
        self._vision_time = -math.inf
        self._last_action = None

        input_topic = str(
            self.get_parameter("input_cmd_vel_topic").value
        )
        output_topic = str(
            self.get_parameter("output_cmd_vel_topic").value
        )
        self._cmd_subscription = self.create_subscription(
            Twist, input_topic, self._on_command, 10
        )
        self._scan_subscription = self.create_subscription(
            LaserScan,
            str(self.get_parameter("scan_topic").value),
            self._on_scan,
            qos_profile_sensor_data,
        )
        tof_topics = [
            str(value) for value in
            self.get_parameter("front_tof_topics").value
        ]
        if len(tof_topics) != 2:
            raise ValueError(
                "front_tof_topics must contain front-left and front-right"
            )
        self._tof_subscriptions = [
            self.create_subscription(
                PointCloud2,
                topic,
                lambda message, index=index: self._on_tof(message, index),
                qos_profile_sensor_data,
            )
            for index, topic in enumerate(tof_topics)
        ]
        self._vision_subscription = self.create_subscription(
            String,
            str(self.get_parameter("vision_topic").value),
            self._on_vision,
            10,
        )
        self._safe_publisher = self.create_publisher(
            Twist, output_topic, 10
        )
        self._status_publisher = self.create_publisher(
            String,
            str(self.get_parameter("status_topic").value),
            10,
        )
        self._timer = self.create_timer(1.0 / rate_hz, self._on_timer)
        self.get_logger().info(
            "Obstacle avoidance ready: roof LiDAR + front-left/right ToF, "
            f"{input_topic} -> {output_topic}"
        )

    def _on_command(self, message: Twist) -> None:
        self._command = message
        self._command_time = time.monotonic()

    def _lookup_transform(self, source_frame: str):
        if not source_frame:
            raise TransformException("sensor frame_id is empty")
        return self._tf_buffer.lookup_transform(
            self._base_frame, source_frame, Time()
        ).transform

    @staticmethod
    def _transform_xyz(point: tuple, transform) -> tuple:
        x, y, z = point
        q = transform.rotation
        # Quaternion rotation matrix, followed by translation.
        r00 = 1.0 - 2.0 * (q.y * q.y + q.z * q.z)
        r01 = 2.0 * (q.x * q.y - q.z * q.w)
        r02 = 2.0 * (q.x * q.z + q.y * q.w)
        r10 = 2.0 * (q.x * q.y + q.z * q.w)
        r11 = 1.0 - 2.0 * (q.x * q.x + q.z * q.z)
        r12 = 2.0 * (q.y * q.z - q.x * q.w)
        r20 = 2.0 * (q.x * q.z - q.y * q.w)
        r21 = 2.0 * (q.y * q.z + q.x * q.w)
        r22 = 1.0 - 2.0 * (q.x * q.x + q.y * q.y)
        return (
            r00 * x + r01 * y + r02 * z + transform.translation.x,
            r10 * x + r11 * y + r12 * z + transform.translation.y,
            r20 * x + r21 * y + r22 * z + transform.translation.z,
        )

    def _on_scan(self, message: LaserScan) -> None:
        try:
            transform = self._lookup_transform(message.header.frame_id)
            points = []
            for index, distance in enumerate(message.ranges):
                if not message.range_min <= distance <= message.range_max:
                    continue
                angle = message.angle_min + index * message.angle_increment
                point = (distance * math.cos(angle),
                         distance * math.sin(angle), 0.0)
                x, y, _ = self._transform_xyz(point, transform)
                points.append((x, y))
            self._lidar = lidar_corridors_from_points(
                points,
                self._lidar_center_angle,
                self._lidar_front_angle,
                self._minimum_hits,
            )
            self._lidar_time = time.monotonic()
        except TransformException as error:
            self.get_logger().warning(f"LiDAR TF unavailable: {error}")

    @staticmethod
    def _pointcloud_xyz(message: PointCloud2):
        offsets = {
            field.name: field.offset for field in message.fields
            if field.datatype == PointField.FLOAT32
        }
        if not {"x", "y", "z"}.issubset(offsets) or message.point_step <= 0:
            raise ValueError("PointCloud2 must contain float32 x/y/z fields")
        endian = ">" if message.is_bigendian else "<"
        unpack = struct.Struct(endian + "f").unpack_from
        for row in range(message.height):
            row_base = row * message.row_step
            for column in range(message.width):
                base = row_base + column * message.point_step
                yield (
                    unpack(message.data, base + offsets["x"])[0],
                    unpack(message.data, base + offsets["y"])[0],
                    unpack(message.data, base + offsets["z"])[0],
                )

    def _on_tof(self, message: PointCloud2, index: int) -> None:
        try:
            transform = self._lookup_transform(message.header.frame_id)
            points = (
                self._transform_xyz(point, transform)
                for point in self._pointcloud_xyz(message)
            )
            self._tof_distance[index] = front_tof_distance_from_points(
                points,
                self._tof_front_angle,
                self._tof_min_height,
                self._tof_max_height,
                self._minimum_hits,
            )
            self._tof_time[index] = time.monotonic()
        except (TransformException, ValueError, struct.error) as error:
            self.get_logger().warning(f"Front ToF TF/data unavailable: {error}")

    def _on_vision(self, message: String) -> None:
        try:
            document = json.loads(message.data)
            raw_detections = document.get("detections", [])
            if not isinstance(raw_detections, list):
                raise ValueError("detections must be a list")
            detections = []
            for raw in raw_detections:
                if not isinstance(raw, dict):
                    raise ValueError("each detection must be an object")
                distance = raw.get("distance_m")
                if distance is not None:
                    distance = float(distance)
                    if not math.isfinite(distance) or distance <= 0.0:
                        raise ValueError("distance_m must be positive")
                detections.append(VisionDetection(
                    label=str(raw["label"]),
                    confidence=float(raw.get("confidence", 1.0)),
                    distance_m=distance,
                ))
            self._vision = detections
            self._vision_time = time.monotonic()
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            self.get_logger().warning(f"Rejected camera detections: {error}")

    def _missing_required_sensors(self, now: float) -> list:
        missing = []
        if self._require_lidar and now - self._lidar_time > self._sensor_timeout:
            missing.append("roof_lidar")
        if self._require_tofs:
            names = ("front_left_tof", "front_right_tof")
            for name, stamp in zip(names, self._tof_time):
                if now - stamp > self._sensor_timeout:
                    missing.append(name)
        if self._require_vision and now - self._vision_time > self._vision_timeout:
            missing.append("vision")
        return missing

    def _on_timer(self) -> None:
        now = time.monotonic()
        output = Twist()
        if now - self._command_time > self._command_timeout:
            self._safe_publisher.publish(output)
            return

        vision = (
            self._vision
            if now - self._vision_time <= self._vision_timeout
            else []
        )
        tof = FrontTofClearance(*self._tof_distance)
        decision = decide_avoidance(
            self._lidar,
            tof,
            vision,
            self._missing_required_sensors(now),
            self._policy,
        )
        linear, angular = apply_decision(
            self._command.linear.x,
            self._command.angular.z,
            decision,
            self._max_yaw_rate,
        )
        output.linear.x = linear
        output.angular.z = angular
        self._safe_publisher.publish(output)

        def finite_or_none(value):
            return None if math.isinf(value) else round(value, 3)

        status = {
            "action": decision.action.value,
            "reason": decision.reason,
            "speedScale": round(decision.speed_scale, 3),
            "roofLidar": {
                "left": finite_or_none(self._lidar.left_m),
                "center": finite_or_none(self._lidar.center_m),
                "right": finite_or_none(self._lidar.right_m),
            },
            "frontTof": {
                "left": finite_or_none(tof.front_left_m),
                "right": finite_or_none(tof.front_right_m),
            },
            "visionLabels": [item.label for item in vision],
        }
        self._status_publisher.publish(String(
            data=json.dumps(status, separators=(",", ":"))
        ))
        if decision.action != self._last_action:
            log = (
                self.get_logger().warning
                if decision.action in {
                    AvoidanceAction.STOP,
                    AvoidanceAction.SENSOR_TIMEOUT,
                }
                else self.get_logger().info
            )
            log(f"Avoidance {decision.action.value}: {decision.reason}")
            self._last_action = decision.action


def main(args=None) -> None:
    rclpy.init(args=args)
    node = ObstacleAvoidanceNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
