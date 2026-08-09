import math
import unittest

from forklift_teleop.pivot_node import shuttle_command

WHEELBASE_M = 0.155


def yaw_change(command, seconds=1.0):
    """이 명령을 seconds 동안 유지했을 때의 yaw 변화.

    후륜 조향차의 요레이트는 v·tan(δ)/L 이고, 명령의 angular.z 가 곧 그것이다
    (곡률 = 각속도/선속도). 그러므로 부호만 보면 된다.
    """
    return command.angular.z * seconds


class ShuttleTurnTest(unittest.TestCase):
    """전진·후진을 번갈아 해도 방향이 **한쪽으로 쌓이는가.**

    여기가 이 동작의 전부다. 같은 조향으로 물러나면 방금 튼 것을 그대로 풀기
    때문에, 부호를 안 뒤집으면 차는 앞뒤로 흔들리기만 하고 yaw 는 제자리다 --
    2026-08-08 에 실제로 그 상태로 70초를 보냈다(yaw -1° 고정).
    """

    def forward_left(self):
        return shuttle_command(going_forward=True, turn_left=True,
                               speed=0.22, curvature=9.0)

    def backward_left(self):
        return shuttle_command(going_forward=False, turn_left=True,
                               speed=0.22, curvature=9.0)

    def test_both_legs_turn_the_same_way(self):
        self.assertGreater(yaw_change(self.forward_left()), 0.0)
        self.assertGreater(yaw_change(self.backward_left()), 0.0)

    def test_reverse_leg_actually_reverses(self):
        self.assertGreater(self.forward_left().linear.x, 0.0)
        self.assertLess(self.backward_left().linear.x, 0.0)

    def test_the_reverse_leg_flips_the_steering(self):
        """요레이트는 같은데 **조향은 저절로 반대가 된다.**

        곡률 = w/v 이고 후진은 v 가 음수라, 같은 w 를 요구하는 것만으로
        조향 부호가 뒤집힌다. 명령에서 따로 뒤집으면 두 번 뒤집혀 상쇄된다.
        """
        forward = self.forward_left()
        backward = self.backward_left()
        forward_steer = math.atan(
            WHEELBASE_M * forward.angular.z / forward.linear.x)
        backward_steer = math.atan(
            WHEELBASE_M * backward.angular.z / backward.linear.x)
        self.assertGreater(forward_steer, 0.0)
        self.assertLess(backward_steer, 0.0)

    def test_turning_right_is_the_mirror_image(self):
        for going_forward in (True, False):
            left = shuttle_command(going_forward=going_forward, turn_left=True,
                                   speed=0.22, curvature=9.0)
            right = shuttle_command(going_forward=going_forward,
                                    turn_left=False, speed=0.22, curvature=9.0)
            with self.subTest(going_forward=going_forward):
                self.assertAlmostEqual(left.angular.z, -right.angular.z)
                self.assertAlmostEqual(left.linear.x, right.linear.x)

    def test_a_full_cycle_accumulates_rather_than_cancels(self):
        """왕복 한 번의 순 변화가 0 이 아니어야 한다."""
        total = (yaw_change(self.forward_left(), 1.6)
                 + yaw_change(self.backward_left(), 1.6))
        self.assertGreater(total, 0.5)

    def test_holding_the_steering_through_the_reverse_would_cancel(self):
        """왜 조향이 뒤집혀야 하는지 -- 유지하면 정확히 상쇄된다.

        이건 구현이 아니라 물리다. 후륜 조향차의 요레이트는 v·tan(δ)/L 이라,
        조향을 그대로 둔 채 물러나면 v 의 부호만 바뀌어 방금 튼 만큼이 그대로
        풀린다. 사람이 좁은 데서 차를 돌릴 때 핸들을 반대로 감는 이유다.
        """
        forward = self.forward_left()
        steering = math.atan(
            WHEELBASE_M * forward.angular.z / forward.linear.x)
        # 같은 조향각을 유지한 채 후진했을 때의 요레이트
        held = -forward.linear.x * math.tan(steering) / WHEELBASE_M
        self.assertLess(held, 0.0)
        self.assertAlmostEqual(yaw_change(forward) + held, 0.0, places=6)


if __name__ == "__main__":
    unittest.main()
