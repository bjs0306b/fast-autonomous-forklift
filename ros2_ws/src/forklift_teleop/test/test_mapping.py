import unittest

from forklift_teleop.mapping import TeleopLimits, map_twist, select_command


class MappingTest(unittest.TestCase):
    def setUp(self):
        self.limits = TeleopLimits()

    # 기대값은 **기본 설정**(중립 9400 · 좌우 각 2800 · 한계 28°) 기준이다.
    # ⚠️ 2026-08-04 오후까지 이 파일은 낡은 기본값(중립 10000 · ±1500 · 15°)을
    #    검증하고 있었다. 실제 설정은 197·198 로 두 번 바뀌었는데 여기가 안
    #    따라와서, **테스트가 오히려 틀린 값을 고정하고** 있었다.
    CENTER = 9400
    LEFT_FULL = 12200
    RIGHT_FULL = 6600

    def test_stop_and_in_place_turn_are_centered(self):
        self.assertEqual(map_twist(0.0, 0.35, self.limits).drive_percent, 0)
        self.assertEqual(
            map_twist(0.0, 0.35, self.limits).steering_cdeg,
            self.CENTER,
        )

    def test_forward_left_and_right(self):
        left = map_twist(0.2, 0.35, self.limits)
        right = map_twist(0.2, -0.35, self.limits)
        self.assertEqual(left, type(left)(60, 10814))
        self.assertEqual(right, type(right)(60, 7986))

    def test_left_and_right_are_symmetric_about_center(self):
        """중립을 옮기면 좌우 폭이 어긋나기 쉬워 대칭성을 못 박아둔다.

        2026-08-04 에 중립을 9600 → 9400 으로 내리면서 min/max 를 그대로 뒀다면
        좌 28° · 우 32° 로 비대칭이 됐을 것이다. 그러면 `turn_ratio` 1.0 이
        방향에 따라 다른 실제 각도를 뜻하게 된다.
        """
        left = map_twist(0.2, 0.35, self.limits).steering_cdeg
        right = map_twist(0.2, -0.35, self.limits).steering_cdeg
        self.assertEqual(left - self.CENTER, self.CENTER - right)

    def test_reverse_flips_rear_steering(self):
        forward_left = map_twist(0.2, 0.35, self.limits)
        reverse_same_yaw = map_twist(-0.2, 0.35, self.limits)
        self.assertEqual(forward_left.steering_cdeg, 10814)
        self.assertEqual(reverse_same_yaw.steering_cdeg, 7986)
        self.assertEqual(reverse_same_yaw.drive_percent, -60)

    def test_values_are_clamped(self):
        command = map_twist(99.0, -99.0, self.limits)
        self.assertEqual(command.drive_percent, 60)
        self.assertEqual(command.steering_cdeg, 7986)

    def test_defaults_match_the_deployed_config(self):
        """기본값이 config/teleop.yaml 과 어긋나지 않게 못 박는다.

        브리지는 늘 yaml 을 명시적으로 넘기므로 기본값이 낡아도 **동작에는 영향이
        없다.** 그래서 낡은 줄 모르고 지나간다 — 인자 없이 `TeleopLimits()` 를
        만드는 코드가 하나 생기는 순간 조용히 틀린 조향값을 쓴다.
        """
        self.assertEqual(self.limits.steering_center_cdeg, self.CENTER)
        self.assertEqual(self.limits.steering_min_cdeg, self.RIGHT_FULL)
        self.assertEqual(self.limits.steering_max_cdeg, self.LEFT_FULL)
        self.assertEqual(self.limits.min_drive_percent, 35)
        self.assertEqual(self.limits.rear_steering_limit_deg, 28.0)

    def test_slower_speed_increases_steering_for_same_yaw_rate(self):
        fast = map_twist(0.2, 0.2, self.limits)
        slow = map_twist(0.1, 0.2, self.limits)
        fast_offset = abs(fast.steering_cdeg - self.CENTER)
        slow_offset = abs(slow.steering_cdeg - self.CENTER)
        self.assertGreater(slow_offset, fast_offset)

    def test_equal_curvature_produces_equal_steering(self):
        first = map_twist(0.2, 0.2, self.limits)
        second = map_twist(0.1, 0.1, self.limits)
        self.assertEqual(first.steering_cdeg, second.steering_cdeg)

    def test_steering_is_limited_at_low_speed(self):
        left = map_twist(0.02, 0.35, self.limits)
        right = map_twist(0.02, -0.35, self.limits)
        self.assertEqual(left.steering_cdeg, self.LEFT_FULL)
        self.assertEqual(right.steering_cdeg, self.RIGHT_FULL)

    def test_partial_input_uses_minimum_drive(self):
        command = map_twist(0.02, 0.0, self.limits)
        self.assertGreaterEqual(command.drive_percent, 35)
        self.assertLessEqual(command.drive_percent, 60)

    def test_low_and_high_commands_map_to_different_drive(self):
        """명령이 실제로 갈리는지 — 이게 S15P11A304-198 의 완료 조건이다.

        하한이 50 이던 동안 0.05 와 0.20 이 52% 와 60% 로 8%p 차이였고, 그
        폭 안에서는 실제 속도가 사실상 구분되지 않았다.
        """
        slow = map_twist(0.05, 0.0, self.limits).drive_percent
        fast = map_twist(0.20, 0.0, self.limits).drive_percent
        self.assertGreaterEqual(fast - slow, 15)

    def test_stale_command_stops_and_centers(self):
        command = select_command(0.2, 0.35, 0.501, 0.5, self.limits)
        self.assertEqual(command.drive_percent, 0)
        self.assertEqual(command.steering_cdeg, self.CENTER)

    def test_fresh_command_is_applied(self):
        command = select_command(0.2, 0.35, 0.499, 0.5, self.limits)
        self.assertEqual(command.drive_percent, 60)
        self.assertEqual(command.steering_cdeg, 10814)


if __name__ == "__main__":
    unittest.main()
