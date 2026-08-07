"""Publish IMU and front ToF data from the ESP32 USB telemetry uplink.

One node rather than several because ``/dev/ttyACM0`` can only be opened by a
single process, and every sensor frame arrives interleaved on that one stream.

This stays separate from ``uart_teleop_bridge`` on purpose: the command link is
a different device file (``/dev/ttyTHS1``), so telemetry cannot delay commands
and the working tele-operation path is untouched.
"""

import collections
import math
import struct
import threading
import time
from typing import Optional

import rclpy
from rcl_interfaces.msg import ParameterDescriptor
from rclpy.node import Node
from geometry_msgs.msg import TwistWithCovarianceStamped
from sensor_msgs.msg import Imu, PointCloud2, PointField
import serial

from forklift_teleop.sensor_protocol import (
    TOF_STATUS_VALID,
    TOF_STATUS_VALID_LOW_CONFIDENCE,
    TOF_ZONE_COUNT,
    ClockOffsetTracker,
    EncoderFrame,
    ImuFrame,
    ImuStatusFrame,
    TofFrame,
    TofStatusFrame,
    parse_sensor_line,
)

MDPS_TO_RAD_PER_SEC = (math.pi / 180.0) / 1000.0
ENCODER_NOMINAL_RATE_HZ = 50.0
# Large but finite: zero on an unmeasured axis reads as a perfect measurement
# of no motion and would pin the filter to it.
TOF_UNUSED_VARIANCE = 1e6

# ROS convention for "this field is not measured".
ORIENTATION_UNUSED = -1.0

# Large but finite: the EKF must ignore these axes without treating the
# covariance as degenerate.
UNTRUSTED_VARIANCE = 1e6

# How long the reader parks when the port has nothing. Short enough that the
# kernel tty buffer never builds up at the 13 kB/s this link carries.
READER_IDLE_SLEEP_SEC = 0.002

TOF_GRID_SIDE = 8
TOF_SENSOR_NAMES = ("left", "right")


def _cloud(stamp_ns: int, frame_id: str, payload: bytes, count: int
           ) -> PointCloud2:
    message = PointCloud2()
    message.header.stamp.sec = stamp_ns // 1_000_000_000
    message.header.stamp.nanosec = stamp_ns % 1_000_000_000
    message.header.frame_id = frame_id
    # Unordered: invalid zones are dropped rather than filled with NaN, so the
    # costmap never sees a phantom return.
    message.height = 1
    message.width = count
    message.fields = [
        PointField(name="x", offset=0, datatype=PointField.FLOAT32, count=1),
        PointField(name="y", offset=4, datatype=PointField.FLOAT32, count=1),
        PointField(name="z", offset=8, datatype=PointField.FLOAT32, count=1),
    ]
    message.is_bigendian = False
    message.point_step = 12
    message.row_step = 12 * count
    message.data = bytes(payload)
    message.is_dense = True
    return message


class SensorBridge(Node):
    def __init__(self) -> None:
        super().__init__("sensor_bridge")

        self.declare_parameter("serial_port", "/dev/ttyACM0")
        self.declare_parameter("baud_rate", 115200)
        self.declare_parameter("imu_topic", "/imu/data")
        self.declare_parameter("imu_frame_id", "imu_link")
        self.declare_parameter("read_rate_hz", 200.0)
        self.declare_parameter("gyro_z_sign", 1)
        self.declare_parameter("yaw_rate_stddev", 0.02)
        self.declare_parameter("status_log_period_sec", 10.0)

        self.declare_parameter("tof_enabled", True)
        self.declare_parameter("tof_topics", ["/tof/left/points",
                                              "/tof/right/points"])
        self.declare_parameter("tof_frame_ids", ["tof_left_link",
                                                 "tof_right_link"])
        # Square field of view in degrees, spread over the 8x8 grid. Check this
        # against the datasheet: get it wrong and every bearing is wrong.
        self.declare_parameter("tof_fov_deg", 45.0)
        # Which way the zone grid runs relative to the sensor frame. A mirrored
        # column or row order is a reflection, not a rotation, so unlike a
        # mounting offset it cannot be corrected by the static transform.
        # Verify with an object at a known bearing before trusting the cloud.
        self.declare_parameter("tof_azimuth_sign", 1)
        self.declare_parameter("tof_elevation_sign", 1)
        # True when the board is turned 90 degrees about its optical axis, so
        # the grid rows carry bearing and the columns carry height.
        self.declare_parameter("tof_transpose_zones", False)
        self.declare_parameter("tof_accept_low_confidence", False)
        self.declare_parameter("tof_min_range_m", 0.02)
        self.declare_parameter("tof_max_range_m", 3.5)
        # Mount height above the floor, per sensor, in metres. The sensors are
        # mounted level, so a point below -(height - margin) in the sensor frame
        # is the floor: no transform lookup is needed to recognise it.
        self.declare_parameter("tof_mount_height_m", [0.07, 0.07])
        self.declare_parameter("tof_ground_margin_m", 0.02)
        self.declare_parameter("tof_pitch_deg", [0.0, 0.0])
        # A zone must repeat before it is believed. Set hits to 1 to disable.
        self.declare_parameter("tof_persistence_frames", 3)
        self.declare_parameter("tof_persistence_hits", 2)
        self.declare_parameter("tof_persistence_tolerance_m", 0.05)
        # Range at which a zone that keeps reporting nothing is emitted as a
        # clearing ray. 0 disables. See _publish_tof for why this must sit
        # between the costmap's obstacle_max_range and raytrace_max_range.
        self.declare_parameter("tof_clear_range_m", 2.2)
        self.declare_parameter("tof_clear_persistence_frames", 3)
        self.declare_parameter("tof_empty_statuses", [0, 255])

        self.declare_parameter("encoder_enabled", True)
        self.declare_parameter("encoder_topic", "/wheel/twist")
        self.declare_parameter("encoder_frame_id", "base_link")
        self.declare_parameter("wheel_diameter_m", 0.060)
        # Measured, never taken from the datasheet: the JGA25-370 name covers
        # several gear ratios, and a wrong one scales every distance without
        # looking wrong. See tools/encoder_scale_check.py.
        self.declare_parameter("encoder_counts_per_wheel_rev", 0.0)
        self.declare_parameter("encoder_velocity_stddev", 0.05)
        # Turn off to see the floor returns themselves, which is how the
        # elevation sign is checked: the floor is a target of known size,
        # position and reflectance that needs no stand.
        self.declare_parameter("tof_ground_filter", True)
        # Zones that always see the vehicle's own forks or chassis. They read a
        # fixed short distance forever, so they are dropped by index rather than
        # by range. Learn them with tools/tof_mask_learn.py.
        # An empty list carries no type for rclpy to infer, so both the default
        # and any YAML override of `[]` would leave the parameter uninitialised.
        # Dynamic typing accepts it; the value then arrives as None until the
        # list is actually populated, which _zone_set below absorbs.
        empty_list_ok = ParameterDescriptor(dynamic_typing=True)
        self.declare_parameter("tof_masked_zones_left", [], empty_list_ok)
        self.declare_parameter("tof_masked_zones_right", [], empty_list_ok)

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
        # Byte capture runs on its own thread. Doing it from the ROS timer made
        # capture hostage to executor scheduling: the MCU reported
        # mcu_dropped=0 while the host still saw sequence gaps, and the loss
        # varied run to run with no change in load. A blocking read on a
        # dedicated thread is what the bring-up tool does, and it loses nothing.
        self._line_queue = collections.deque(maxlen=4096)
        self._reader_stop = threading.Event()
        self._reader_thread: Optional[threading.Thread] = None
        self._imu_clock = ClockOffsetTracker()
        self._expected_sequence: Optional[int] = None
        self._parse_error_count = 0
        self._gap_count = 0
        self._last_status_log_time = 0.0

        imu_topic = str(self.get_parameter("imu_topic").value)
        self._publisher = self.create_publisher(Imu, imu_topic, 10)

        self._tof_enabled = bool(self.get_parameter("tof_enabled").value)
        self._tof_frame_ids = list(
            self.get_parameter("tof_frame_ids").value
        )
        self._tof_min_range = float(
            self.get_parameter("tof_min_range_m").value
        )
        self._tof_max_range = float(
            self.get_parameter("tof_max_range_m").value
        )
        self._tof_accepted_status = {TOF_STATUS_VALID}
        if bool(self.get_parameter("tof_accept_low_confidence").value):
            self._tof_accepted_status.add(TOF_STATUS_VALID_LOW_CONFIDENCE)

        azimuth_sign = int(self.get_parameter("tof_azimuth_sign").value)
        elevation_sign = int(self.get_parameter("tof_elevation_sign").value)
        if azimuth_sign not in (-1, 1) or elevation_sign not in (-1, 1):
            raise ValueError("ToF axis signs must be -1 or 1")

        self._tof_directions = self._build_zone_directions(
            float(self.get_parameter("tof_fov_deg").value),
            azimuth_sign,
            elevation_sign,
            bool(self.get_parameter("tof_transpose_zones").value),
        )
        self._tof_mount_height = [
            float(value)
            for value in self.get_parameter("tof_mount_height_m").value
        ]
        self._tof_ground_margin = float(
            self.get_parameter("tof_ground_margin_m").value
        )
        # Measured, not designed: the mounts cannot be adjusted, so the tilt is
        # carried here and in the static TF instead of being taken out of the
        # bracket. Fit it from the floor rows -- see procedure D.
        self._tof_pitch_terms = [
            (math.sin(math.radians(float(value))),
             math.cos(math.radians(float(value))))
            for value in self.get_parameter("tof_pitch_deg").value
        ]
        self._tof_ground_filter = bool(
            self.get_parameter("tof_ground_filter").value
        )
        self._tof_masked_zones = [
            self._zone_set("tof_masked_zones_left"),
            self._zone_set("tof_masked_zones_right"),
        ]
        self._tof_persistence_frames = max(
            1, int(self.get_parameter("tof_persistence_frames").value)
        )
        self._tof_persistence_hits = max(
            1, int(self.get_parameter("tof_persistence_hits").value)
        )
        self._tof_persistence_tolerance = float(
            self.get_parameter("tof_persistence_tolerance_m").value
        )
        self._tof_history = [
            collections.deque(maxlen=self._tof_persistence_frames)
            for _ in range(2)
        ]
        self._tof_clear_range = float(
            self.get_parameter("tof_clear_range_m").value
        )
        self._tof_clear_persistence = max(
            1, int(self.get_parameter("tof_clear_persistence_frames").value)
        )
        self._tof_empty_statuses = {
            int(value) for value in
            self.get_parameter("tof_empty_statuses").value
        }
        self._tof_empty_streak = [
            [0] * TOF_ZONE_COUNT for _ in range(2)
        ]
        self._tof_cleared = [0, 0]
        self._tof_flicker_rejected = [0, 0]
        self._tof_ground_rejected = [0, 0]
        self._tof_status_seen = {}

        self._encoder_enabled = bool(
            self.get_parameter("encoder_enabled").value
        )
        self._encoder_frame_id = str(
            self.get_parameter("encoder_frame_id").value
        )
        self._encoder_velocity_stddev = float(
            self.get_parameter("encoder_velocity_stddev").value
        )
        self._encoder_unused_variance = TOF_UNUSED_VARIANCE
        self._encoder_clock = ClockOffsetTracker()
        self._encoder_previous = None
        # Half the nominal period: shorter than that and the division amplifies
        # timestamp noise more than the reading is worth.
        self._encoder_min_interval = 0.5 / ENCODER_NOMINAL_RATE_HZ

        counts_per_rev = float(
            self.get_parameter("encoder_counts_per_wheel_rev").value
        )
        wheel_diameter = float(self.get_parameter("wheel_diameter_m").value)
        if self._encoder_enabled and counts_per_rev <= 0.0:
            self._encoder_enabled = False
            self.get_logger().warn(
                "encoder_counts_per_wheel_rev is unset, so wheel velocity is "
                "not published. Measure it with tools/encoder_scale_check.py"
            )
        self._encoder_metres_per_count = (
            math.pi * wheel_diameter / counts_per_rev
            if counts_per_rev > 0.0 else 0.0
        )
        self._encoder_publisher = self.create_publisher(
            TwistWithCovarianceStamped,
            str(self.get_parameter("encoder_topic").value),
            10,
        )

        self._tof_publishers = []
        self._tof_clear_publishers = []
        self._tof_clocks = []
        if self._tof_enabled:
            for topic in self.get_parameter("tof_topics").value:
                self._tof_publishers.append(
                    self.create_publisher(PointCloud2, str(topic), 5)
                )
                # Clearing rays go out on their own topic so nav2 can take
                # them through a marking:false source. That is what lets the
                # layer accept every row: a source that can never mark needs
                # no height window narrow enough to keep phantoms out.
                self._tof_clear_publishers.append(
                    self.create_publisher(PointCloud2, f"{topic}_clear", 5)
                )
                self._tof_clocks.append(ClockOffsetTracker())

        self._timer = self.create_timer(1.0 / read_rate_hz, self._on_timer)
        self.get_logger().info(
            f"Sensor bridge ready: imu={imu_topic}, "
            f"port={self._serial_port}, frame={self._frame_id}, "
            f"gyro_z_sign={self._gyro_z_sign}, "
            f"tof={'on' if self._tof_enabled else 'off'}"
        )

    def _zone_set(self, name: str) -> set:
        """Read a masked-zone list, tolerating the unset and empty cases."""
        value = self.get_parameter(name).value
        return {int(zone) for zone in value} if value else set()

    @staticmethod
    def _build_zone_directions(
        fov_deg: float,
        azimuth_sign: int = 1,
        elevation_sign: int = 1,
        transpose: bool = False,
    ) -> list:
        """Unit vector per zone, precomputed once.

        Zone spacing is the field of view divided across the grid, measured
        from the centre of the array. With both signs positive and no
        transpose, column 0 sits at +Y and row 0 at +Z. The sensor frame points
        +X forward, +Y left, +Z up, matching the vehicle convention used for
        the lidar transform.

        The field of view is square, so turning the sensor 90 degrees about its
        optical axis covers exactly the same space -- but it swaps which grid
        axis carries bearing and which carries height. That is a transpose, not
        a sign flip, so the signs alone cannot express it and `transpose`
        exists to let the mechanics mount the board whichever way fits.
        """
        step = math.radians(fov_deg) / TOF_GRID_SIDE
        centre = (TOF_GRID_SIDE - 1) / 2.0
        directions = []
        for row in range(TOF_GRID_SIDE):
            for column in range(TOF_GRID_SIDE):
                across, down = (row, column) if transpose else (column, row)
                azimuth = azimuth_sign * (centre - across) * step
                elevation = elevation_sign * (centre - down) * step
                directions.append((
                    math.cos(elevation) * math.cos(azimuth),
                    math.cos(elevation) * math.sin(azimuth),
                    math.sin(elevation),
                ))
        return directions

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

    def _publish_tof(self, frame: TofFrame) -> None:
        if frame.sensor_id >= len(self._tof_publishers):
            self.get_logger().warning(
                f"Unknown ToF sensor id {frame.sensor_id}"
            )
            return

        host_time_ns = self.get_clock().now().nanoseconds
        stamp_ns = self._tof_clocks[frame.sensor_id].stamp_ns(
            frame.mcu_time_us, host_time_ns
        )

        masked = self._tof_masked_zones[frame.sensor_id]
        mount_height = self._tof_mount_height[frame.sensor_id]
        pitch_sin, pitch_cos = self._tof_pitch_terms[frame.sensor_id]

        accepted = {}
        for zone in range(TOF_ZONE_COUNT):
            if zone in masked:
                continue

            if frame.status[zone] not in self._tof_accepted_status:
                continue

            distance = frame.distance_mm[zone] / 1000.0
            if not self._tof_min_range <= distance <= self._tof_max_range:
                continue

            direction = self._tof_directions[zone]
            x = direction[0] * distance
            z = direction[2] * distance
            # Height above the floor. A level sensor makes this mount_height +
            # z, but a sensor pitched down by theta tilts the floor across the
            # frame, and then the near-axis rows -- which see the floor
            # furthest away -- come out too high to reject and become a phantom
            # obstacle a metre ahead. Rotating into the world frame first costs
            # one multiply and holds for any mounting angle.
            if self._tof_ground_filter:
                height = mount_height - x * pitch_sin + z * pitch_cos
                if height <= self._tof_ground_margin:
                    self._tof_ground_rejected[frame.sensor_id] += 1
                    continue

            accepted[zone] = (distance, x, direction[1] * distance, z)

        # A zone has to come back before it is believed. A fabric floor
        # scatters weakly at grazing angles and drops single frames into the
        # cloud at no fixed place, so masking cannot reach them -- and with
        # clearing disabled on this source, one that reaches the costmap stays
        # there. Waiting for a repeat costs one frame of latency, 67 ms, which
        # is 2 cm at navigation speed.
        history = self._tof_history[frame.sensor_id]
        points = bytearray()
        valid = 0
        for zone, (distance, x, y, z) in accepted.items():
            hits = 1 + sum(
                1 for older in history
                if abs(older.get(zone, math.inf) - distance)
                <= self._tof_persistence_tolerance
            )
            if hits < self._tof_persistence_hits:
                self._tof_flicker_rejected[frame.sensor_id] += 1
                continue
            points += struct.pack("<fff", x, y, z)
            valid += 1

        history.append({
            zone: values[0] for zone, values in accepted.items()
        })

        # Raytrace clearing only erases cells a returning ray passes through,
        # so a direction that stops returning anything keeps whatever it
        # marked earlier -- forever. Most of the grid reports nothing most of
        # the time, which is how a box that has been taken away stays on the
        # costmap.
        #
        # A zone that measures and finds nothing is evidence of empty space,
        # so it is emitted as a point past obstacle_max_range but inside
        # raytrace_max_range: the costmap clears along it and refuses to mark
        # its endpoint. That is exactly what those two ranges are for.
        #
        # This is only safe because the ToF sources sit in their own costmap
        # layer. "I see nothing" can therefore erase marks this sensor made
        # and nothing else -- it can never reach the lidar's marks, which
        # cover heights the ToF cannot see at all. A dark object the ToF
        # cannot detect was never marked by the ToF either, so there is
        # nothing here for it to wrongly erase.
        clear_points = bytearray()
        clear_count = 0
        streak = self._tof_empty_streak[frame.sensor_id]
        for zone in range(TOF_ZONE_COUNT):
            if zone in masked or frame.status[zone] not in self._tof_empty_statuses:
                streak[zone] = 0
                continue
            streak[zone] += 1
            if (self._tof_clear_range <= 0.0
                    or streak[zone] < self._tof_clear_persistence):
                continue

            # No ground or ceiling filter here, and no marking-range dance.
            # These go to a source declared marking:false, so the layer can be
            # given a height window wide enough to accept every row, and the
            # voxel layer clips each ray where it leaves the grid -- at the
            # floor for the downward rows, at the ceiling for the upward ones.
            # Filtering here would only throw away rays nav2 clips correctly.
            direction = self._tof_directions[zone]
            clear_points += struct.pack(
                "<fff",
                direction[0] * self._tof_clear_range,
                direction[1] * self._tof_clear_range,
                direction[2] * self._tof_clear_range,
            )
            clear_count += 1
            self._tof_cleared[frame.sensor_id] += 1

        frame_id = self._tof_frame_ids[frame.sensor_id]
        self._tof_publishers[frame.sensor_id].publish(
            _cloud(stamp_ns, frame_id, points, valid)
        )
        self._tof_clear_publishers[frame.sensor_id].publish(
            _cloud(stamp_ns, frame_id, clear_points, clear_count)
        )

    def _check_sequence(self, sequence: int) -> None:
        if self._expected_sequence is not None:
            missing = (sequence - self._expected_sequence) & 0xFFFFFFFF
            # Counted, not logged one by one. A burst of gaps would otherwise
            # produce a burst of log calls, which costs time in the very
            # callback that is already falling behind.
            if missing != 0:
                self._gap_count += missing
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

    def _log_tof_status(self, status: TofStatusFrame) -> None:
        """Report bus health, and only when it changes.

        The error counts are cumulative, so a steady value means nothing new is
        going wrong. Logging every second would bury the moment they start to
        climb -- which is the whole point of counting them, since that is how
        motor EMI shows itself.
        """
        errors = status.read_errors + status.data_ready_errors
        previous = self._tof_status_seen.get(status.sensor_id)
        self._tof_status_seen[status.sensor_id] = errors

        name = TOF_SENSOR_NAMES[status.sensor_id] \
            if status.sensor_id < len(TOF_SENSOR_NAMES) else status.sensor_id

        if previous is None:
            self.get_logger().info(
                f"ToF {name}: present={status.present}, "
                f"published={status.published}, i2c errors={errors}"
            )
            return

        if errors > previous:
            self.get_logger().warning(
                f"ToF {name}: I2C errors climbing {previous} -> {errors}. "
                f"If this only moves while the stepper or drive motor runs, "
                f"it is EMI on the sensor wiring"
            )

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
        elif isinstance(frame, TofFrame) and self._tof_enabled:
            self._publish_tof(frame)
        elif isinstance(frame, TofStatusFrame):
            self._log_tof_status(frame)
        elif isinstance(frame, EncoderFrame) and self._encoder_enabled:
            self._publish_encoder(frame)

    def _publish_encoder(self, frame: EncoderFrame) -> None:
        """Turn cumulative counts into a forward velocity for the EKF.

        Velocity is differenced over the MCU's own timestamps rather than
        arrival times. USB scheduling moves frames around by milliseconds, and
        at 50 Hz that lands directly on the velocity; the MCU clock does not
        care when the host got round to reading.
        """
        stamp_ns = self._encoder_clock.stamp_ns(
            frame.mcu_time_us, self.get_clock().now().nanoseconds
        )
        previous = self._encoder_previous
        self._encoder_previous = frame

        if previous is None or frame.sequence <= previous.sequence:
            # First frame, or the MCU restarted and the count went back to
            # zero. Either way there is no interval to divide by.
            return

        interval = (frame.mcu_time_us - previous.mcu_time_us) / 1e6
        if not self._encoder_min_interval <= interval:
            return

        travel = ((frame.count - previous.count)
                  * self._encoder_metres_per_count)
        velocity = travel / interval

        message = TwistWithCovarianceStamped()
        message.header.stamp.sec = stamp_ns // 1_000_000_000
        message.header.stamp.nanosec = stamp_ns % 1_000_000_000
        message.header.frame_id = self._encoder_frame_id
        message.twist.twist.linear.x = velocity
        # Only vx is measured. The unmeasured entries are given a large
        # variance rather than zero, because a zero there reads as a perfect
        # measurement of zero motion and would pin the filter.
        variance = self._encoder_velocity_stddev ** 2
        for index in range(6):
            message.twist.covariance[index * 7] = (
                variance if index == 0 else self._encoder_unused_variance
            )
        self._encoder_publisher.publish(message)

    def _reader_loop(self) -> None:
        """Capture bytes and split lines, independent of ROS scheduling."""
        buffer = bytearray()

        while not self._reader_stop.is_set():
            now = time.monotonic()
            if not self._ensure_serial(now):
                self._reader_stop.wait(0.2)
                continue

            try:
                # Non-blocking, as in tools/tof_probe.py. A blocking read with
                # a timeout raises when the CDC device briefly disappears --
                # which it does every time opening the port resets the ESP32.
                waiting = self._serial.in_waiting
                chunk = self._serial.read(waiting) if waiting else b""
            except (serial.SerialException, OSError) as error:
                self.get_logger().error(f"USB read failed: {error}")
                self._close_serial()
                self._next_reconnect_time = time.monotonic() + 1.0
                continue

            if not chunk:
                self._reader_stop.wait(READER_IDLE_SLEEP_SEC)
                continue

            buffer.extend(chunk)
            while b"\n" in buffer:
                line, _, remainder = buffer.partition(b"\n")
                buffer = bytearray(remainder)
                self._line_queue.append(bytes(line))

    def _on_timer(self) -> None:
        now = time.monotonic()

        if self._reader_thread is None:
            self._reader_thread = threading.Thread(
                target=self._reader_loop, daemon=True, name="tof_imu_reader"
            )
            self._reader_thread.start()

        while self._line_queue:
            self._handle_line(self._line_queue.popleft(), now)

        return

    def destroy_node(self) -> bool:
        self._reader_stop.set()
        if self._reader_thread is not None:
            self._reader_thread.join(timeout=1.0)
        self._close_serial()
        return super().destroy_node()


def main(args=None) -> None:
    rclpy.init(args=args)
    node = SensorBridge()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
