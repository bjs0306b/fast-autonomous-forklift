import unittest

from forklift_teleop.protocol import frame_body
from forklift_teleop.sensor_protocol import (
    ClockOffsetTracker,
    ImuFrame,
    ImuStatusFrame,
    parse_sensor_line,
)


class SensorProtocolTest(unittest.TestCase):
    def test_parse_imu_frame(self):
        frame = parse_sensor_line(frame_body("IMU,881,15243120,-1053,3412"))
        self.assertEqual(
            frame,
            ImuFrame(
                sequence=881,
                mcu_time_us=15243120,
                gyro_z_mdps=-1053,
                temp_cdeg=3412,
            ),
        )

    def test_parse_imu_status_frame(self):
        frame = parse_sensor_line(frame_body("IMS,112,-38,1,7,9001"))
        self.assertEqual(
            frame,
            ImuStatusFrame(
                who_am_i=112,
                bias_mdps=-38,
                idle=True,
                dropped=7,
                sequence=9001,
            ),
        )

    def test_log_lines_are_ignored(self):
        self.assertIsNone(
            parse_sensor_line(b"I (1234) IMU_TASK: Gyro Z bias: -38 mdps\n")
        )
        self.assertIsNone(parse_sensor_line(b"\n"))

    def test_unknown_frame_kind_is_ignored(self):
        self.assertIsNone(parse_sensor_line(frame_body("ACK,7,OK")))

    def test_bad_crc_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "CRC mismatch"):
            parse_sensor_line(b"@IMU,881,15243120,-1053,3412*0000\n")

    def test_missing_crc_field_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "CRC field"):
            parse_sensor_line(b"@IMU,881,15243120,-1053,3412\n")

    def test_field_count_is_checked(self):
        with self.assertRaisesRegex(ValueError, "field count"):
            parse_sensor_line(frame_body("IMU,881,15243120,-1053"))

    def test_non_numeric_field_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "IMU gyro"):
            parse_sensor_line(frame_body("IMU,881,15243120,abc,3412"))


class ClockOffsetTrackerTest(unittest.TestCase):
    def test_first_sample_maps_to_host_time(self):
        tracker = ClockOffsetTracker()
        stamp = tracker.stamp_ns(mcu_time_us=1_000, host_time_ns=5_000_000_000)
        self.assertEqual(stamp, 5_000_000_000)
        self.assertEqual(tracker.reset_count, 1)

    def test_queueing_delay_does_not_shift_the_offset(self):
        tracker = ClockOffsetTracker()
        tracker.stamp_ns(mcu_time_us=1_000, host_time_ns=1_000_000_000)

        # Same MCU spacing (10ms) but the host read it 3ms late. The stamp must
        # follow the MCU clock, drifting up by no more than the skew allowance
        # (20 ppm over 10 ms = 200 ns) rather than absorbing the 3 ms delay.
        stamp = tracker.stamp_ns(
            mcu_time_us=11_000,
            host_time_ns=1_013_000_000,
        )
        self.assertGreaterEqual(stamp, 1_010_000_000)
        self.assertLessEqual(stamp, 1_010_000_200)

    def test_stamp_is_never_in_the_future(self):
        tracker = ClockOffsetTracker()
        tracker.stamp_ns(mcu_time_us=1_000, host_time_ns=1_000_000_000)

        # Host clock stepped backwards by less than the reset threshold.
        stamp = tracker.stamp_ns(
            mcu_time_us=11_000,
            host_time_ns=1_005_000_000,
        )
        self.assertLessEqual(stamp, 1_005_000_000)

    def test_mcu_reboot_resets_the_offset(self):
        tracker = ClockOffsetTracker()
        tracker.stamp_ns(mcu_time_us=5_000_000, host_time_ns=9_000_000_000)

        stamp = tracker.stamp_ns(mcu_time_us=1_000, host_time_ns=9_100_000_000)
        self.assertEqual(stamp, 9_100_000_000)
        self.assertEqual(tracker.reset_count, 2)

    def test_offset_follows_slow_mcu_drift(self):
        tracker = ClockOffsetTracker(drift_rate=20e-6)
        tracker.stamp_ns(mcu_time_us=0, host_time_ns=1_000_000_000)

        # MCU clock runs slow, so host - mcu grows steadily. The offset must
        # track it instead of pinning the stamps to the first sample forever.
        mcu_us = 0
        host_ns = 1_000_000_000
        for _ in range(200):
            mcu_us += 10_000
            host_ns += 10_000_100  # 10 ppm slow MCU
            tracker.stamp_ns(mcu_time_us=mcu_us, host_time_ns=host_ns)

        expected_offset = host_ns - mcu_us * 1000
        self.assertEqual(tracker.offset_ns, expected_offset)


if __name__ == "__main__":
    unittest.main()
