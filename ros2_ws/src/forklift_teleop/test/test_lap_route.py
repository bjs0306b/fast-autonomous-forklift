import math
import unittest

from forklift_teleop.lap_route import subdivide_route, warehouse_to_slam


class LapRouteTest(unittest.TestCase):
    def test_starting_pose_becomes_slam_origin(self):
        pose = warehouse_to_slam(0.618, 0.618, 0.0, 0.618, 0.618, 0.0)
        self.assertEqual((0.0, 0.0, 0.0), pose)

    def test_rotated_start_converts_warehouse_axes(self):
        x_m, y_m, yaw = warehouse_to_slam(
            1.0, 2.0, math.pi, 1.0, 1.0, math.pi / 2.0
        )
        self.assertAlmostEqual(1.0, x_m)
        self.assertAlmostEqual(0.0, y_m)
        self.assertAlmostEqual(math.pi / 2.0, yaw)

    def test_long_legs_are_subdivided_for_online_mapping(self):
        route = subdivide_route(
            (0.0, 0.0, 0.0),
            [(0.6, 0.0, 0.0), (0.6, 0.5, math.pi / 2.0)],
            0.25,
        )
        self.assertEqual(5, len(route))
        self.assertAlmostEqual(0.2, route[0][0])
        self.assertAlmostEqual(0.6, route[2][0])
        self.assertAlmostEqual(math.pi / 2.0, route[3][2])
        for first, second in zip([(0.0, 0.0, 0.0)] + route, route):
            self.assertLessEqual(
                math.hypot(second[0] - first[0], second[1] - first[1]),
                0.25,
            )

    def test_subdivision_rejects_invalid_length(self):
        with self.assertRaises(ValueError):
            subdivide_route((0.0, 0.0, 0.0), [(1.0, 0.0, 0.0)], 0.0)


if __name__ == "__main__":
    unittest.main()
