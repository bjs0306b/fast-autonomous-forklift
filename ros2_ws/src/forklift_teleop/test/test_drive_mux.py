import unittest

from geometry_msgs.msg import Twist
from std_msgs.msg import String

from forklift_teleop.drive_mux import ALIGN, IDLE, NAV, DriveMux


class _Selector:
    """DriveMux 의 선택 로직만 떼어 본다.

    노드를 띄우면 rclpy 와 DDS 가 필요하고, 이 장비에서는 디스커버리가 느려
    테스트가 간헐적으로 실패한다. 정작 검증할 것은 "누가 운전대를 잡는가" 라는
    순수한 결정이므로, 그 상태만 재현한다.
    """

    def __init__(self, timeout=0.3):
        self.mode = NAV
        self.timeout = timeout
        self._sources = {NAV: None, ALIGN: None}
        self._seen = {NAV: -1e9, ALIGN: -1e9}
        self.now = 0.0

    def send(self, name, linear, at):
        command = Twist()
        command.linear.x = linear
        self._sources[name] = command
        self._seen[name] = at

    def select(self):
        return DriveMux._select(self)

    # DriveMux._select 가 참조하는 이름들
    @property
    def _mode(self):
        return self.mode

    @property
    def _timeout(self):
        return self.timeout


def _at(selector, when):
    import forklift_teleop.drive_mux as module
    original = module.time.monotonic
    module.time.monotonic = lambda: when
    try:
        return selector.select()
    finally:
        module.time.monotonic = original


class DriveMuxSelectionTest(unittest.TestCase):
    """두 노드가 동시에 운전대를 잡는 일이 없어야 한다.

    nav2 와 포크 정렬 서보가 둘 다 /cmd_vel 에 쓰고 있었다. 중재가 없으면
    마지막에 온 것이 이기는데, 그 고장은 조용하다 -- 양쪽 로그가 다 정상이고
    차만 이상하게 움직인다.
    """

    def selector(self):
        s = _Selector()
        s.send(NAV, 0.10, 1.0)
        s.send(ALIGN, 0.05, 1.0)
        return s

    def test_nav_mode_passes_only_nav(self):
        s = self.selector()
        s.mode = NAV
        command, _ = _at(s, 1.1)
        self.assertAlmostEqual(command.linear.x, 0.10)

    def test_align_mode_passes_only_align(self):
        s = self.selector()
        s.mode = ALIGN
        command, _ = _at(s, 1.1)
        self.assertAlmostEqual(command.linear.x, 0.05)

    def test_idle_drives_nothing_even_with_both_talking(self):
        s = self.selector()
        s.mode = IDLE
        command, reason = _at(s, 1.1)
        self.assertEqual(command.linear.x, 0.0)
        self.assertIn("IDLE", reason)

    def test_a_stale_source_stops_rather_than_repeats_itself(self):
        """조용해진 노드의 마지막 명령으로 계속 굴러가면 안 된다."""
        s = self.selector()
        s.mode = NAV
        command, reason = _at(s, 2.0)
        self.assertEqual(command.linear.x, 0.0)
        self.assertIn("stale", reason)

    def test_the_other_source_going_stale_changes_nothing(self):
        s = self.selector()
        s.mode = ALIGN
        s.send(ALIGN, 0.05, 5.0)
        command, _ = _at(s, 5.1)     # NAV 는 4초째 조용하다
        self.assertAlmostEqual(command.linear.x, 0.05)

    def test_a_mode_with_nothing_published_yet_is_stopped(self):
        s = _Selector()
        s.mode = ALIGN
        command, reason = _at(s, 1.0)
        self.assertEqual(command.linear.x, 0.0)
        self.assertIn("no command", reason)


if __name__ == "__main__":
    unittest.main()
