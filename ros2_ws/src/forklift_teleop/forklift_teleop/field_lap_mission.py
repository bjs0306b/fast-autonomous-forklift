"""Run one configured warehouse lap through sequential Nav2 goals."""

import json
import math
import os
import time

from action_msgs.msg import GoalStatus
from ament_index_python.packages import get_package_share_directory
from nav2_msgs.action import NavigateToPose
from nav_msgs.msg import OccupancyGrid
import rclpy
from rclpy.action import ActionClient
from rclpy.node import Node
from rclpy.qos import (
    DurabilityPolicy,
    HistoryPolicy,
    QoSProfile,
    ReliabilityPolicy,
    qos_profile_sensor_data,
)
from sensor_msgs.msg import LaserScan, PointCloud2
from std_msgs.msg import String

from forklift_teleop.lap_route import subdivide_route, warehouse_to_slam


class FieldLapMission(Node):
    """Wait for the full sensor/Nav2 stack, then complete exactly one lap."""

    def __init__(self) -> None:
        super().__init__("field_lap_mission")
        defaults = {
            "mission_armed": False,
            "drive_enabled": False,
            "origin_configured": False,
            "origin_x_m": 0.0,
            "origin_y_m": 0.0,
            "origin_yaw_rad": 0.0,
            "map_frame": "map",
            "navigate_action": "/navigate_to_pose",
            "map_topic": "/map",
            "scan_topic": "/scan",
            "tof_left_topic": "/tof/left/points",
            "tof_right_topic": "/tof/right/points",
            "status_topic": "/field_lap/status",
            "start_delay_sec": 5.0,
            "sensor_timeout_sec": 1.0,
            "navigation_timeout_sec": 180.0,
            "max_retries_per_waypoint": 1,
            "max_segment_length_m": 0.25,
            "return_to_origin": True,
            "waypoint_x_m": [1.55, 1.55, 0.50, 0.50],
            "waypoint_y_m": [0.50, 2.50, 2.50, 0.50],
            "waypoint_yaw_rad": [0.0, 1.5708, 3.1416, -1.5708],
        }
        for name, default in defaults.items():
            self.declare_parameter(name, default)

        if not bool(self._value("mission_armed")):
            raise ValueError("field lap refuses to start unless mission_armed=true")
        if not bool(self._value("drive_enabled")):
            raise ValueError("field lap requires drive_enabled=true")
        if not bool(self._value("origin_configured")):
            raise ValueError(
                "field lap requires the measured starting warehouse pose: "
                "origin_configured=true"
            )

        self._origin = (
            float(self._value("origin_x_m")),
            float(self._value("origin_y_m")),
            float(self._value("origin_yaw_rad")),
        )
        x_values = [float(value) for value in self._value("waypoint_x_m")]
        y_values = [float(value) for value in self._value("waypoint_y_m")]
        yaw_values = [
            float(value) for value in self._value("waypoint_yaw_rad")
        ]
        if not x_values or not len(x_values) == len(y_values) == len(yaw_values):
            raise ValueError("lap waypoint x/y/yaw arrays must have equal nonzero size")
        warehouse_route = list(zip(x_values, y_values, yaw_values))
        if bool(self._value("return_to_origin")):
            warehouse_route.append(self._origin)
        for x_m, y_m, yaw_rad in warehouse_route:
            if not all(math.isfinite(value) for value in (x_m, y_m, yaw_rad)):
                raise ValueError("lap waypoints must be finite")
            if not 0.0 <= x_m <= 2.0 or not 0.0 <= y_m <= 3.0:
                raise ValueError("lap waypoint is outside the 2 x 3 m warehouse")
        slam_waypoints = [
            warehouse_to_slam(x_m, y_m, yaw_rad, *self._origin)
            for x_m, y_m, yaw_rad in warehouse_route
        ]
        max_segment_length = float(self._value("max_segment_length_m"))
        self._route = subdivide_route(
            (0.0, 0.0, 0.0), slam_waypoints, max_segment_length
        )

        self._map_frame = str(self._value("map_frame"))
        self._sensor_timeout = float(self._value("sensor_timeout_sec"))
        self._start_delay = float(self._value("start_delay_sec"))
        self._navigation_timeout = float(
            self._value("navigation_timeout_sec")
        )
        self._max_retries = int(self._value("max_retries_per_waypoint"))
        if min(
            self._sensor_timeout, self._start_delay, self._navigation_timeout
        ) <= 0.0 or self._max_retries < 0:
            raise ValueError("lap timing must be positive and retries nonnegative")

        behavior_tree = os.path.join(
            get_package_share_directory("forklift_teleop"),
            "behavior_trees",
            "navigate_to_pose_no_spin.xml",
        )
        self._behavior_tree = behavior_tree
        self._navigate = ActionClient(
            self, NavigateToPose, str(self._value("navigate_action"))
        )

        map_qos = QoSProfile(
            history=HistoryPolicy.KEEP_LAST,
            depth=1,
            reliability=ReliabilityPolicy.RELIABLE,
            durability=DurabilityPolicy.TRANSIENT_LOCAL,
        )
        self._map_subscription = self.create_subscription(
            OccupancyGrid, str(self._value("map_topic")),
            lambda message: self._mark_sensor("map"), map_qos,
        )
        self._scan_subscription = self.create_subscription(
            LaserScan, str(self._value("scan_topic")),
            lambda message: self._mark_sensor("scan"), qos_profile_sensor_data,
        )
        self._tof_left_subscription = self.create_subscription(
            PointCloud2, str(self._value("tof_left_topic")),
            lambda message: self._mark_sensor("tof_left"), qos_profile_sensor_data,
        )
        self._tof_right_subscription = self.create_subscription(
            PointCloud2, str(self._value("tof_right_topic")),
            lambda message: self._mark_sensor("tof_right"), qos_profile_sensor_data,
        )
        status_qos = QoSProfile(
            history=HistoryPolicy.KEEP_LAST,
            depth=1,
            reliability=ReliabilityPolicy.RELIABLE,
            durability=DurabilityPolicy.TRANSIENT_LOCAL,
        )
        self._status_publisher = self.create_publisher(
            String, str(self._value("status_topic")), status_qos
        )

        self._seen = {
            "map": -math.inf,
            "scan": -math.inf,
            "tof_left": -math.inf,
            "tof_right": -math.inf,
        }
        self._ready_since = None
        self._index = 0
        self._retry = 0
        self._goal_handle = None
        self._goal_deadline = math.inf
        self._finished = False
        self._last_wait_reason = None
        self._timer = self.create_timer(0.2, self._tick)
        self._publish_status("WAITING", "waiting for map, sensors and Nav2")
        self.get_logger().warning(
            f"AUTO LAP ARMED: {len(self._route)} goals; origin={self._origin}"
        )

    def _value(self, name: str):
        return self.get_parameter(name).value

    def _mark_sensor(self, name: str) -> None:
        self._seen[name] = time.monotonic()

    def _publish_status(self, state: str, detail: str) -> None:
        current = None
        if self._index < len(self._route):
            current = {
                "index": self._index + 1,
                "total": len(self._route),
                "mapPose": {
                    "x": round(self._route[self._index][0], 3),
                    "y": round(self._route[self._index][1], 3),
                    "yaw": round(self._route[self._index][2], 4),
                },
            }
        payload = {
            "state": state,
            "detail": detail,
            "currentGoal": current,
        }
        self._status_publisher.publish(String(
            data=json.dumps(payload, separators=(",", ":"))
        ))

    def _not_ready_reason(self, now: float) -> str:
        missing = []
        if self._seen["map"] == -math.inf:
            missing.append("map")
        for name in ("scan", "tof_left", "tof_right"):
            if now - self._seen[name] > self._sensor_timeout:
                missing.append(name)
        if not self._navigate.server_is_ready():
            missing.append("navigate_to_pose")
        return ",".join(missing)

    def _tick(self) -> None:
        if self._finished:
            return
        now = time.monotonic()
        if self._goal_handle is not None:
            if now > self._goal_deadline:
                self.get_logger().error(
                    f"Waypoint {self._index + 1} timed out; cancelling"
                )
                if hasattr(self._goal_handle, "cancel_goal_async"):
                    self._goal_handle.cancel_goal_async()
                self._goal_handle = None
                self._handle_failure("navigation timeout")
            return

        reason = self._not_ready_reason(now)
        if reason:
            self._ready_since = None
            if reason != self._last_wait_reason:
                self._last_wait_reason = reason
                self.get_logger().info(f"Lap waiting for: {reason}")
                self._publish_status("WAITING", reason)
            return

        if self._ready_since is None:
            self._ready_since = now
            self._last_wait_reason = None
            self.get_logger().info(
                f"Lap prerequisites ready; settling {self._start_delay:.1f}s"
            )
            return
        if now - self._ready_since < self._start_delay:
            return
        self._send_goal()

    def _send_goal(self) -> None:
        x_m, y_m, yaw_rad = self._route[self._index]
        goal = NavigateToPose.Goal()
        goal.pose.header.frame_id = self._map_frame
        goal.pose.header.stamp = self.get_clock().now().to_msg()
        goal.pose.pose.position.x = x_m
        goal.pose.pose.position.y = y_m
        goal.pose.pose.orientation.z = math.sin(yaw_rad / 2.0)
        goal.pose.pose.orientation.w = math.cos(yaw_rad / 2.0)
        goal.behavior_tree = self._behavior_tree
        self.get_logger().warning(
            f"AUTO LAP goal {self._index + 1}/{len(self._route)}: "
            f"map=({x_m:.3f},{y_m:.3f},{yaw_rad:.3f}), retry={self._retry}"
        )
        self._publish_status("NAVIGATING", "goal sent")
        future = self._navigate.send_goal_async(goal)
        future.add_done_callback(self._goal_response)
        # A non-None sentinel prevents the timer from sending the same goal
        # while the action server is still answering.
        self._goal_handle = False
        self._goal_deadline = time.monotonic() + self._navigation_timeout

    def _goal_response(self, future) -> None:
        try:
            handle = future.result()
        except Exception as error:
            self._goal_handle = None
            self._handle_failure(f"goal request failed: {error}")
            return
        if handle is None or not handle.accepted:
            self._goal_handle = None
            self._handle_failure("goal rejected")
            return
        self._goal_handle = handle
        result_future = handle.get_result_async()
        result_future.add_done_callback(self._goal_result)

    def _goal_result(self, future) -> None:
        self._goal_handle = None
        try:
            result = future.result()
            status = result.status
        except Exception as error:
            self._handle_failure(f"result failed: {error}")
            return
        if status != GoalStatus.STATUS_SUCCEEDED:
            self._handle_failure(f"Nav2 status={status}")
            return

        self.get_logger().info(
            f"AUTO LAP waypoint {self._index + 1}/{len(self._route)} reached"
        )
        self._index += 1
        self._retry = 0
        self._ready_since = time.monotonic()
        if self._index >= len(self._route):
            self._finished = True
            self._publish_status("COMPLETED", "one lap returned to origin")
            self.get_logger().warning("AUTO LAP COMPLETED: returned to origin")

    def _handle_failure(self, reason: str) -> None:
        if self._retry < self._max_retries:
            self._retry += 1
            self._ready_since = time.monotonic()
            self.get_logger().warning(
                f"Waypoint {self._index + 1} failed ({reason}); "
                f"retry {self._retry}/{self._max_retries}"
            )
            self._publish_status("RETRYING", reason)
            return
        self._finished = True
        self._publish_status("FAILED", reason)
        self.get_logger().error(
            f"AUTO LAP FAILED at waypoint {self._index + 1}: {reason}"
        )


def main(args=None) -> None:
    rclpy.init(args=args)
    node = FieldLapMission()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
