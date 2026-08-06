"""ROS2 /cmd_vel to ESP32 UART bridge."""

from collections import deque
import json
import time
from typing import Optional

import rclpy
from geometry_msgs.msg import Twist
from rclpy.node import Node
import serial
from std_msgs.msg import String

from forklift_teleop.mapping import TeleopLimits, map_twist, select_command
from forklift_teleop.protocol import (
    encode_command,
    encode_lift_command,
    next_sequence,
    parse_ack,
    parse_lift_status,
)


class UartTeleopBridge(Node):
    def __init__(self) -> None:
        super().__init__("uart_teleop_bridge")

        self.declare_parameter("cmd_vel_topic", "/cmd_vel")
        self.declare_parameter("fork_command_topic", "/fork/command")
        self.declare_parameter("fork_status_topic", "/fork/status")
        self.declare_parameter("serial_port", "/dev/ttyTHS1")
        self.declare_parameter("baud_rate", 115200)
        self.declare_parameter("command_rate_hz", 20.0)
        self.declare_parameter("command_timeout_sec", 0.5)
        # ⚠️ **기본값을 여기 다시 적지 않는다.** `TeleopLimits` 하나만 본다.
        #
        # 2026-08-05 까지 여기에 10000/8500/11500 · ±15° · drive 50 이 박혀 있었다.
        # 실제 설정이 197·198·152 로 세 번 바뀌는 동안 한 번도 안 따라왔다. 브리지가
        # 항상 teleop.yaml 을 넘기니 동작에는 영향이 없어서 아무도 안 봤다 — 그러다
        # yaml 에서 한 줄이라도 빠지면 **그 순간 조용히 옛 값으로 돌아간다.**
        # 같은 값을 두 곳에 적어두면 언젠가 반드시 갈라진다.
        _d = TeleopLimits()
        self.declare_parameter("max_linear_mps", _d.max_linear_mps)
        self.declare_parameter("max_angular_rps", _d.max_angular_rps)
        self.declare_parameter("linear_deadband_mps", _d.linear_deadband_mps)
        self.declare_parameter("wheelbase_m", _d.wheelbase_m)
        self.declare_parameter("rear_steering_limit_deg",
                               _d.rear_steering_limit_deg)
        self.declare_parameter("min_drive_percent", _d.min_drive_percent)
        self.declare_parameter("max_drive_percent", _d.max_drive_percent)
        self.declare_parameter("max_drive_percent_reverse",
                               _d.max_drive_percent_reverse)
        self.declare_parameter("steering_center_cdeg", _d.steering_center_cdeg)
        self.declare_parameter("steering_min_cdeg", _d.steering_min_cdeg)
        self.declare_parameter("steering_max_cdeg", _d.steering_max_cdeg)

        self._serial_port = str(self.get_parameter("serial_port").value)
        self._baud_rate = int(self.get_parameter("baud_rate").value)
        command_rate_hz = float(self.get_parameter("command_rate_hz").value)
        self._command_timeout_sec = float(
            self.get_parameter("command_timeout_sec").value
        )
        if command_rate_hz <= 0.0 or self._command_timeout_sec <= 0.0:
            raise ValueError("command rate and timeout must be positive")

        self._limits = TeleopLimits(
            max_linear_mps=float(self.get_parameter("max_linear_mps").value),
            max_angular_rps=float(self.get_parameter("max_angular_rps").value),
            linear_deadband_mps=float(
                self.get_parameter("linear_deadband_mps").value
            ),
            wheelbase_m=float(
                self.get_parameter("wheelbase_m").value
            ),
            rear_steering_limit_deg=float(
                self.get_parameter("rear_steering_limit_deg").value
            ),
            min_drive_percent=int(
                self.get_parameter("min_drive_percent").value
            ),
            max_drive_percent_reverse=int(
                self.get_parameter("max_drive_percent_reverse").value
            ),
            max_drive_percent=int(
                self.get_parameter("max_drive_percent").value
            ),
            steering_center_cdeg=int(
                self.get_parameter("steering_center_cdeg").value
            ),
            steering_min_cdeg=int(
                self.get_parameter("steering_min_cdeg").value
            ),
            steering_max_cdeg=int(
                self.get_parameter("steering_max_cdeg").value
            ),
        )
        self._limits.validate()

        self._serial: Optional[serial.Serial] = None
        self._next_reconnect_time = 0.0
        self._rx_buffer = bytearray()
        self._sequence = 0
        self._last_twist: Optional[Twist] = None
        self._last_twist_time: Optional[float] = None
        self._pending_lift_commands = deque(maxlen=8)
        self._initializing_fork = False

        cmd_vel_topic = str(self.get_parameter("cmd_vel_topic").value)
        self._subscription = self.create_subscription(
            Twist,
            cmd_vel_topic,
            self._on_twist,
            1,
        )
        fork_command_topic = str(
            self.get_parameter("fork_command_topic").value
        )
        fork_status_topic = str(
            self.get_parameter("fork_status_topic").value
        )
        self._fork_subscription = self.create_subscription(
            String,
            fork_command_topic,
            self._on_fork_command,
            1,
        )
        self._fork_status_publisher = self.create_publisher(
            String,
            fork_status_topic,
            10,
        )
        self._timer = self.create_timer(1.0 / command_rate_hz, self._on_timer)
        self.get_logger().info(
            f"UART teleop ready: topic={cmd_vel_topic}, "
            f"fork_command={fork_command_topic}, "
            f"fork_status={fork_status_topic}, "
            f"port={self._serial_port}, baud={self._baud_rate}"
        )

    def _on_twist(self, message: Twist) -> None:
        self._last_twist = message
        self._last_twist_time = time.monotonic()

    def _on_fork_command(self, message: String) -> None:
        action = message.data.strip().upper()
        if action not in {"UP", "DOWN", "HOME", "INITIALIZE", "STOP"}:
            self.get_logger().warning(
                f"Rejected fork command {message.data!r}; "
                "expected UP, DOWN, HOME, INITIALIZE, or STOP"
            )
            return
        if len(self._pending_lift_commands) == self._pending_lift_commands.maxlen:
            self.get_logger().warning("Fork command queue is full")
            return
        self._pending_lift_commands.append(action)
        if action == "INITIALIZE":
            self._initializing_fork = True

    def _ensure_serial(self, now: float) -> bool:
        if self._serial is not None and self._serial.is_open:
            return True
        if now < self._next_reconnect_time:
            return False

        try:
            self._serial = serial.Serial(
                port=self._serial_port,
                baudrate=self._baud_rate,
                bytesize=serial.EIGHTBITS,
                parity=serial.PARITY_NONE,
                stopbits=serial.STOPBITS_ONE,
                timeout=0,
                write_timeout=0.1,
            )
            self.get_logger().info(f"Opened UART {self._serial_port}")
            return True
        except serial.SerialException as error:
            self._serial = None
            self._next_reconnect_time = now + 1.0
            self.get_logger().warning(f"UART open failed: {error}")
            return False

    def _close_serial(self) -> None:
        if self._serial is not None:
            try:
                self._serial.close()
            except serial.SerialException:
                pass
        self._serial = None

    def _current_command(self, now: float):
        if self._initializing_fork:
            return map_twist(0.0, 0.0, self._limits)
        if (
            self._last_twist is None
            or self._last_twist_time is None
        ):
            return map_twist(0.0, 0.0, self._limits)
        return select_command(
            self._last_twist.linear.x,
            self._last_twist.angular.z,
            now - self._last_twist_time,
            self._command_timeout_sec,
            self._limits,
        )

    def _read_uart(self) -> None:
        if self._serial is None:
            return
        waiting = self._serial.in_waiting
        if waiting > 0:
            self._rx_buffer.extend(self._serial.read(waiting))

        while b"\n" in self._rx_buffer:
            line, _, remainder = self._rx_buffer.partition(b"\n")
            self._rx_buffer = bytearray(remainder)
            try:
                framed_line = line + b"\n"
                if line.startswith(b"@LIFT_STATUS,"):
                    status = parse_lift_status(framed_line)
                    message = String()
                    message.data = json.dumps(
                        {
                            "sequence": status.sequence,
                            "state": status.state,
                            "completed_steps": status.completed_steps,
                            "total_steps": status.total_steps,
                            "lower_limit_active": (
                                status.lower_limit_active
                            ),
                        },
                        separators=(",", ":"),
                    )
                    self._fork_status_publisher.publish(message)
                    if (
                        self._initializing_fork
                        and status.state in {"DONE", "ERROR"}
                    ):
                        self._initializing_fork = False
                    self.get_logger().info(
                        "Fork status: "
                        f"sequence={status.sequence}, "
                        f"state={status.state}, "
                        f"steps={status.completed_steps}/"
                        f"{status.total_steps}, "
                        f"lower_limit={status.lower_limit_active}"
                    )
                else:
                    ack = parse_ack(framed_line)
                    log = (
                        self.get_logger().debug
                        if ack.status == "OK"
                        else self.get_logger().warning
                    )
                    log(
                        f"ACK sequence={ack.sequence} "
                        f"status={ack.status}"
                    )
            except ValueError as error:
                self.get_logger().warning(f"Invalid UART frame: {error}")

    def _on_timer(self) -> None:
        now = time.monotonic()
        if not self._ensure_serial(now):
            return

        command = self._current_command(now)

        try:
            assert self._serial is not None
            if self._pending_lift_commands:
                lift_action = self._pending_lift_commands.popleft()
                self._serial.write(
                    encode_lift_command(self._sequence, lift_action)
                )
                self.get_logger().info(
                    f"Sent fork command: sequence={self._sequence}, "
                    f"action={lift_action}"
                )
                self._sequence = next_sequence(self._sequence)
            self._serial.write(
                encode_command(
                    self._sequence,
                    command.drive_percent,
                    command.steering_cdeg,
                )
            )
            self._read_uart()
            self._sequence = next_sequence(self._sequence)
        except (serial.SerialException, OSError) as error:
            self.get_logger().error(f"UART communication failed: {error}")
            self._close_serial()
            self._next_reconnect_time = now + 1.0

    def destroy_node(self) -> bool:
        if self._serial is not None and self._serial.is_open:
            try:
                stop = encode_command(
                    self._sequence,
                    0,
                    self._limits.steering_center_cdeg,
                )
                self._serial.write(stop)
                self._sequence = next_sequence(self._sequence)
                self._serial.write(
                    encode_lift_command(self._sequence, "STOP")
                )
                self._serial.flush()
            except (serial.SerialException, OSError):
                pass
        self._close_serial()
        return super().destroy_node()


def main(args=None) -> None:
    rclpy.init(args=args)
    node = UartTeleopBridge()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
