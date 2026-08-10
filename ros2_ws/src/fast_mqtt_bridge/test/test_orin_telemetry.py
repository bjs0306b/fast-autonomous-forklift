import math
import unittest

from fast_mqtt_bridge.orin_telemetry_model import (
    align_slam_pose,
    normalize_angle,
    telemetry_payload,
)


class OrinTelemetryTest(unittest.TestCase):
    def test_aligns_slam_start_to_measured_warehouse_pose(self):
        x, y, yaw = align_slam_pose(
            0.2, 0.0, math.pi / 4.0,
            1.0, 1.5, math.pi / 2.0,
        )
        self.assertAlmostEqual(1.0, x)
        self.assertAlmostEqual(1.7, y)
        self.assertAlmostEqual(3.0 * math.pi / 4.0, yaw)

    def test_builds_ten_hz_spec_payload_with_only_lengths_scaled(self):
        payload = telemetry_payload(
            vehicle_id="fk01", timestamp_ms=1785936000123,
            x_m=1.55, y_m=0.40, yaw=math.pi / 2.0,
            linear_mps=0.12, angular_rps=0.05, fork_height_m=0.02,
            loaded=False, cargo_id=None, state="MOVING", task_id=None,
            battery=87.5,
        )
        self.assertEqual({"x": 15.5, "y": 4.0, "yaw": 1.5708}, payload["pose"])
        self.assertEqual({"linear": 1.2, "angular": 0.05}, payload["velocity"])
        self.assertEqual(0.2, payload["forkHeight"])
        self.assertIsNone(payload["cargo"])

    def test_normalizes_yaw(self):
        self.assertAlmostEqual(-math.pi / 2.0, normalize_angle(3.0 * math.pi / 2.0))

    def test_rejects_unknown_state(self):
        with self.assertRaisesRegex(ValueError, "unsupported vehicle state"):
            telemetry_payload(
                vehicle_id="fk01", timestamp_ms=1, x_m=0.0, y_m=0.0,
                yaw=0.0, linear_mps=0.0, angular_rps=0.0,
                fork_height_m=0.0, loaded=False, cargo_id=None,
                state="UNKNOWN", task_id=None, battery=100.0,
            )

    def test_rejects_non_finite_pose(self):
        with self.assertRaisesRegex(ValueError, "must be finite"):
            telemetry_payload(
                vehicle_id="fk01", timestamp_ms=1, x_m=math.nan, y_m=0.0,
                yaw=0.0, linear_mps=0.0, angular_rps=0.0,
                fork_height_m=0.0, loaded=False, cargo_id=None,
                state="IDLE", task_id=None, battery=100.0,
            )


if __name__ == "__main__":
    unittest.main()
