"""Publish sensor_msgs/Imu from the ESP32 USB telemetry uplink.

This is a separate node from ``uart_teleop_bridge`` on purpose: the command
link is a different device file (``/dev/ttyTHS1`` versus ``/dev/ttyACM0``), so
keeping them apart means telemetry cannot delay commands and the working
tele-operation path stays untouched.
"""

import math
import time
from typing import Optional

import rclpy
from rclpy.node import Node
from sensor_msgs.msg import Imu
import serial

from forklift_teleop.sensor_protocol import (
    ClockOffsetTracker,
    ImuFrame,
    ImuStatusFrame,
    parse_sensor_line,
)

MDPS_TO_RAD_PER_SEC = (math.pi / 180.0) / 1000.0

# ROS convention for "this field is not measured".
ORIENTATION_UNUSED = -1.0

# Large but finite: the EKF must ignore these axes without treating the
# covariance as degenerate.
UNTRUSTED_VARIANCE = 1e6


class ImuBridge(Node):
    def __init__(self) -> None:
        super().__init__("imu_bridge")

        self.declare_parameter("serial_port", "/dev/ttyACM0")
        self.declare_parameter("baud_rate", 115200)
        self.declare_parameter("imu_topic", "/imu/data")
        self.declare_parameter("imu_frame_id", "imu_link")
        self.declare_parameter("read_rate_hz", 200.0)
        self.declare_parameter("gyro_z_sign", 1)
        self.declare_parameter("yaw_rate_stddev", 0.02)
        self.declare_parameter("status_log_period_sec", 10.0)

        self._serial_port = str(self.get_parameter("serial_port").value)
        self._baud_rate = int(self.get_parameter("baud_rate").value)
        self._frame_id = str(self.get_parameter("imu_frame_id").value)
        read_rate_hz = float(self.get_parameter("read_rate_hz").value)
        self._gyro_z_sign = int(self.get_parameter("gyro_z_sign").value)
        yaw_rate_stddev = float(self.get_parameter("yaw_rate_stddev").value)
        self._status_log_period = float(
            self.get_parameter("status_log_period_sec").value
        )

        if read_rate_hz <= 0.0:
            raise ValueError("read_rate_hz must be positive")
        if self._gyro_z_sign not in (-1, 1):
            raise ValueError("gyro_z_sign must be -1 or 1")
        if yaw_rate_stddev <= 0.0:
            # A zero variance tells the EKF this sensor is perfect, and it then
            # ignores the laser odometry entirely.
            raise ValueError("yaw_rate_stddev must be positive")

        self._yaw_rate_variance = yaw_rate_stddev**2

        self._serial: Optional[serial.Serial] = None
        self._next_reconnect_time = 0.0
        self._rx_buffer = bytearray()
        self._imu_clock = ClockOffsetTracker()
        self._expected_sequence: Optional[int] = None
        self._parse_error_count = 0
        self._gap_count = 0
        self._last_status_log_time = 0.0

        imu_topic = str(self.get_parameter("imu_topic").value)
        self._publisher = self.create_publisher(Imu, imu_topic, 10)
        self._timer = self.create_timer(1.0 / read_rate_hz, self._on_timer)
        self.get_logger().info(
            f"IMU bridge ready: topic={imu_topic}, "
            f"port={self._serial_port}, frame={self._frame_id}, "
            f"gyro_z_sign={self._gyro_z_sign}"
        )

    def _ensure_serial(self, now: float) -> bool:
        if self._serial is not None and self._serial.is_open:
            return True
        if now < self._next_reconnect_time:
            return False

        try:
            # The ESP32-S3 USB Serial/JTAG peripheral maps DTR/RTS onto reset
            # and download-mode strapping, so both must be deasserted before
            # the port is opened or the MCU reboots on every connect.
            port = serial.Serial()
            port.port = self._serial_port
            port.baudrate = self._baud_rate
            port.bytesize = serial.EIGHTBITS
            port.parity = serial.PARITY_NONE
            port.stopbits = serial.STOPBITS_ONE
            port.timeout = 0
            port.dtr = False
            port.rts = False
            port.open()
        except (serial.SerialException, OSError) as error:
            self._serial = None
            self._next_reconnect_time = now + 1.0
            self.get_logger().warning(f"USB open failed: {error}")
            return False

        self._serial = port
        self._rx_buffer.clear()
        self.get_logger().info(f"Opened USB uplink {self._serial_port}")
        return True

    def _close_serial(self) -> None:
        if self._serial is not None:
            try:
                self._serial.close()
            except (serial.SerialException, OSError):
                pass
        self._serial = None

    def _publish_imu(self, frame: ImuFrame) -> None:
        host_time_ns = self.get_clock().now().nanoseconds
        stamp_ns = self._imu_clock.stamp_ns(frame.mcu_time_us, host_time_ns)

        message = Imu()
        message.header.stamp.sec = stamp_ns // 1_000_000_000
        message.header.stamp.nanosec = stamp_ns % 1_000_000_000
        message.header.frame_id = self._frame_id

        # Orientation is never estimated here; absolute heading is AMCL's job.
        message.orientation_covariance[0] = ORIENTATION_UNUSED

        message.angular_velocity.z = (
            self._gyro_z_sign * frame.gyro_z_mdps * MDPS_TO_RAD_PER_SEC
        )
        message.angular_velocity_covariance[0] = UNTRUSTED_VARIANCE
        message.angular_velocity_covariance[4] = UNTRUSTED_VARIANCE
        message.angular_velocity_covariance[8] = self._yaw_rate_variance

        # Motor and stepper vibration dominates the accelerometer, and rf2o
        # gives better translational velocity, so it is not published.
        message.linear_acceleration_covariance[0] = UNTRUSTED_VARIANCE
        message.linear_acceleration_covariance[4] = UNTRUSTED_VARIANCE
        message.linear_acceleration_covariance[8] = UNTRUSTED_VARIANCE

        self._publisher.publish(message)

    def _check_sequence(self, sequence: int) -> None:
        if self._expected_sequence is not None:
            missing = (sequence - self._expected_sequence) & 0xFFFFFFFF
            if missing != 0:
                self._gap_count += missing
                self.get_logger().warning(
                    f"Dropped {missing} IMU sample(s) before "
                    f"sequence {sequence}"
                )
        self._expected_sequence = (sequence + 1) & 0xFFFFFFFF

    def _log_status(self, status: ImuStatusFrame, now: float) -> None:
        if now - self._last_status_log_time < self._status_log_period:
            return

        bias_rad_per_sec = status.bias_mdps * MDPS_TO_RAD_PER_SEC
        self.get_logger().info(
            f"IMU status: who_am_i=0x{status.who_am_i:02X}, "
            f"bias={bias_rad_per_sec:+.5f} rad/s, idle={status.idle}, "
            f"mcu_dropped={status.dropped}, host_gaps={self._gap_count}, "
            f"parse_errors={self._parse_error_count}, "
            f"clock_resets={self._imu_clock.reset_count}"
        )
        self._last_status_log_time = now

    def _handle_line(self, line: bytes, now: float) -> None:
        try:
            frame = parse_sensor_line(line)
        except ValueError as error:
            self._parse_error_count += 1
            self.get_logger().debug(f"Invalid sensor frame: {error}")
            return

        if isinstance(frame, ImuFrame):
            self._check_sequence(frame.sequence)
            self._publish_imu(frame)
        elif isinstance(frame, ImuStatusFrame):
            self._log_status(frame, now)

    def _on_timer(self) -> None:
        now = time.monotonic()
        if not self._ensure_serial(now):
            return

        assert self._serial is not None
        try:
            waiting = self._serial.in_waiting
            if waiting > 0:
                self._rx_buffer.extend(self._serial.read(waiting))
        except (serial.SerialException, OSError) as error:
            self.get_logger().error(f"USB read failed: {error}")
            self._close_serial()
            self._next_reconnect_time = now + 1.0
            return

        while b"\n" in self._rx_buffer:
            line, _, remainder = self._rx_buffer.partition(b"\n")
            self._rx_buffer = bytearray(remainder)
            self._handle_line(bytes(line), now)

    def destroy_node(self) -> bool:
        self._close_serial()
        return super().destroy_node()


def main(args=None) -> None:
    rclpy.init(args=args)
    node = ImuBridge()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
