#!/usr/bin/env python3
"""One-shot, sensor-gated forward-left recovery arc."""

import argparse
import json
import math
import sys
import time

import rclpy
from geometry_msgs.msg import Twist
from nav_msgs.msg import Odometry
from rclpy.node import Node
from std_msgs.msg import String


def yaw_from_odom(message: Odometry) -> float:
    q = message.pose.pose.orientation
    return math.atan2(2.0 * (q.w * q.z), 1.0 - 2.0 * q.z * q.z)


def angle_distance(a: float, b: float) -> float:
    return abs(math.atan2(math.sin(a - b), math.cos(a - b)))


class LeftRecovery(Node):
    def __init__(
        self,
        speed: float,
        angular: float,
        travel_limit: float,
        yaw_target: float,
        timeout: float,
    ) -> None:
        super().__init__("left_recovery_once")
        self.publisher = self.create_publisher(Twist, "/cmd_vel", 10)
        self.create_subscription(Odometry, "/odometry/filtered", self.on_odom, 10)
        self.create_subscription(String, "/obstacle_avoidance/status", self.on_status, 10)
        self.create_timer(0.05, self.tick)
        self.position = None
        self.yaw = None
        self.start_position = None
        self.start_yaw = None
        self.status = None
        self.status_time = 0.0
        self.started = 0.0
        self.phase = "WAIT"
        self.result = ""
        self.stop_count = 0
        self.speed = speed
        self.angular = angular
        self.travel_limit = travel_limit
        self.yaw_target = yaw_target
        self.timeout = timeout
        self.best_moved = 0.0
        self.progress_time = time.monotonic()

    def on_odom(self, message: Odometry) -> None:
        p = message.pose.pose.position
        self.position = (p.x, p.y)
        self.yaw = yaw_from_odom(message)

    def on_status(self, message: String) -> None:
        try:
            self.status = json.loads(message.data)
            self.status_time = time.monotonic()
        except (json.JSONDecodeError, TypeError):
            pass

    def publish(self, linear: float, angular: float = 0.0) -> None:
        command = Twist()
        command.linear.x = linear
        command.angular.z = angular
        self.publisher.publish(command)

    def moved(self) -> float:
        if self.position is None or self.start_position is None:
            return 0.0
        return math.hypot(
            self.position[0] - self.start_position[0],
            self.position[1] - self.start_position[1],
        )

    def turned(self) -> float:
        if self.yaw is None or self.start_yaw is None:
            return 0.0
        return angle_distance(self.yaw, self.start_yaw)

    def finish(self, result: str) -> None:
        if self.phase != "STOP":
            self.phase = "STOP"
            self.result = result
            self.stop_count = 0
            self.get_logger().info(result)

    def safety_reason(self):
        if time.monotonic() - self.status_time > 0.5:
            return "safety status stale"
        action = self.status.get("action", "")
        reason = self.status.get("reason", "")
        if action in ("STOP", "SENSOR_TIMEOUT"):
            return f"{action}: {reason}"
        if reason.startswith("dynamic object"):
            return reason
        expected_avoidance = "AVOID_LEFT" if self.angular > 0.0 else "AVOID_RIGHT"
        if action not in ("CLEAR", "SLOW", expected_avoidance):
            return f"unexpected avoidance action {action}: {reason}"
        tof = self.status.get("frontTof", {})
        values = (tof.get("left"), tof.get("right"))
        if any(value is None or value <= 0.25 for value in values):
            return f"front ToF threshold: {values}"
        return None

    def tick(self) -> None:
        now = time.monotonic()
        if self.phase == "WAIT":
            self.publish(0.0)
            if self.position is None or self.yaw is None or self.status is None:
                return
            blocked = self.safety_reason()
            if blocked:
                self.finish(f"ABORT: {blocked}")
                return
            self.start_position = self.position
            self.start_yaw = self.yaw
            self.started = now
            self.progress_time = now
            self.phase = "TURN"
            self.get_logger().info(
                f"START: speed={self.speed:.2f}m/s, angular={self.angular:.2f}rad/s, "
                f"travel={self.travel_limit:.2f}m, "
                f"yaw={self.yaw_target:.2f}rad, timeout={self.timeout:.1f}s"
            )
            return

        if self.phase == "TURN":
            blocked = self.safety_reason()
            if blocked:
                self.finish(
                    f"STOP: {blocked}, moved={self.moved():.3f}m, turned={self.turned():.3f}rad"
                )
                return
            if self.yaw_target > 0.0 and self.turned() >= self.yaw_target:
                self.finish(
                    f"COMPLETE: yaw target, moved={self.moved():.3f}m, turned={self.turned():.3f}rad"
                )
                return
            if self.moved() >= self.travel_limit:
                self.finish(
                    f"STOP: travel limit, moved={self.moved():.3f}m, turned={self.turned():.3f}rad"
                )
                return
            if self.timeout > 0.0 and now - self.started >= self.timeout:
                self.finish(
                    f"STOP: timeout, moved={self.moved():.3f}m, turned={self.turned():.3f}rad"
                )
                return
            moved = self.moved()
            if moved >= self.best_moved + 0.005:
                self.best_moved = moved
                self.progress_time = now
            if now - self.progress_time >= 3.0:
                self.finish(
                    f"STOP: no progress for 3s, moved={moved:.3f}m, "
                    f"turned={self.turned():.3f}rad"
                )
                return
            self.publish(self.speed, self.angular)
            return

        self.publish(0.0)
        self.stop_count += 1
        if self.stop_count >= 20:
            print(self.result, flush=True)
            rclpy.shutdown()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--speed", type=float, default=0.06)
    parser.add_argument("--angular", type=float, default=0.35)
    parser.add_argument("--travel-limit", type=float, default=0.25)
    parser.add_argument("--yaw-target", type=float, default=1.05)
    parser.add_argument("--timeout", type=float, default=5.0)
    args = parser.parse_args()
    if not 0.0 < args.speed <= 0.20:
        parser.error("speed must be in (0, 0.20]")
    if not 0.0 < abs(args.angular) <= 0.35:
        parser.error("absolute angular speed must be in (0, 0.35]")
    if not 0.0 < args.travel_limit <= 0.50:
        parser.error("travel-limit must be in (0, 0.50]")
    if not 0.0 <= args.yaw_target <= 1.05:
        parser.error("yaw-target must be in [0, 1.05]")
    if not 0.0 <= args.timeout <= 5.0:
        parser.error("timeout must be in [0, 5.0]")
    rclpy.init()
    node = LeftRecovery(
        args.speed, args.angular, args.travel_limit, args.yaw_target, args.timeout
    )
    try:
        rclpy.spin(node)
    finally:
        node.publish(0.0)
        node.destroy_node()
    return 0 if node.result.startswith("COMPLETE") else 2


if __name__ == "__main__":
    sys.exit(main())
