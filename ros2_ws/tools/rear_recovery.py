#!/usr/bin/env python3
"""One-shot, odometry-bounded rear escape for field recovery."""

import json
import math
import sys
import time

import rclpy
from geometry_msgs.msg import Twist
from nav_msgs.msg import Odometry
from rclpy.node import Node
from std_msgs.msg import String


class RearRecovery(Node):
    def __init__(self) -> None:
        super().__init__("rear_recovery_once")
        self.publisher = self.create_publisher(Twist, "/cmd_vel", 10)
        self.create_subscription(Odometry, "/odometry/filtered", self.on_odom, 10)
        self.create_subscription(String, "/obstacle_avoidance/status", self.on_status, 10)
        self.create_timer(0.05, self.tick)
        self.position = None
        self.start_position = None
        self.status = None
        self.status_time = 0.0
        self.started = 0.0
        self.phase = "WAIT"
        self.result = ""
        self.stop_count = 0

    def on_odom(self, message: Odometry) -> None:
        point = message.pose.pose.position
        self.position = (point.x, point.y)

    def on_status(self, message: String) -> None:
        try:
            self.status = json.loads(message.data)
            self.status_time = time.monotonic()
        except (json.JSONDecodeError, TypeError):
            pass

    def publish(self, linear_x: float) -> None:
        command = Twist()
        command.linear.x = linear_x
        self.publisher.publish(command)

    def finish(self, result: str) -> None:
        if self.phase != "STOP":
            self.phase = "STOP"
            self.result = result
            self.stop_count = 0
            self.get_logger().info(result)

    def distance(self) -> float:
        if self.position is None or self.start_position is None:
            return 0.0
        return math.hypot(
            self.position[0] - self.start_position[0],
            self.position[1] - self.start_position[1],
        )

    def tick(self) -> None:
        now = time.monotonic()
        if self.phase == "WAIT":
            self.publish(0.0)
            if self.position is None or self.status is None:
                return
            rear = self.status.get("roofLidar", {}).get("rear")
            if rear is None or rear <= 0.30:
                self.finish(f"ABORT: rear clearance is {rear!r}m")
                return
            if now - self.status_time > 0.5:
                return
            self.start_position = self.position
            self.started = now
            self.phase = "REVERSE"
            self.get_logger().info(
                f"START: rear={rear:.3f}m, target=0.330m, stop=0.300m"
            )
            return

        if self.phase == "REVERSE":
            if now - self.status_time > 0.5:
                self.finish(f"STOP: safety status stale, moved={self.distance():.3f}m")
                return
            rear = self.status.get("roofLidar", {}).get("rear")
            action = self.status.get("action", "")
            reason = self.status.get("reason", "")
            if rear is None or rear <= 0.30:
                self.finish(
                    f"STOP: rear threshold, rear={rear!r}m, moved={self.distance():.3f}m"
                )
                return
            if action == "SENSOR_TIMEOUT" or reason.startswith("dynamic object"):
                self.finish(
                    f"STOP: {action} {reason}, moved={self.distance():.3f}m"
                )
                return
            if self.distance() >= 0.33:
                self.finish(
                    f"COMPLETE: moved={self.distance():.3f}m, rear={rear:.3f}m"
                )
                return
            if now - self.started >= 5.0:
                self.finish(
                    f"STOP: 5s timeout, moved={self.distance():.3f}m, rear={rear:.3f}m"
                )
                return
            self.publish(-0.10)
            return

        self.publish(0.0)
        self.stop_count += 1
        if self.stop_count >= 20:
            print(self.result, flush=True)
            rclpy.shutdown()


def main() -> int:
    rclpy.init()
    node = RearRecovery()
    try:
        rclpy.spin(node)
    finally:
        node.publish(0.0)
        node.destroy_node()
    return 0 if node.result.startswith("COMPLETE") else 2


if __name__ == "__main__":
    sys.exit(main())
