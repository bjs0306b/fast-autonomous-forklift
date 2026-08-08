import json
import unittest

from std_msgs.msg import String

from forklift_teleop.unstick_node import NAV, RECOVER, UnstickNode


class _Fake:
    """UnstickNode 의 판단 부분만 떼어 본다.

    노드를 띄우면 DDS 디스커버리에 의존해 간헐적으로 실패한다. 검증할 것은
    "언제 끼어들고 언제 참는가" 라는 결정이고, 그것은 순수하다.
    """

    def __init__(self):
        self._status = None
        self._speed_measured = 0.0
        self._mode = NAV
        self._blocked_since = None
        self._recovering_until = None
        self._attempts = 0
        self._last_report = ""
        self._stuck_for = 4.0
        self._rolling = 0.02
        self._speed = 0.12
        self._target = 0.60
        self._rear_limit = 0.45
        self._max_reverse = 5.0
        self._max_attempts = 3
        self.modes = []
        self.commands = []
        self.reports = []

        class _Pub:
            def __init__(self, sink):
                self.sink = sink

            def publish(self, message):
                self.sink.append(
                    message.data if hasattr(message, "data") else message)

        self._mode_publisher = _Pub(self.modes)
        self._command = _Pub(self.commands)
        self._report = _Pub(self.reports)

    def get_logger(self):
        class _L:
            def info(self, *_):
                pass
        return _L()

    def set_status(self, action, ahead, rear):
        self._status = {
            "action": action,
            "roofLidar": {"center": ahead, "rear": rear},
            "frontTof": {"path": ahead},
        }

    # 실제 메서드를 빌려 쓴다
    _ahead = UnstickNode._ahead
    _rear = UnstickNode._rear
    _say = UnstickNode._say
    _tick = UnstickNode._tick
    _reverse = UnstickNode._reverse


def _at(fake, when, method="_tick"):
    import forklift_teleop.unstick_node as module
    original = module.time.monotonic
    module.time.monotonic = lambda: when
    try:
        getattr(fake, method)()
    finally:
        module.time.monotonic = original


class UnstickDecisionTest(unittest.TestCase):
    """가드가 멈춰 세운 차를 언제 대신 빼내는가.

    나설 이유는 하나뿐이다: 가드가 STOP 을 걸면 컨트롤러가 명령을 안 내고,
    그러면 nav2 의 진행 판정이 돌 기회가 없어 복구가 영영 발동하지 않는다.
    """

    def stuck(self):
        f = _Fake()
        f.set_status("STOP", ahead=0.25, rear=0.90)
        return f

    def test_it_waits_before_taking_the_wheel(self):
        """잠깐의 정지에 끼어들면 정상 주행을 방해한다."""
        f = self.stuck()
        _at(f, 0.0)
        _at(f, 2.0)
        self.assertEqual(f.modes, [])

    def test_it_takes_over_once_the_stop_persists(self):
        f = self.stuck()
        _at(f, 0.0)
        _at(f, 5.0)
        self.assertEqual(f.modes, [RECOVER])

    def test_a_moving_vehicle_is_not_stuck(self):
        """STOP 이어도 바퀴가 돌면 빠져나가는 중이다."""
        f = self.stuck()
        f._speed_measured = 0.08
        _at(f, 0.0)
        _at(f, 5.0)
        self.assertEqual(f.modes, [])

    def test_it_never_interrupts_alignment(self):
        """진입 중에는 파렛트에 일부러 다가간다 -- 후진하면 포크가 빠진다."""
        f = self.stuck()
        f._mode = "ALIGN"
        _at(f, 0.0)
        _at(f, 5.0)
        self.assertEqual(f.modes, [])

    def test_it_refuses_when_there_is_no_room_behind(self):
        f = _Fake()
        f.set_status("STOP", ahead=0.25, rear=0.30)
        _at(f, 0.0)
        _at(f, 5.0)
        self.assertEqual(f.modes, [])
        self.assertTrue(any("사람이 필요" in r for r in f.reports))

    def test_it_gives_up_after_repeated_failures(self):
        """빠져나오자마자 다시 갇히면 무한 반복이 된다."""
        f = self.stuck()
        f._attempts = 3
        _at(f, 0.0)
        _at(f, 5.0)
        self.assertEqual(f.modes, [])
        self.assertTrue(any("사람이 필요" in r for r in f.reports))

    def test_it_hands_the_wheel_back_once_there_is_room(self):
        f = self.stuck()
        _at(f, 0.0)
        _at(f, 5.0)               # RECOVER 진입
        f.set_status("SLOW", ahead=0.70, rear=0.60)
        _at(f, 6.0, "_reverse")
        self.assertEqual(f.modes, [RECOVER, NAV])
        self.assertIsNone(f._recovering_until)

    def test_a_successful_escape_resets_the_attempt_count(self):
        f = self.stuck()
        _at(f, 0.0)
        _at(f, 5.0)
        self.assertEqual(f._attempts, 1)
        f.set_status("CLEAR", ahead=0.80, rear=0.60)
        _at(f, 6.0, "_reverse")
        self.assertEqual(f._attempts, 0)

    def test_it_stops_reversing_if_the_rear_closes_in(self):
        f = self.stuck()
        _at(f, 0.0)
        _at(f, 5.0)
        f.set_status("STOP", ahead=0.25, rear=0.30)
        _at(f, 6.0, "_reverse")
        self.assertEqual(f.modes, [RECOVER, NAV])

    def test_reverse_is_straight(self):
        """꺾인 채로는 정지에서 못 뜬다."""
        f = self.stuck()
        _at(f, 0.0)
        _at(f, 5.0)
        _at(f, 6.0, "_reverse")
        self.assertLess(f.commands[-1].linear.x, 0.0)
        self.assertEqual(f.commands[-1].angular.z, 0.0)


if __name__ == "__main__":
    unittest.main()
