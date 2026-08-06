import math
import unittest

from forklift_teleop.mapping import TeleopLimits, map_twist, select_command


class MappingTest(unittest.TestCase):
    def setUp(self):
        self.limits = TeleopLimits()

    # ⚠️ **기대값을 숫자로 적지 않는다. 설정에서 유도한다.**
    #
    # 2026-08-04 오후까지 이 파일은 낡은 기본값(중립 10000 · ±1500 · 15°)을
    # 검증하고 있었고, 실제 설정이 197·198 로 두 번 바뀌는 동안 안 따라와서
    # **테스트가 오히려 틀린 값을 고정하고** 있었다. 2026-08-05 에 152 로 또
    # 바뀌었다(9000 · ±3600 · 36°). 숫자를 적어두는 한 이 일은 반복된다.
    @property
    def CENTER(self):
        return self.limits.steering_center_cdeg

    @property
    def LEFT_FULL(self):
        return self.limits.steering_max_cdeg

    @property
    def RIGHT_FULL(self):
        return self.limits.steering_min_cdeg

    def test_stop_and_in_place_turn_are_centered(self):
        self.assertEqual(map_twist(0.0, 0.35, self.limits).drive_percent, 0)
        self.assertEqual(
            map_twist(0.0, 0.35, self.limits).steering_cdeg,
            self.CENTER,
        )

    def test_forward_left_and_right(self):
        """조향값은 **중립 + 뒷바퀴각×100** 이어야 한다.

        `rear_steering_limit_deg` 와 좌우 폭(cdeg)이 일치하는 한(둘 다 1° = 100cdeg)
        이 관계가 성립한다. 둘이 어긋나면 여기서 깨진다 — 그게 이 테스트의 목적이다.
        """
        expected = round(math.degrees(
            math.atan(self.limits.wheelbase_m * 0.35 / 0.2)) * 100)
        left = map_twist(0.2, 0.35, self.limits)
        right = map_twist(0.2, -0.35, self.limits)
        self.assertEqual(left.drive_percent, 60)
        self.assertEqual(left.steering_cdeg, self.CENTER + expected)
        self.assertEqual(right.steering_cdeg, self.CENTER - expected)

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
        offset = abs(forward_left.steering_cdeg - self.CENTER)
        self.assertEqual(reverse_same_yaw.steering_cdeg, self.CENTER - offset)
        # 후진은 상한이 다르다(전진 60 · 후진 100) — 같은 명령도 듀티가 크다.
        self.assertEqual(reverse_same_yaw.drive_percent,
                         -self.limits.max_drive_percent_reverse)

    def test_values_are_clamped(self):
        command = map_twist(99.0, -99.0, self.limits)
        self.assertEqual(command.drive_percent, 60)
        # 상한으로 잘린 입력은 **상한값을 직접 준 것과 같아야** 한다.
        # ⚠️ 이때 조향이 최대(RIGHT_FULL)가 되는 것이 아니다 — 0.2 m/s 에서는
        #    같은 각속도가 더 작은 곡률이라 14° 정도다. 속도가 조향각을 정한다.
        self.assertEqual(command, map_twist(0.2, -0.35, self.limits))

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
        self.assertEqual(self.limits.rear_steering_limit_deg,
                         (self.LEFT_FULL - self.CENTER) / 100.0)

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
        self.assertEqual(command.steering_cdeg, map_twist(0.2, 0.35, self.limits).steering_cdeg)


if __name__ == "__main__":
    unittest.main()
