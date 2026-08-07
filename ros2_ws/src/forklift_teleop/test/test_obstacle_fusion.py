import math
import unittest

from forklift_teleop.obstacle_fusion import (
    front_tof_corridors_from_points,
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
            tof=FrontTofClearance(0.20, 2.0)
        )
        self.assertEqual(decision.action, AvoidanceAction.STOP)

    def test_tall_center_obstacle_seen_by_roof_lidar_stops(self):
        decision = self.decide(
            lidar=LidarCorridors(2.0, 0.20, 2.0)
        )
        self.assertEqual(decision.action, AvoidanceAction.STOP)

    def test_front_left_tof_obstacle_avoids_right_when_lidar_clear(self):
        # Inside avoidance_engage_distance_m -- outside it the guard slows
        # down and leaves the steering to the planner.
        decision = self.decide(
            tof=FrontTofClearance(0.35, 1.40)
        )
        self.assertEqual(decision.action, AvoidanceAction.AVOID_RIGHT)
        linear, angular = apply_decision(0.1, 0.0, decision, 0.35)
        self.assertGreater(linear, 0.0)
        self.assertLess(linear, 0.1)
        self.assertLess(angular, 0.0)

    def test_front_right_tof_obstacle_avoids_left_when_lidar_clear(self):
        decision = self.decide(
            tof=FrontTofClearance(1.40, 0.35)
        )
        self.assertEqual(decision.action, AvoidanceAction.AVOID_LEFT)

    def test_guard_leaves_steering_alone_outside_the_engage_distance(self):
        """Slowing down must not also mean seizing the wheel.

        The bias is bigger than what nav2 asks for, so adding it inverts the
        steering. Applied across the whole slowdown band it fought the planner
        every cycle and the vehicle never committed to a direction.
        """
        decision = self.decide(tof=FrontTofClearance(0.70, 1.40))
        self.assertEqual(decision.action, AvoidanceAction.SLOW)
        self.assertEqual(decision.yaw_bias_rps, 0.0)
        self.assertLess(decision.speed_scale, 1.0)
        _, angular = apply_decision(0.1, -0.05, decision, 0.35)
        self.assertAlmostEqual(angular, -0.05)

    def test_field_wall_at_43cm_avoids_left_instead_of_stopping(self):
        decision = self.decide(
            lidar=LidarCorridors(1.331, 0.468, 0.446),
            tof=FrontTofClearance(0.463, 0.431),
        )
        self.assertEqual(decision.action, AvoidanceAction.AVOID_LEFT)
        self.assertGreater(decision.yaw_bias_rps, 0.0)
        self.assertGreater(decision.speed_scale, 0.0)

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

    def test_roof_lidar_extracts_rear_corridor(self):
        corridors = lidar_corridors_from_points(
            [(-0.7, 0.0), (-0.8, 0.02), (1.5, 0.0), (1.6, 0.0)],
            math.radians(18),
            math.radians(70),
        )
        self.assertAlmostEqual(corridors.rear_m, math.hypot(0.8, 0.02))

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


class FrontTofCorridorsTest(unittest.TestCase):
    """A ToF grid must yield a left/right split, not one number per sensor.

    Collapsing each sensor to its nearest hit made the two read almost
    identically -- they cover the same forward cone -- so the imbalance test
    could never fire and every turn fell through to the roof LiDAR, which
    cannot see fork height at all.
    """

    FRONT = math.radians(35.0)
    CENTER = math.radians(12.0)

    def corridors(self, points):
        return front_tof_corridors_from_points(
            points, self.FRONT, self.CENTER, 0.02, 0.80, minimum_hits=2
        )

    def test_object_on_the_right_only_shortens_the_right_sector(self):
        points = [
            (1.50, +0.60, 0.20), (1.50, +0.62, 0.20),
            (0.40, -0.20, 0.20), (0.41, -0.21, 0.20),
            (1.50, +0.00, 0.20), (1.51, +0.01, 0.20),
        ]
        result = self.corridors(points)
        self.assertLess(result.front_right_m, 0.5)
        self.assertGreater(result.front_left_m, 1.0)
        self.assertGreater(result.front_left_m - result.front_right_m, 0.10)

    def test_floor_and_out_of_cone_points_are_dropped(self):
        points = [
            (0.30, 0.00, -0.05),
            (0.30, 0.00, 1.20),
            (0.30, 0.90, 0.20),
            (-0.5, 0.00, 0.20),
        ]
        result = self.corridors(points)
        self.assertEqual(result.front_left_m, math.inf)
        self.assertEqual(result.front_center_m, math.inf)
        self.assertEqual(result.front_right_m, math.inf)


class FrontCentreIsNotABlindSpotTest(unittest.TestCase):
    """A wall dead ahead must stop the vehicle.

    Splitting the ToF cone into bearing sectors moved the straight-ahead
    returns out of front_left_m/front_right_m and into their own field. For
    one afternoon nothing read that field, so the sector the fork actually
    drives into was the one sector no check covered, and the vehicle drove
    into walls.
    """

    def test_obstacle_dead_ahead_stops_even_with_the_sides_clear(self):
        decision = decide_avoidance(
            LidarCorridors(2.0, 2.0, 2.0, 2.0),
            FrontTofClearance(
                front_left_m=2.0,
                front_right_m=2.0,
                front_center_m=0.15,
            ),
            (),
            (),
            AvoidanceConfig(),
        )
        self.assertEqual(decision.action, AvoidanceAction.STOP)
        self.assertEqual(decision.speed_scale, 0.0)

    def test_centre_also_drives_the_slowdown(self):
        decision = decide_avoidance(
            LidarCorridors(2.0, 2.0, 2.0, 2.0),
            FrontTofClearance(
                front_left_m=2.0,
                front_right_m=2.0,
                front_center_m=0.60,
            ),
            (),
            (),
            AvoidanceConfig(),
        )
        self.assertLess(decision.speed_scale, 1.0)


if __name__ == "__main__":
    unittest.main()
