"""Coordinate one Nav2 pickup-and-dropoff mission with the fork bridge."""

import json
import math
import os
import time
from typing import Optional

from action_msgs.msg import GoalStatus
from ament_index_python.packages import get_package_share_directory
from nav2_msgs.action import NavigateToPose
import rclpy
from rclpy.action import ActionClient
from rclpy.node import Node
from std_msgs.msg import String


class UnmannedMission(Node):
    """Run navigation and lift actions as one fail-safe sequential mission."""

    def __init__(self) -> None:
        super().__init__("unmanned_mission")

        self.declare_parameter("mission_armed", False)
        self.declare_parameter("map_frame", "map")
        self.declare_parameter("pickup_x", 0.0)
        self.declare_parameter("pickup_y", 0.0)
        self.declare_parameter("pickup_yaw", 0.0)
        self.declare_parameter("dropoff_x", 0.0)
        self.declare_parameter("dropoff_y", 0.0)
        self.declare_parameter("dropoff_yaw", 0.0)
        self.declare_parameter("navigate_action", "/navigate_to_pose")
        default_behavior_tree = os.path.join(
            get_package_share_directory("forklift_teleop"),
            "behavior_trees",
            "navigate_to_pose_no_spin.xml",
        )
        self.declare_parameter("behavior_tree", default_behavior_tree)
        self.declare_parameter("fork_command_topic", "/fork/command")
        self.declare_parameter("fork_status_topic", "/fork/status")
        self.declare_parameter("navigation_timeout_sec", 180.0)
        self.declare_parameter("fork_timeout_sec", 30.0)
        self.declare_parameter("start_delay_sec", 5.0)
        self.declare_parameter("settle_time_sec", 1.0)

        navigate_action = str(self.get_parameter("navigate_action").value)
        self._behavior_tree = str(
            self.get_parameter("behavior_tree").value
        )
        fork_command_topic = str(
            self.get_parameter("fork_command_topic").value
        )
        fork_status_topic = str(self.get_parameter("fork_status_topic").value)

        self._navigation_timeout_sec = float(
            self.get_parameter("navigation_timeout_sec").value
        )
        self._fork_timeout_sec = float(
            self.get_parameter("fork_timeout_sec").value
        )
        self._start_delay_sec = float(
            self.get_parameter("start_delay_sec").value
        )
        self._settle_time_sec = float(
            self.get_parameter("settle_time_sec").value
        )
        if min(
            self._navigation_timeout_sec,
            self._fork_timeout_sec,
            self._start_delay_sec,
            self._settle_time_sec,
        ) < 0.0:
            raise ValueError("mission timeout and delay parameters cannot be negative")

        self._navigate_client = ActionClient(
            self,
            NavigateToPose,
            navigate_action,
        )
        self._fork_publisher = self.create_publisher(
            String,
            fork_command_topic,
            10,
        )
        self._fork_subscription = self.create_subscription(
            String,
            fork_status_topic,
            self._on_fork_status,
            10,
        )

        self._fork_running_seen = False
        self._fork_done = False
        self._fork_error: Optional[str] = None

    def _on_fork_status(self, message: String) -> None:
        try:
            status = json.loads(message.data)
        except (TypeError, ValueError) as error:
            self.get_logger().warning(f"Ignored malformed fork status: {error}")
            return

        state = str(status.get("state", "")).upper()
        if state == "RUNNING":
            self._fork_running_seen = True
        elif state == "DONE" and self._fork_running_seen:
            self._fork_done = True
        elif state == "ERROR":
            self._fork_error = message.data

    def _spin_until(self, future, timeout_sec: float) -> bool:
        deadline = time.monotonic() + timeout_sec
        while rclpy.ok() and not future.done():
            remaining = deadline - time.monotonic()
            if remaining <= 0.0:
                return False
            rclpy.spin_once(self, timeout_sec=min(0.1, remaining))
        return future.done()

    def _wait_for_fork_bridge(self) -> bool:
        deadline = time.monotonic() + 10.0
        while rclpy.ok() and time.monotonic() < deadline:
            if self._fork_publisher.get_subscription_count() > 0:
                return True
            rclpy.spin_once(self, timeout_sec=0.1)
        self.get_logger().error("Fork command subscriber did not appear")
        return False

    def _send_fork_command(self, action: str) -> bool:
        self._fork_running_seen = False
        self._fork_done = False
        self._fork_error = None
        self._fork_publisher.publish(String(data=action))
        self.get_logger().info(f"Fork command sent: {action}")

        deadline = time.monotonic() + self._fork_timeout_sec
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(self, timeout_sec=0.1)
            if self._fork_error is not None:
                self.get_logger().error(
                    f"Fork command {action} failed: {self._fork_error}"
                )
                return False
            if self._fork_done:
                self.get_logger().info(f"Fork command completed: {action}")
                return True

        self.get_logger().error(f"Fork command timed out: {action}")
        self._fork_publisher.publish(String(data="STOP"))
        return False

    def _navigate_to(self, label: str, x: float, y: float, yaw: float) -> bool:
        if not self._navigate_client.wait_for_server(timeout_sec=10.0):
            self.get_logger().error("NavigateToPose action server is unavailable")
            return False

        goal = NavigateToPose.Goal()
        goal.pose.header.frame_id = str(self.get_parameter("map_frame").value)
        goal.pose.header.stamp = self.get_clock().now().to_msg()
        goal.pose.pose.position.x = x
        goal.pose.pose.position.y = y
        goal.pose.pose.orientation.z = math.sin(yaw / 2.0)
        goal.pose.pose.orientation.w = math.cos(yaw / 2.0)
        goal.behavior_tree = self._behavior_tree

        self.get_logger().info(
            f"Navigating to {label}: x={x:.3f}, y={y:.3f}, yaw={yaw:.3f}"
        )
        self.get_logger().info(
            f"Using forklift behavior tree: {self._behavior_tree}"
        )
        goal_future = self._navigate_client.send_goal_async(goal)
        if not self._spin_until(goal_future, 10.0):
            self.get_logger().error(f"Timed out sending {label} goal")
            return False

        goal_handle = goal_future.result()
        if goal_handle is None or not goal_handle.accepted:
            self.get_logger().error(f"Navigation goal rejected: {label}")
            return False

        result_future = goal_handle.get_result_async()
        if not self._spin_until(result_future, self._navigation_timeout_sec):
            self.get_logger().error(f"Navigation timed out: {label}")
            cancel_future = goal_handle.cancel_goal_async()
            self._spin_until(cancel_future, 5.0)
            return False

        result = result_future.result()
        if result is None or result.status != GoalStatus.STATUS_SUCCEEDED:
            status = None if result is None else result.status
            self.get_logger().error(
                f"Navigation failed: {label}, status={status}"
            )
            return False

        self.get_logger().info(f"Navigation completed: {label}")
        return True

    def _settle(self) -> None:
        deadline = time.monotonic() + self._settle_time_sec
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(self, timeout_sec=0.1)

    def _stop_fork(self) -> None:
        self._fork_publisher.publish(String(data="STOP"))

    def run(self) -> bool:
        if not bool(self.get_parameter("mission_armed").value):
            self.get_logger().error(
                "Mission is disarmed; pass -p mission_armed:=true explicitly"
            )
            return False

        pickup = (
            float(self.get_parameter("pickup_x").value),
            float(self.get_parameter("pickup_y").value),
            float(self.get_parameter("pickup_yaw").value),
        )
        dropoff = (
            float(self.get_parameter("dropoff_x").value),
            float(self.get_parameter("dropoff_y").value),
            float(self.get_parameter("dropoff_yaw").value),
        )
        if math.hypot(dropoff[0] - pickup[0], dropoff[1] - pickup[1]) < 0.05:
            self.get_logger().error(
                "Pickup and dropoff must be at least 0.05 m apart"
            )
            return False
        if not self._wait_for_fork_bridge():
            return False

        self.get_logger().warning(
            "Mission armed: ensure the fork is homed and the route is clear"
        )
        deadline = time.monotonic() + self._start_delay_sec
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(self, timeout_sec=0.1)

        try:
            if not self._navigate_to("pickup", *pickup):
                return False
            self._settle()
            if not self._send_fork_command("UP"):
                return False
            self._settle()
            if not self._navigate_to("dropoff", *dropoff):
                return False
            self._settle()
            if not self._send_fork_command("DOWN"):
                return False
        finally:
            self._stop_fork()

        self.get_logger().info("Unmanned pickup-and-dropoff mission completed")
        return True


def main(args=None) -> None:
    rclpy.init(args=args)
    node = UnmannedMission()
    exit_code = 1
    try:
        exit_code = 0 if node.run() else 1
    except KeyboardInterrupt:
        node.get_logger().warning("Mission interrupted; stopping fork")
        node._stop_fork()
    finally:
        rclpy.spin_once(node, timeout_sec=0.2)
        node.destroy_node()
        rclpy.shutdown()
    raise SystemExit(exit_code)
