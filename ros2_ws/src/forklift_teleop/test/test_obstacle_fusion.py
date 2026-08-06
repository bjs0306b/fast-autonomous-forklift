import math
import unittest

from forklift_teleop.obstacle_fusion import (
    AvoidanceAction,
    AvoidanceConfig,
    FrontTofClearance,
    LidarCorridors,
    VisionDetection,
    apply_decision,
    decide_avoidance,
    front_tof_distance_from_points,
    lidar_corridors_from_points,
    robust_nearest,
)


class ObstacleFusionTest(unittest.TestCase):
    def setUp(self):
        self.config = AvoidanceConfig()
        self.clear_lidar = LidarCorridors(2.0, 2.0, 2.0)
        self.clear_tof = FrontTofClearance(2.0, 2.0)

    def decide(self, lidar=None, tof=None, detections=(), missing=()):
        return decide_avoidance(
            lidar or self.clear_lidar,
            tof or self.clear_tof,
            detections,
            missing,
            self.config,
        )

    def test_clear_roof_and_fork_level_corridors_pass(self):
        decision = self.decide()
        self.assertEqual(decision.action, AvoidanceAction.CLEAR)
        self.assertEqual(
            apply_decision(0.1, 0.0, decision, 0.35),
            (0.1, 0.0),
        )

    def test_low_center_obstacle_seen_only_by_tof_stops(self):
        decision = self.decide(
            tof=FrontTofClearance(0.30, 2.0)
        )
        self.assertEqual(decision.action, AvoidanceAction.STOP)

    def test_tall_center_obstacle_seen_by_roof_lidar_stops(self):
        decision = self.decide(
            lidar=LidarCorridors(2.0, 0.30, 2.0)
        )
        self.assertEqual(decision.action, AvoidanceAction.STOP)

    def test_front_left_tof_obstacle_avoids_right_when_lidar_clear(self):
        decision = self.decide(
            tof=FrontTofClearance(0.70, 1.40)
        )
        self.assertEqual(decision.action, AvoidanceAction.AVOID_RIGHT)
        linear, angular = apply_decision(0.1, 0.0, decision, 0.35)
        self.assertGreater(linear, 0.0)
        self.assertLess(linear, 0.1)
        self.assertLess(angular, 0.0)

    def test_front_right_tof_obstacle_avoids_left_when_lidar_clear(self):
        decision = self.decide(
            tof=FrontTofClearance(1.40, 0.70)
        )
        self.assertEqual(decision.action, AvoidanceAction.AVOID_LEFT)

    def test_tof_nominated_turn_is_rejected_when_lidar_side_blocked(self):
        decision = self.decide(
            lidar=LidarCorridors(2.0, 2.0, 0.40),
            tof=FrontTofClearance(0.70, 1.40),
        )
        self.assertEqual(decision.action, AvoidanceAction.SLOW)
        self.assertEqual(decision.yaw_bias_rps, 0.0)

    def test_person_or_vehicle_stops(self):
        for label in ("person", "forklift"):
            with self.subTest(label=label):
                decision = self.decide(detections=(
                    VisionDetection(label, 0.95, 1.0),
                ))
                self.assertEqual(decision.action, AvoidanceAction.STOP)

    def test_required_sensor_timeout_stops(self):
        decision = self.decide(missing=("front_right_tof",))
        self.assertEqual(decision.action, AvoidanceAction.SENSOR_TIMEOUT)

    def test_roof_lidar_points_are_split_in_base_frame(self):
        corridors = lidar_corridors_from_points(
            [
                (1.0, 0.7), (1.1, 0.8),
                (0.7, 0.0), (0.8, 0.0),
                (1.0, -0.7), (1.1, -0.8),
            ],
            math.radians(18),
            math.radians(70),
        )
        self.assertGreater(corridors.left_m, 1.0)
        self.assertEqual(corridors.center_m, 0.8)
        self.assertGreater(corridors.right_m, 1.0)

    def test_front_tof_filters_floor_and_outside_fork_cone(self):
        distance = front_tof_distance_from_points(
            [
                (0.20, 0.0, 0.0),       # floor: ignored
                (0.30, 0.5, 0.1),       # outside cone: ignored
                (0.60, 0.05, 0.1),
                (0.70, 0.05, 0.1),
            ],
            math.radians(35),
            0.02,
            0.80,
        )
        self.assertAlmostEqual(distance, math.hypot(0.70, 0.05))

    def test_isolated_speckle_is_rejected(self):
        self.assertEqual(robust_nearest([0.05, 0.80, 0.90]), 0.80)


if __name__ == "__main__":
    unittest.main()
