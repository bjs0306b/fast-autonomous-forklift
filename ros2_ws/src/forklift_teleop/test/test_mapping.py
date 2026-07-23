import unittest

from forklift_teleop.mapping import TeleopLimits, map_twist, select_command


class MappingTest(unittest.TestCase):
    def setUp(self):
        self.limits = TeleopLimits()

    def test_stop_and_in_place_turn_are_centered(self):
        self.assertEqual(map_twist(0.0, 0.35, self.limits).drive_percent, 0)
        self.assertEqual(
            map_twist(0.0, 0.35, self.limits).steering_cdeg,
            10000,
        )

    def test_forward_left_and_right(self):
        left = map_twist(0.2, 0.35, self.limits)
        right = map_twist(0.2, -0.35, self.limits)
        self.assertEqual(left, type(left)(60, 11500))
        self.assertEqual(right, type(right)(60, 8500))

    def test_reverse_flips_rear_steering(self):
        forward_left = map_twist(0.2, 0.35, self.limits)
        reverse_same_yaw = map_twist(-0.2, 0.35, self.limits)
        self.assertEqual(forward_left.steering_cdeg, 11500)
        self.assertEqual(reverse_same_yaw.steering_cdeg, 8500)
        self.assertEqual(reverse_same_yaw.drive_percent, -60)

    def test_values_are_clamped(self):
        command = map_twist(99.0, -99.0, self.limits)
        self.assertEqual(command.drive_percent, 60)
        self.assertEqual(command.steering_cdeg, 8500)

    def test_partial_input_uses_minimum_drive(self):
        command = map_twist(0.02, 0.0, self.limits)
        self.assertGreaterEqual(command.drive_percent, 50)
        self.assertLessEqual(command.drive_percent, 60)

    def test_stale_command_stops_and_centers(self):
        command = select_command(0.2, 0.35, 0.501, 0.5, self.limits)
        self.assertEqual(command.drive_percent, 0)
        self.assertEqual(command.steering_cdeg, 10000)

    def test_fresh_command_is_applied(self):
        command = select_command(0.2, 0.35, 0.499, 0.5, self.limits)
        self.assertEqual(command.drive_percent, 60)
        self.assertEqual(command.steering_cdeg, 11500)


if __name__ == "__main__":
    unittest.main()
