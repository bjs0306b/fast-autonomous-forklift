"""Decide which node is driving, so two of them never are at once.

Nav2 and the fork alignment servo both produce Twist commands, and until now
both wrote straight to /cmd_vel. Nothing arbitrated: whichever published last
won, at whatever rate it happened to run. That failure is silent -- no error,
no warning, the logs of both nodes look correct -- and the only symptom is a
vehicle that moves strangely. The same shape of bug cost hours on 2026-08-08
when three EKF instances published the same transform and the pose jumped 45
degrees between them.

Each source keeps its own topic and this node passes exactly one of them
through:

    nav2 velocity_smoother  -> /cmd_vel           \\
    fork align node         -> /cmd_vel_align     >-- drive_mux -> guard
    unstick node            -> /cmd_vel_recover  /

Nav2 is untouched. The guard's input_cmd_vel_topic points here instead of at
/cmd_vel, and everything downstream (guard, bridge, firmware watchdog) is
unchanged.

⚠️ **Default mode is NAV**, not idle. Idle would be the safer-looking choice
   and the wrong one: it would stop every existing workflow the moment this
   node is added, and a stack that will not move gets debugged by removing
   safety, not by adding it. NAV preserves what already works and makes
   handing the wheel to alignment an explicit act.

⚠️ **A stale mode does not fall back.** If whoever publishes the mode dies
   mid-insertion, dropping back to NAV would hand the wheel to a planner that
   thinks the pallet is an obstacle to route around, with the forks already
   inside it. The last mode stands; what expires is the *source*, and an
   expired source publishes zero.
"""

import time
from typing import Dict, Optional

from geometry_msgs.msg import Twist
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile
from std_msgs.msg import String

NAV = "NAV"
ALIGN = "ALIGN"
# Backing out of a corner the guard will not let the vehicle leave. Its own
# mode, not a special case inside NAV: while it holds the wheel nav2's
# commands must not reach the motor, or the two push against each other.
RECOVER = "RECOVER"
# 좁은 곳에서 방향만 바꾸는 제자리 전환. 전진·후진을 번갈아 하며 조향으로
# yaw 를 쌓는 동안 nav2 명령이 섞이면 두 쪽이 서로를 밀어낸다.
PIVOT = "PIVOT"
IDLE = "IDLE"


class DriveMux(Node):
    def __init__(self) -> None:
        super().__init__("drive_mux")

        self.declare_parameter("nav_topic", "/cmd_vel")
        self.declare_parameter("align_topic", "/cmd_vel_align")
        self.declare_parameter("recover_topic", "/cmd_vel_recover")
        self.declare_parameter("pivot_topic", "/cmd_vel_pivot")
        self.declare_parameter("output_topic", "/cmd_vel_arbitrated")
        self.declare_parameter("mode_topic", "/drive/mode")
        self.declare_parameter("status_topic", "/drive/mux_status")
        self.declare_parameter("publish_rate_hz", 20.0)
        # A source that has gone quiet must not keep the vehicle rolling on
        # its last command. Shorter than the bridge's own 500 ms watchdog so
        # this is what stops the vehicle, not the timeout.
        self.declare_parameter("source_timeout_sec", 0.3)

        self._sources: Dict[str, Optional[Twist]] = {
            NAV: None, ALIGN: None, RECOVER: None, PIVOT: None}
        self._seen: Dict[str, float] = {
            NAV: -1e9, ALIGN: -1e9, RECOVER: -1e9, PIVOT: -1e9}
        self._mode = NAV
        self._last_reported = ""

        self._timeout = float(self.get_parameter("source_timeout_sec").value)
        if self._timeout <= 0.0:
            raise ValueError("source_timeout_sec must be positive")

        self.create_subscription(
            Twist, str(self.get_parameter("nav_topic").value),
            lambda m: self._on_source(NAV, m), 10)
        self.create_subscription(
            Twist, str(self.get_parameter("align_topic").value),
            lambda m: self._on_source(ALIGN, m), 10)
        self.create_subscription(
            Twist, str(self.get_parameter("recover_topic").value),
            lambda m: self._on_source(RECOVER, m), 10)
        self.create_subscription(
            Twist, str(self.get_parameter("pivot_topic").value),
            lambda m: self._on_source(PIVOT, m), 10)

        # Latched: a node that starts late still learns who is driving. Without
        # this an alignment node coming up mid-mission would sit silent while
        # the mode it needed was published before it subscribed.
        latched = QoSProfile(depth=1)
        latched.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
        self.create_subscription(
            String, str(self.get_parameter("mode_topic").value),
            self._on_mode, latched)

        self._publisher = self.create_publisher(
            Twist, str(self.get_parameter("output_topic").value), 10)
        self._status = self.create_publisher(
            String, str(self.get_parameter("status_topic").value), latched)

        rate = float(self.get_parameter("publish_rate_hz").value)
        if rate <= 0.0:
            raise ValueError("publish_rate_hz must be positive")
        self.create_timer(1.0 / rate, self._on_timer)

        self.get_logger().info(
            f"Drive mux ready: {self.get_parameter('nav_topic').value} / "
            f"{self.get_parameter('align_topic').value} -> "
            f"{self.get_parameter('output_topic').value}, mode={self._mode}"
        )

    def _on_source(self, name: str, message: Twist) -> None:
        self._sources[name] = message
        self._seen[name] = time.monotonic()

    def _on_mode(self, message: String) -> None:
        requested = message.data.strip().upper()
        if requested not in (NAV, ALIGN, RECOVER, PIVOT, IDLE):
            self.get_logger().warn(
                f"Unknown drive mode '{message.data}', staying in {self._mode}"
            )
            return
        if requested != self._mode:
            # Loud on purpose. Which node holds the wheel is the first thing
            # to know when the vehicle does something unexpected.
            self.get_logger().info(f"Drive mode {self._mode} -> {requested}")
            self._mode = requested

    def _on_timer(self) -> None:
        command, reason = self._select()
        self._publisher.publish(command)
        if reason != self._last_reported:
            self._last_reported = reason
            self._status.publish(String(data=reason))

    def _select(self):
        if self._mode == IDLE:
            return Twist(), "IDLE: nobody is driving"
        command = self._sources.get(self._mode)
        if command is None:
            return Twist(), f"{self._mode}: no command yet"
        age = time.monotonic() - self._seen[self._mode]
        if age > self._timeout:
            return Twist(), f"{self._mode}: stale by {age:.2f}s"
        return command, f"{self._mode}: driving"


def main(args=None) -> None:
    rclpy.init(args=args)
    node = DriveMux()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
