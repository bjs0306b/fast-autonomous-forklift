import json
import unittest

from geometry_msgs.msg import Twist
import rclpy
from std_msgs.msg import String

from forklift_teleop.debug_hud import DebugHud


class DebugHudTest(unittest.TestCase):
    """차가 안 움직일 때 **어디서** 끊겼는지 여섯 줄로 갈리는가.

    이유는 최소 넷인데 밖에서는 넷이 똑같이 보인다:

        플래너   경로가 0점
        컨트롤러 경로는 있는데 nav2 가 0
        가드     nav2 는 내는데 가드가 STOP
        브리지   가드는 통과시키는데 서보가 중립

    이 테스트가 지키는 것은 "그 넷이 화면에서 구분되는가" 하나다.
    """

    @classmethod
    def setUpClass(cls):
        rclpy.init()

    @classmethod
    def tearDownClass(cls):
        rclpy.shutdown()

    def setUp(self):
        self.node = DebugHud()

    def tearDown(self):
        self.node.destroy_node()

    def lines(self):
        action, lines = self.node._lines(now=100.0)
        return action, "\n".join(lines)

    def guard(self, action, reason="시험"):
        self.node._on_guard(String(data=json.dumps(
            {"action": action, "reason": reason})))

    def nav(self, vx, wz):
        command = Twist()
        command.linear.x = vx
        command.angular.z = wz
        self.node._on_nav(command)
        self.node._nav_at = 99.8

    def plan(self, points, at=99.6):
        self.node._plan_points = points
        self.node._plan_at = at

    def teleop(self, degrees, duty, measured=0.0):
        self.node._on_teleop(String(data=json.dumps({
            "rearSteeringDeg": degrees, "drivePercent": duty,
            "steeringCdeg": 9000, "measuredMps": measured})))

    def test_no_plan_is_named_as_the_planner(self):
        self.plan(0)
        _, text = self.lines()
        self.assertIn("0점", text)
        self.assertIn("플래너가 못 냈다", text)

    def test_a_plan_with_zero_command_shows_both(self):
        """경로는 있는데 nav2 가 0 이면 컨트롤러다."""
        self.plan(108)
        self.nav(0.0, 0.0)
        _, text = self.lines()
        self.assertIn("108점", text)
        self.assertIn("v=+0.00", text)
        self.assertNotIn("플래너가 못 냈다", text)

    def test_guard_reason_is_shown_verbatim(self):
        self.guard("STOP", "front obstacle at 0.27m")
        action, text = self.lines()
        self.assertEqual(action, "STOP")
        self.assertIn("front obstacle at 0.27m", text)

    def test_servo_line_reports_what_actually_went_out(self):
        """가드 출력이 아니라 UART 로 나간 값이다 -- 둘은 다르다."""
        self.teleop(-18.7, 75, measured=0.264)
        _, text = self.lines()
        self.assertIn("-18.7", text)
        self.assertIn("75%", text)
        self.assertIn("+0.264", text)

    def test_a_missing_bridge_says_so_rather_than_showing_zero(self):
        """서보 0도와 '브리지가 없다' 는 완전히 다른 사건이다."""
        _, text = self.lines()
        self.assertIn("브리지 확인", text)

    def test_unknown_encoder_is_not_reported_as_zero(self):
        self.teleop(0.0, 0, measured=None)
        _, text = self.lines()
        self.assertIn("모름", text)

    def test_a_silent_guard_explains_itself(self):
        """가드는 /cmd_vel 이 흐를 때만 발행한다 -- 침묵이 고장은 아니다."""
        _, text = self.lines()
        self.assertIn("cmd_vel", text)

    def test_stale_plan_is_distinguishable_from_a_fresh_one(self):
        """재계획 중인지 포기했는지는 점 수가 아니라 갱신 시각이 가른다."""
        self.plan(108, at=99.9)
        _, fresh = self.lines()
        self.plan(108, at=40.0)
        _, stale = self.lines()
        self.assertNotEqual(fresh, stale)

    def test_colour_marks_stopped_apart_from_moving(self):
        from forklift_teleop.debug_hud import COLORS
        self.assertNotEqual(COLORS["STOP"], COLORS["CLEAR"])
        self.assertNotEqual(COLORS["SLOW"], COLORS["CLEAR"])


if __name__ == "__main__":
    unittest.main()
