import json
import math
import unittest

from std_msgs.msg import String

from forklift_teleop.obstacle_fusion import (
    FrontTofClearance,
    LidarCorridors,
    status_document,
)
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
        self._max_reverse = 7.0
        self._max_attempts = 3
        self._escape_radius = 0.40
        self._escape_rolling = 0.05
        self._breakaway_sec = 1.5
        self._steer_sec = 1.2
        self._tail_limit = 0.50
        self._tof_imbalance = 0.10
        self._lidar_margin = 0.15
        self._steer_sign = 0.0
        self._last_sign = 0.0
        self._rolling_since = None
        self._steer_until = None
        self._steer_spent = False
        self._escape_started = None
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

    def set_status(self, action, ahead, rear, tof_left=None, tof_right=None,
                   rear_left=None, rear_right=None):
        """실제 가드가 내는 형식으로 만든다.

        손으로 세 키만 짜 두면 실제 문서와 조용히 벌어진다 -- status_document
        를 쓰면 형식이 한 곳에서만 산다.
        """
        self._status = status_document(
            action,
            "test",
            1.0,
            LidarCorridors(
                left_m=math.inf,
                center_m=ahead,
                right_m=math.inf,
                rear_m=rear,
                rear_left_m=math.inf if rear_left is None else rear_left,
                rear_right_m=math.inf if rear_right is None else rear_right,
            ),
            FrontTofClearance(
                front_left_m=math.inf if tof_left is None else tof_left,
                front_right_m=math.inf if tof_right is None else tof_right,
                front_path_m=ahead,
            ),
            [],
        )

    # 실제 메서드를 빌려 쓴다
    _ahead = UnstickNode._ahead
    _rear = UnstickNode._rear
    _say = UnstickNode._say
    _tick = UnstickNode._tick
    _reverse = UnstickNode._reverse
    _corridors = UnstickNode._corridors
    _tail_clearance = UnstickNode._tail_clearance
    _pick_escape_sign = UnstickNode._pick_escape_sign
    _escape_yaw = UnstickNode._escape_yaw
    _steer_yaw = UnstickNode._steer_yaw


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

    def test_a_symmetric_scene_reverses_straight(self):
        """가려던 쪽도 없고 뒤도 대칭이면 꺾을 근거가 없다."""
        f = self.stuck()
        _at(f, 0.0)
        _at(f, 5.0)
        _at(f, 6.0, "_reverse")
        self.assertLess(f.commands[-1].linear.x, 0.0)
        self.assertEqual(f.commands[-1].angular.z, 0.0)


class SteeredEscapeTest(unittest.TestCase):
    """곧게 물러나면 들어온 호를 되짚어 **방향이 제자리로 돌아온다.**

    그러면 다음 전진이 같은 각도로 같은 벽을 만난다. 탈출의 목적은 앞을
    비우는 것이 아니라 **방향을 바꿔 놓는 것**이다.
    """

    def stuck(self, **kwargs):
        f = _Fake()
        f.set_status("STOP", ahead=0.25, rear=0.90, **kwargs)
        return f

    def taken_over(self, **kwargs):
        """인수인계까지 진행한 상태."""
        f = self.stuck(**kwargs)
        _at(f, 0.0)
        _at(f, 5.0)
        return f

    def rolling(self, f, speed=0.10):
        f._speed_measured = speed
        return f

    def test_it_steers_from_the_very_first_tick(self):
        """곧게 먼저 가면 후진 거리의 앞부분을 방향 안 바꾼 채 써 버린다.

        2026-08-10 실측: 조향이 1.5초 뒤에야 들어가, 좁은 곳에서는 꺾기도
        전에 뒤가 막혀 끝났다.
        """
        f = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        _at(f, 6.0, "_reverse")
        self.assertNotEqual(f.commands[-1].angular.z, 0.0)
        self.assertLess(f.commands[-1].linear.x, 0.0)

    def test_it_straightens_if_it_cannot_break_away_steered(self):
        """꺾은 채로 못 뜨면 각을 펴고 곧게 간다 -- 안 그러면 탈출이 무효다."""
        f = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        _at(f, 6.0, "_reverse")
        self.assertNotEqual(f.commands[-1].angular.z, 0.0)
        _at(f, 7.6, "_reverse")     # 1.5초 동안 한 번도 못 구름
        self.assertEqual(f.commands[-1].angular.z, 0.0)
        self.assertLess(f.commands[-1].linear.x, 0.0)

    def test_the_steer_budget_starts_when_the_wheels_start(self):
        """출발이 늦었다고 각 쓸 시간이 줄면 좁은 곳에서 회전을 못 한다."""
        f = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        _at(f, 6.0, "_reverse")          # 아직 못 구름 -- 꺾은 채 대기
        self.rolling(f)
        rolled_at = 7.0
        _at(f, rolled_at, "_reverse")    # 여기서부터 예산이 돈다
        _at(f, rolled_at + f._steer_sec - 0.1, "_reverse")
        self.assertNotEqual(f.commands[-1].angular.z, 0.0)
        _at(f, rolled_at + f._steer_sec + 0.1, "_reverse")
        self.assertEqual(f.commands[-1].angular.z, 0.0)

    def test_the_reverse_turns_the_way_the_vehicle_was_trying_to_go(self):
        """전방 좌측이 막혔으면 우회전 부호(-)로 물러난다.

        전진 시도의 부호를 그대로 이어받는다. 후진이라 후륜각은 저절로
        반대가 되고, 그래서 회전이 누적되어 다음 접근각이 실제로 달라진다.
        """
        f = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        self.assertLess(f._steer_sign, 0.0)
        mirrored = self.taken_over(
            tof_left=0.90, tof_right=0.20, rear_right=0.90)
        self.assertGreater(mirrored._steer_sign, 0.0)

    def test_it_does_not_finish_before_the_heading_has_changed(self):
        """앞이 트여도 조향 예산을 쓰기 전에는 운전대를 안 넘긴다.

        이 테스트가 없으면 기능이 조용히 무효가 된다 -- 뒤가 트인 흔한 경우
        직선 1초 만에 목표 여유가 확보돼 한 번도 안 꺾고 끝난다.
        """
        f = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        f.set_status("STOP", ahead=2.0, rear=0.90,
                     tof_left=0.20, tof_right=0.90, rear_left=0.90)
        _at(f, 6.0, "_reverse")
        self.assertNotIn(NAV, f.modes)

    def test_it_reverses_straight_when_both_tail_sides_are_blocked(self):
        """보이지 않는 곳으로 꼬리를 휘두르지 않는다."""
        f = self.taken_over(tof_left=0.20, tof_right=0.90,
                            rear_left=0.20, rear_right=0.20)
        self.assertEqual(f._steer_sign, 0.0)
        self.rolling(f)
        _at(f, 6.0, "_reverse")
        _at(f, 7.0, "_reverse")
        self.assertEqual(f.commands[-1].angular.z, 0.0)
        self.assertLess(f.commands[-1].linear.x, 0.0)

    def test_a_blocked_tail_goes_straight_instead_of_turning_the_other_way(self):
        """뒤집으면 **후진의 자동 반전과 겹쳐 두 번** 뒤집힌다.

        그러면 후륜이 전진 때와 같은 쪽으로 꺾여 밖에서는 "후진할 때 조향을
        안 한다" 로 보인다(2026-08-10 실측: 전진 7489 / 후진 7020, 둘 다
        중립 아래). 게다가 가려던 방향과 **반대로** 차를 돌려놓아 다음 전진의
        접근각이 오히려 나빠진다. 방향을 못 바꾸는 것보다 나쁜 것은 틀린
        방향으로 바꾸는 것이다.
        """
        # 전방 좌측이 막혀 우회전(-)이 뽑히고, 그때 꼬리는 왼쪽으로 나간다.
        f = self.taken_over(tof_left=0.20, tof_right=0.90,
                            rear_left=0.20, rear_right=0.90)
        self.assertEqual(f._steer_sign, 0.0)

    def test_the_reverse_mirrors_the_forward_intent(self):
        """후진 조향은 전진 조향의 **반대쪽**이어야 한다.

        후륜 조향차는 map_twist 가 곡률 = w/v 로 각을 내므로, 같은 요레이트
        부호를 유지하는 것만으로 후륜각이 뒤집힌다. 그래서 탈출은 전진 시도와
        **같은 요레이트 부호**를 쓴다 -- 여기서 뒤집으면 상쇄된다.
        """
        from forklift_teleop.obstacle_fusion import (
            AvoidanceConfig, _choose_turn,
        )
        f = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        lidar, tof = f._corridors()
        # 가드가 전진 중에 걸었을 편향
        _, forward_bias, _ = _choose_turn(
            lidar, tof, AvoidanceConfig(minimum_turn_clearance_m=0.28))
        self.assertNotEqual(forward_bias, 0.0)
        self.assertGreater(f._steer_sign * forward_bias, 0.0)

    def test_it_stops_steering_when_the_tail_side_closes_in(self):
        f = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        self.rolling(f)
        _at(f, 6.0, "_reverse")
        _at(f, 6.5, "_reverse")
        self.assertNotEqual(f.commands[-1].angular.z, 0.0)
        f.set_status("STOP", ahead=0.25, rear=0.90,
                     tof_left=0.20, tof_right=0.90, rear_left=0.10)
        _at(f, 6.6, "_reverse")
        self.assertEqual(f.commands[-1].angular.z, 0.0)
        self.assertLess(f.commands[-1].linear.x, 0.0)

    def test_the_steered_phase_is_time_boxed(self):
        """0.50 m 여유는 이 예산만큼 도는 것을 전제로 유도했다."""
        f = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        self.rolling(f)
        _at(f, 6.0, "_reverse")
        _at(f, 6.5, "_reverse")
        self.assertNotEqual(f.commands[-1].angular.z, 0.0)
        _at(f, 9.0, "_reverse")     # 예산 2.0초 초과
        self.assertEqual(f.commands[-1].angular.z, 0.0)

    def test_a_repeat_attempt_keeps_the_same_direction(self):
        """재시도라고 방향을 뒤집지 않는다.

        한 번 돌아서 부족했으면 필요한 것은 **더 도는 것**이지 되돌리는 것이
        아니다. 뒤집으면 후진의 자동 반전과 겹쳐 후륜이 전진과 같은 쪽이 된다.
        """
        f = self.stuck(tof_left=0.20, tof_right=0.90,
                       rear_left=0.90, rear_right=0.90)
        f._attempts = 1
        _at(f, 0.0)
        _at(f, 5.0)
        self.assertLess(f._steer_sign, 0.0)

    def test_the_rear_limit_still_ends_the_escape_mid_steer(self):
        """안전 출구는 조향 예산과 무관하게 즉시 선다."""
        f = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        self.rolling(f)
        _at(f, 6.0, "_reverse")
        _at(f, 6.5, "_reverse")
        f.set_status("STOP", ahead=0.25, rear=0.20,
                     tof_left=0.20, tof_right=0.90, rear_left=0.90)
        _at(f, 6.6, "_reverse")
        self.assertEqual(f.modes[-1], NAV)

    def test_the_steering_angle_does_not_grow_with_speed(self):
        """반경으로 명령하므로 속도를 올려도 곡률이 같다.

        고정 요레이트로 되돌아가면 후진 속도를 올릴 때 후륜각이 같이 커져,
        구동 모터가 못 이기는 영역으로 들어간다.
        """
        slow = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        fast = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        fast._speed = slow._speed * 2.0
        curvature = [abs(f._escape_yaw()) / f._speed for f in (slow, fast)]
        self.assertAlmostEqual(curvature[0], curvature[1])
        self.assertAlmostEqual(curvature[0], 1.0 / slow._escape_radius)

    def test_a_stall_while_steering_folds_the_angle_back(self):
        """각이 이 바닥에 과한 것이다 -- 듀티로 밀지 않고 곧게 돌아간다."""
        f = self.taken_over(tof_left=0.20, tof_right=0.90, rear_left=0.90)
        self.rolling(f)
        _at(f, 6.0, "_reverse")
        _at(f, 6.5, "_reverse")
        self.assertNotEqual(f.commands[-1].angular.z, 0.0)
        f._speed_measured = 0.0
        _at(f, 6.6, "_reverse")
        self.rolling(f)
        _at(f, 7.2, "_reverse")
        self.assertEqual(f.commands[-1].angular.z, 0.0)
        self.assertLess(f.commands[-1].linear.x, 0.0)


if __name__ == "__main__":
    unittest.main()
