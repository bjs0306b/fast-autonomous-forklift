import math
import pathlib
import unittest

import yaml

from forklift_teleop import protocol
from forklift_teleop.mapping import (
    ActuatorCommand,
    StartupKick,
    SpeedController,
    TurnSpeedBoost,
    TeleopLimits,
    map_twist,
    steering_turn_ratio,
    sustain_floor_percent,
    select_command,
)


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
        # 상한 속도로 낸다 -- 숫자를 박아 두면 max_linear_mps 를 바꿀 때마다
        # 조향과 무관한 이유로 깨진다.
        top = self.limits.max_linear_mps
        expected = round(math.degrees(
            math.atan(self.limits.wheelbase_m * 0.35 / top)) * 100)
        left = map_twist(top, 0.35, self.limits)
        right = map_twist(top * 2, -0.35, self.limits)
        self.assertEqual(left.drive_percent, self.limits.max_drive_percent)
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
        # 상한 속도로 낸다 -- 숫자를 박으면 max_linear_mps 를 바꿀 때마다
        # 조향·후진과 무관한 이유로 깨진다.
        top = self.limits.max_linear_mps
        forward_left = map_twist(top, 0.35, self.limits)
        reverse_same_yaw = map_twist(-top, 0.35, self.limits)
        offset = abs(forward_left.steering_cdeg - self.CENTER)
        self.assertEqual(reverse_same_yaw.steering_cdeg, self.CENTER - offset)
        # 후진은 상한이 다르다 — 같은 명령도 듀티가 크다.
        self.assertEqual(reverse_same_yaw.drive_percent,
                         -self.limits.max_drive_percent_reverse)

    def test_values_are_clamped(self):
        command = map_twist(99.0, -99.0, self.limits)
        self.assertEqual(command.drive_percent, self.limits.max_drive_percent)
        # 상한으로 잘린 입력은 **상한값을 직접 준 것과 같아야** 한다.
        # ⚠️ 이때 조향이 반드시 최대(RIGHT_FULL)가 되는 것은 아니다 — 조향각은
        #    곡률(angular/linear)에서 나오므로 **속도가 조향각을 정한다.**
        #
        # ⚠️ 상한을 숫자로 적지 않는다. 2026-08-05 에 max_angular_rps 가
        #    0.35 → 0.75 로 바뀌자 여기 적힌 0.35 때문에 이 테스트만 깨졌다.
        self.assertEqual(
            command,
            map_twist(self.limits.max_linear_mps,
                      -self.limits.max_angular_rps,
                      self.limits))

    def test_defaults_match_the_deployed_config(self):
        """기본값이 config/teleop.yaml 과 어긋나지 않게 못 박는다.

        브리지는 늘 yaml 을 명시적으로 넘기므로 기본값이 낡아도 **동작에는 영향이
        없다.** 그래서 낡은 줄 모르고 지나간다 — 인자 없이 `TeleopLimits()` 를
        만드는 코드가 하나 생기는 순간 조용히 틀린 조향값을 쓴다.

        ⚠️ **기대값을 여기 적지 않고 yaml 을 읽어서 비교한다.** 종전에는 필드를
        골라 숫자로 적었는데, 그러면 **적어두지 않은 필드는 안 지켜진다** —
        2026-08-05 에 `max_angular_rps` 가 0.35 → 0.75 로 바뀌었을 때 여기서
        안 잡혔다. 이제 두 곳에 다 있는 필드는 전부 자동으로 비교된다.
        """
        params = self._deployed_params()
        common = [f for f in vars(self.limits) if f in params]
        # 필드가 실수로 하나도 안 겹치면(키 구조 변경 등) 통과처럼 보이면 안 된다.
        self.assertGreaterEqual(len(common), 8, f"비교된 필드: {common}")
        for field in common:
            with self.subTest(field=field):
                self.assertEqual(getattr(self.limits, field), params[field])

        # yaml 에 없는 관계(조향 폭 ↔ 각도 한계)는 여기서 따로 못 박는다.
        self.assertEqual(self.limits.rear_steering_limit_deg,
                         (self.LEFT_FULL - self.CENTER) / 100.0)

    def test_deployed_steering_fits_inside_the_protocol_envelope(self):
        """운전 범위가 프로토콜 봉투를 넘으면 **주행 중에** 브리지가 죽는다.

        test_protocol 은 경계를 상수에서 끌어오므로 늘 통과한다 -- 그래서 이
        불일치를 못 잡는다. 어긋나는 것은 상수끼리가 아니라 `teleop.yaml` 의
        운전 범위와 `protocol.py` 의 봉투 사이다.

        2026-08-08: yaml 과 펌웨어만 ±58° 로 넓히고 봉투는 ±50° 로 두었더니,
        랩 주행 세 번이 전부 조향 14522 에서 브리지 사망으로 끝났다. 나머지
        노드는 살아 있어 가드가 SLOW 를 계속 발행했고, 증상은 "서보가 안
        꺾이는데 아무도 에러를 안 낸다" 였다.
        """
        params = self._deployed_params()
        self.assertGreaterEqual(params["steering_min_cdeg"],
                                protocol.STEERING_MIN_CDEG)
        self.assertLessEqual(params["steering_max_cdeg"],
                             protocol.STEERING_MAX_CDEG)

    def _deployed_params(self):
        path = (pathlib.Path(__file__).resolve().parent.parent
                / "config" / "teleop.yaml")
        if not path.exists():                       # 설치 트리에서 실행된 경우
            self.skipTest(f"config/teleop.yaml 을 찾지 못했다: {path}")
        with path.open(encoding="utf-8") as handle:
            loaded = yaml.safe_load(handle)
        return loaded["uart_teleop_bridge"]["ros__parameters"]

    def test_slower_speed_increases_steering_for_same_yaw_rate(self):
        fast = map_twist(0.10, 0.2, self.limits)
        slow = map_twist(0.05, 0.2, self.limits)
        fast_offset = abs(fast.steering_cdeg - self.CENTER)
        slow_offset = abs(slow.steering_cdeg - self.CENTER)
        self.assertGreater(slow_offset, fast_offset)

    def test_equal_curvature_produces_equal_steering(self):
        first = map_twist(0.10, 0.2, self.limits)
        second = map_twist(0.05, 0.1, self.limits)
        self.assertEqual(first.steering_cdeg, second.steering_cdeg)

    def test_steering_never_exceeds_the_mechanical_lock(self):
        """조향은 어떤 명령에도 기계 한계를 넘지 않는다.

        예전에는 저속 + 큰 각속도가 곧장 완전 잠김으로 포화되는 것을 확인했다.
        이제는 min_command_turning_radius_m 이 그 앞에서 먼저 깎으므로, 포화
        자체를 보려면 그 제한을 끈 상태여야 한다. 넘지 않는다는 보장은 그대로다.
        """
        from dataclasses import replace as _replace
        uncapped = _replace(self.limits, min_command_turning_radius_m=0.0)
        left = map_twist(0.02, 0.35, uncapped)
        right = map_twist(0.02, -0.35, uncapped)
        self.assertEqual(left.steering_cdeg, self.LEFT_FULL)
        self.assertEqual(right.steering_cdeg, self.RIGHT_FULL)

    def test_low_speed_hard_turns_stay_startable(self):
        """저속에서 곡률이 무한정 커지지 않는다 -- 정지 출발이 가능한 범위."""
        capped = map_twist(0.02, 0.35, self.limits)
        self.assertNotEqual(capped.steering_cdeg, self.LEFT_FULL)

    def test_partial_input_uses_minimum_drive(self):
        command = map_twist(0.02, 0.0, self.limits)
        self.assertGreaterEqual(command.drive_percent, 35)
        self.assertLessEqual(command.drive_percent,
                             self.limits.max_drive_percent)

    def test_low_and_high_commands_map_to_different_drive(self):
        """명령이 실제로 갈리는지 — 이게 S15P11A304-198 의 완료 조건이다.

        하한이 50 이던 동안 0.05 와 0.20 이 52% 와 60% 로 8%p 차이였고, 그
        폭 안에서는 실제 속도가 사실상 구분되지 않았다.
        """
        slow = map_twist(self.limits.max_linear_mps * 0.25, 0.0,
                         self.limits).drive_percent
        fast = map_twist(self.limits.max_linear_mps, 0.0,
                         self.limits).drive_percent
        self.assertGreaterEqual(fast - slow, 15)

    def test_stale_command_stops_and_centers(self):
        command = select_command(0.2, 0.35, 0.501, 0.5, self.limits)
        self.assertEqual(command.drive_percent, 0)
        self.assertEqual(command.steering_cdeg, self.CENTER)

    def test_fresh_command_is_applied(self):
        top = self.limits.max_linear_mps
        command = select_command(top, 0.35, 0.499, 0.5, self.limits)
        self.assertEqual(command.drive_percent, self.limits.max_drive_percent)
        self.assertEqual(command.steering_cdeg,
                         map_twist(top, 0.35, self.limits).steering_cdeg)

    def test_forward_startup_kick_overcomes_static_friction_then_expires(self):
        kick = StartupKick(50, 100, 0.3)
        slow = ActuatorCommand(41, self.CENTER)
        self.assertEqual(50, kick.apply(slow, 10.0).drive_percent)
        self.assertEqual(50, kick.apply(slow, 10.29).drive_percent)
        self.assertEqual(41, kick.apply(slow, 10.30).drive_percent)

    def test_stop_immediately_cancels_startup_kick(self):
        kick = StartupKick(50, 100, 0.6)
        slow = ActuatorCommand(41, self.CENTER)
        kick.apply(slow, 10.0)
        stop = ActuatorCommand(0, self.CENTER)
        self.assertEqual(stop, kick.apply(stop, 10.1))
        self.assertEqual(50, kick.apply(slow, 10.2).drive_percent)

    def test_reverse_startup_kick_uses_its_own_stronger_figure(self):
        # A rear-steered chassis reversing needs far more duty than forwards;
        # Nav2's BackUp recovery starts from a standstill and would not move
        # at all on the forward figure.
        kick = StartupKick(50, 100, 0.3)
        slow = ActuatorCommand(-41, self.CENTER)
        self.assertEqual(-100, kick.apply(slow, 10.0).drive_percent)
        self.assertEqual(-41, kick.apply(slow, 10.30).drive_percent)

    def test_kick_rearms_when_direction_reverses(self):
        kick = StartupKick(50, 100, 0.3)
        forward = ActuatorCommand(41, self.CENTER)
        reverse = ActuatorCommand(-41, self.CENTER)
        self.assertEqual(50, kick.apply(forward, 10.0).drive_percent)
        self.assertEqual(41, kick.apply(forward, 10.5).drive_percent)
        # 방향이 바뀌면 다시 정지마찰을 이겨야 한다.
        self.assertEqual(-100, kick.apply(reverse, 10.6).drive_percent)

    def test_kick_never_weakens_a_stronger_command(self):
        kick = StartupKick(50, 100, 0.3)
        self.assertEqual(
            60, kick.apply(ActuatorCommand(60, self.CENTER), 10.0).drive_percent
        )


class StraightReverseKickTest(unittest.TestCase):
    """Straight back is ordinary rolling resistance; steered back is not.

    The 100% figure comes from a 45-degree re-approach where the steered
    wheels plough sideways. nav2's BackUp recovery commands no steering at
    all, so applying that figure there overrode the requested speed outright
    and made the vehicle lurch.
    """

    def kick(self):
        return StartupKick(
            forward_percent=50,
            reverse_percent=100,
            duration_sec=0.3,
            reverse_straight_percent=60,
            steering_center_cdeg=9600,
        )

    def test_straight_reverse_uses_the_lower_figure(self):
        command = ActuatorCommand(drive_percent=-20, steering_cdeg=9600)
        self.assertEqual(self.kick().apply(command, 0.0).drive_percent, -60)

    def test_steered_reverse_still_uses_the_full_figure(self):
        command = ActuatorCommand(drive_percent=-20, steering_cdeg=13200)
        self.assertEqual(self.kick().apply(command, 0.0).drive_percent, -100)

    def test_kick_expires_and_leaves_the_command_alone(self):
        kick = self.kick()
        command = ActuatorCommand(drive_percent=-20, steering_cdeg=9600)
        kick.apply(command, 0.0)
        self.assertEqual(kick.apply(command, 1.0).drive_percent, -20)


class StallKickTest(unittest.TestCase):
    """A kick that expires on a timer gives up while the vehicle is still still.

    nav2 regulates down to about 0.037 m/s in a tight avoidance, below the
    duty that can start the vehicle. The fixed 0.3 s then ran out against a
    vehicle that had not moved at all, and nothing ever started it.
    """

    def kick(self):
        return StartupKick(
            forward_percent=50,
            reverse_percent=100,
            duration_sec=0.3,
            steering_center_cdeg=9600,
            stall_speed_mps=0.01,
            max_stall_kick_sec=2.0,
        )

    def test_kick_is_held_while_the_wheels_are_not_turning(self):
        kick = self.kick()
        command = ActuatorCommand(drive_percent=35, steering_cdeg=9600)
        kick.apply(command, 0.0, 0.0)
        self.assertEqual(kick.apply(command, 1.0, 0.0).drive_percent, 50)

    def test_kick_releases_once_the_vehicle_rolls(self):
        kick = self.kick()
        command = ActuatorCommand(drive_percent=35, steering_cdeg=9600)
        kick.apply(command, 0.0, 0.0)
        self.assertEqual(kick.apply(command, 1.0, 0.08).drive_percent, 35)

    def test_kick_gives_up_so_a_blocked_motor_is_not_cooked(self):
        kick = self.kick()
        command = ActuatorCommand(drive_percent=35, steering_cdeg=9600)
        kick.apply(command, 0.0, 0.0)
        self.assertEqual(kick.apply(command, 5.0, 0.0).drive_percent, 35)

    def test_without_an_encoder_the_kick_stays_time_based(self):
        kick = self.kick()
        command = ActuatorCommand(drive_percent=35, steering_cdeg=9600)
        kick.apply(command, 0.0)
        self.assertEqual(kick.apply(command, 1.0).drive_percent, 35)


class SteeredStallKickTest(unittest.TestCase):
    """Starting with the wheels turned costs what reversing costs.

    The 50% figure was measured driving straight. With the rear wheel at lock
    it ploughs sideways instead of rolling, which is the same resistance that
    made 60% move the vehicle exactly nothing backwards.
    """

    def kick(self, straighten=True):
        # ⚠️ straighten_to_start 는 이제 **기본이 꺼져 있다.** 2026-08-09
        #    재측정에서 50도로 꺾인 채 정지 출발이 됐기 때문이다. 아래 시험들은
        #    그 기능 자체를 보는 것이므로 명시적으로 켠다.
        return StartupKick(
            forward_percent=50,
            reverse_percent=100,
            duration_sec=0.3,
            reverse_straight_percent=60,
            steering_center_cdeg=9600,
            stall_speed_mps=0.01,
            max_stall_kick_sec=2.0,
            straighten_to_start=straighten,
        )

    def test_straightening_is_off_by_default(self):
        """근거였던 실측이 낡았다 -- 켜 두면 회전 때마다 갈팡질팡한다.

        2026-08-09 재측정: 후륜 25.6°/43.8°/50.2° 전부 정지에서 출발했고,
        중간 각도는 직진(0.057)보다 오히려 빨랐다(0.170). 기능은 남기되
        기본은 끈다 -- 바닥이나 적재가 바뀌면 파라미터 하나로 되살린다.
        """
        self.assertFalse(StartupKick(50, 100, 0.3).straighten_to_start)
        default = self.kick(straighten=False)
        command = ActuatorCommand(drive_percent=35, steering_cdeg=13200)
        default.apply(command, 0.0, 0.0)
        # 조향을 펴지 않고 원래 각도를 유지한 채 듀티만 올린다
        self.assertEqual(default.apply(command, 1.0, 0.0).steering_cdeg, 13200)

    def test_stalled_and_steered_first_straightens_instead_of_pushing_harder(self):
        """듀티를 더 주기 전에 **긁는 저항 자체를 없앤다.**

        전륜 구동 · 후륜 조향이라, 조향륜이 꺾인 채로는 구동륜이 그 저항까지
        끌어야 한다. 실측으로 100% 를 줘도 못 떴으므로 듀티로는 상한이 있다.
        """
        kick = self.kick()
        command = ActuatorCommand(drive_percent=35, steering_cdeg=13200)
        kick.apply(command, 0.0, 0.0)
        result = kick.apply(command, 1.0, 0.0)
        self.assertEqual(result.steering_cdeg, 9600)
        self.assertEqual(result.drive_percent, 50)

    def test_it_gives_up_straightening_and_pushes_harder(self):
        """펴서도 못 뜨면 원래 조향으로 돌아가 센 듀티를 쓴다.

        무한정 펴 두면 의도한 곡선 대신 직진을 계속하게 되고, 목업에서는
        그대로 벽이다.
        """
        kick = self.kick()
        command = ActuatorCommand(drive_percent=35, steering_cdeg=13200)
        kick.apply(command, 0.0, 0.0)
        kick.apply(command, 1.0, 0.0)          # 펴기 시작
        result = kick.apply(command, 3.0, 0.0)  # 상한(1.5초) 넘김
        self.assertEqual(result.steering_cdeg, 13200)
        self.assertEqual(result.drive_percent, 100)

    def test_it_hands_the_steering_back_once_the_wheels_roll(self):
        """구르기 시작하면 곧바로 원래 곡률로 돌려준다.

        구르는 중에는 조향륜이 굴러가며 방향을 바꾸므로 긁지 않는다 --
        완전 조향으로도 잘 돈다는 것이 실측돼 있다.
        """
        kick = self.kick()
        command = ActuatorCommand(drive_percent=35, steering_cdeg=13200)
        kick.apply(command, 0.0, 0.0)
        self.assertEqual(kick.apply(command, 1.0, 0.0).steering_cdeg, 9600)
        self.assertEqual(kick.apply(command, 1.2, 0.20).steering_cdeg, 13200)

    def test_without_an_encoder_it_never_straightens(self):
        """구름을 볼 수 없으면 언제 돌려줄지도 모른다.

        그 상태로 펴면 의도한 곡선 대신 직진이 이어진다 -- 못 도는 것보다 나쁘다.
        """
        kick = self.kick()
        command = ActuatorCommand(drive_percent=35, steering_cdeg=13200)
        kick.apply(command, 0.0, None)
        self.assertEqual(kick.apply(command, 1.0, None).steering_cdeg, 13200)

    def test_stalled_but_straight_keeps_the_ordinary_figure(self):
        kick = self.kick()
        command = ActuatorCommand(drive_percent=35, steering_cdeg=9600)
        kick.apply(command, 0.0, 0.0)
        self.assertEqual(kick.apply(command, 1.0, 0.0).drive_percent, 50)


class SteeredSpeedBoostTest(unittest.TestCase):
    """Turning hard needs more than the straight-line speed can deliver.

    Raising the controller's floor instead carried the higher speed out of
    the turn into the straight that followed, and the vehicle hit a wall.
    Tying the boost to the steering angle makes it release itself.
    """

    def limits(self):
        return TeleopLimits(steered_speed_boost=2.0)

    def test_straight_running_is_not_boosted(self):
        limits = self.limits()
        plain = map_twist(0.05, 0.0, limits)
        self.assertEqual(
            plain.drive_percent,
            map_twist(0.05, 0.0, TeleopLimits(steered_speed_boost=1.0)
                      ).drive_percent,
        )

    def test_turning_raises_duty_at_the_same_commanded_speed(self):
        limits = self.limits()
        straight = map_twist(0.05, 0.0, limits)
        turning = map_twist(0.05, 0.35, limits)
        self.assertGreater(turning.drive_percent, straight.drive_percent)

    def test_boost_releases_as_the_wheel_straightens(self):
        limits = self.limits()
        hard = map_twist(0.05, 0.35, limits)
        gentle = map_twist(0.05, 0.05, limits)
        self.assertGreater(hard.drive_percent, gentle.drive_percent)

    def test_steering_angle_is_unchanged_by_the_boost(self):
        boosted = map_twist(0.05, 0.35, TeleopLimits(steered_speed_boost=2.0))
        plain = map_twist(0.05, 0.35, TeleopLimits(steered_speed_boost=1.0))
        self.assertEqual(boosted.steering_cdeg, plain.steering_cdeg)


class SpeedControllerTest(unittest.TestCase):
    """Duty has to be found, not predicted.

    One straight line from speed to duty cannot be right at both ends: below
    roughly 0.08 m/s the commanded duty will not break static friction, and
    the duty that does break it carries the vehicle far too fast once it is
    rolling.
    """

    def controller(self):
        return SpeedController(
            percent_per_mps_second=500.0, max_bias_percent=40
        )

    def test_a_stalled_vehicle_gets_more_duty_every_tick(self):
        c = self.controller()
        c.bias(0.08, 0.0, 0.0)
        first = c.bias(0.08, 0.0, 0.1)
        second = c.bias(0.08, 0.0, 0.2)
        self.assertGreater(first, 0)
        self.assertGreater(second, first)

    def test_overspeed_pulls_duty_back(self):
        c = self.controller()
        c.bias(0.08, 0.0, 0.0)
        self.assertLess(c.bias(0.08, 0.20, 0.1), 0)

    def test_bias_is_capped_so_the_envelope_still_rules(self):
        c = self.controller()
        c.bias(0.08, 0.0, 0.0)
        for step in range(1, 200):
            bias = c.bias(0.08, 0.0, step * 0.1)
        self.assertEqual(bias, 40)

    def test_stopping_does_not_leave_wound_up_duty_for_the_next_start(self):
        c = self.controller()
        c.bias(0.08, 0.0, 0.0)
        c.bias(0.08, 0.0, 1.0)
        self.assertEqual(c.bias(0.0, 0.0, 1.1), 0)
        self.assertEqual(c.bias(0.08, 0.0, 1.2), 0)

    def test_direction_change_starts_from_scratch(self):
        c = self.controller()
        c.bias(0.08, 0.0, 0.0)
        c.bias(0.08, 0.0, 1.0)
        self.assertEqual(c.bias(-0.08, 0.0, 1.1), 0)

    def test_reverse_is_tracked_by_magnitude_not_sign(self):
        """The encoder's direction is unverified; this must not rely on it."""
        c = self.controller()
        c.bias(-0.08, 0.0, 0.0)
        self.assertGreater(c.bias(-0.08, 0.02, 0.1), 0)

    def test_without_an_encoder_it_stays_out_of_the_way(self):
        c = self.controller()
        self.assertEqual(c.bias(0.08, None, 0.0), 0)
        self.assertEqual(c.bias(0.08, None, 1.0), 0)


class SustainFloorTest(unittest.TestCase):
    """Starting and keeping going are different jobs with different floors."""

    def test_the_two_floors_are_separate_and_ordered(self):
        limits = TeleopLimits()
        self.assertLess(limits.min_sustain_drive_percent,
                        limits.min_drive_percent)

    def test_a_sustain_floor_above_the_start_floor_is_rejected(self):
        with self.assertRaises(ValueError):
            TeleopLimits(min_drive_percent=35,
                         min_sustain_drive_percent=40).validate()


class TurnSpeedBoostTest(unittest.TestCase):
    """Extra speed to break out of a turn, gone the moment the turn happens."""

    TURNING = 0.5      # turn_ratio, well past engage
    TARGET = 0.05

    def boost(self):
        return TurnSpeedBoost(
            gain_per_second=0.8, max_multiplier=2.5, engage_turn_ratio=0.25
        )

    def test_it_accumulates_while_the_turn_is_not_happening(self):
        b = self.boost()
        b.update(self.TARGET, 0.0, self.TURNING, 0.0)
        first = b.update(self.TARGET, 0.0, self.TURNING, 0.2)
        second = b.update(self.TARGET, 0.0, self.TURNING, 0.4)
        self.assertGreater(first, 1.0)
        self.assertGreater(second, first)

    def test_it_drops_at_once_when_the_vehicle_keeps_up(self):
        """Not a decay: leftover speed is what carries past the heading."""
        b = self.boost()
        b.update(self.TARGET, 0.0, self.TURNING, 0.0)
        for step in range(1, 10):
            b.update(self.TARGET, 0.0, self.TURNING, step * 0.2)
        self.assertGreater(b.multiplier, 1.5)
        self.assertEqual(
            b.update(self.TARGET, self.TARGET, self.TURNING, 2.0), 1.0
        )

    def test_going_straight_never_boosts(self):
        b = self.boost()
        b.update(self.TARGET, 0.0, 0.0, 0.0)
        self.assertEqual(b.update(self.TARGET, 0.0, 0.0, 0.5), 1.0)

    def test_a_gentle_bend_is_not_a_turn(self):
        b = self.boost()
        b.update(self.TARGET, 0.0, 0.1, 0.0)
        self.assertEqual(b.update(self.TARGET, 0.0, 0.1, 0.5), 1.0)

    def test_it_is_capped(self):
        b = self.boost()
        b.update(self.TARGET, 0.0, self.TURNING, 0.0)
        for step in range(1, 100):
            value = b.update(self.TARGET, 0.0, self.TURNING, step * 0.2)
        self.assertEqual(value, 2.5)

    def test_curvature_survives_the_boost(self):
        """Clipping one of the pair and not the other would re-steer it."""
        limits = TeleopLimits()
        b = self.boost()
        linear, angular = 0.05, 0.30
        capped = b.limited(2.5, linear, angular, limits)
        plain = map_twist(linear, angular, limits)
        boosted = map_twist(linear * capped, angular * capped, limits)
        self.assertGreater(capped, 1.0)
        self.assertEqual(boosted.steering_cdeg, plain.steering_cdeg)
        self.assertGreater(boosted.drive_percent, plain.drive_percent)

    def test_the_cap_respects_both_limits(self):
        limits = TeleopLimits()
        b = self.boost()
        capped = b.limited(2.5, 0.05, 0.30, limits)
        self.assertLessEqual(0.05 * capped, limits.max_linear_mps + 1e-9)
        self.assertLessEqual(0.30 * capped, limits.max_angular_rps + 1e-9)


class StartCeilingTest(unittest.TestCase):
    """Breaking away from a standstill is not cruising and is not bounded by it.

    60% is the cruising ceiling and cannot move -- the insert speed, the entry
    depth and the steering centre were all calibrated against it. None of that
    was measured at a standstill with the rear wheels at full lock, where the
    steered wheels plough sideways and 60% cannot start the vehicle at all.
    """

    def test_the_cruise_ceiling_never_exceeds_the_start_ceiling(self):
        """순항이 출발보다 셀 수는 없다 -- 그 반대는 말이 안 된다.

        ⚠️ 종전에는 **더 작아야** 한다고 못 박았다. 둘을 나눠 둔 이유는
           발열이다: 100 은 정지마찰을 이기는 동안만 쓰는 값이고, 계속 물리면
           멈춘 모터에 최대 전류가 그대로 들어간다.

           2026-08-09 에 깊은 조향에서 힘이 계속 모자라 순항 상한을 100 으로
           올리면서 그 구분이 사라졌다. **의도한 선택이지 실수가 아니다.**
           대신 되돌릴 조건을 teleop.yaml 에 적어 두었다 -- 모터가 뜨겁거나
           전압이 떨어져 오히려 더 못 가면 내린다.
        """
        limits = TeleopLimits()
        self.assertLessEqual(limits.max_drive_percent,
                             limits.max_start_drive_percent)

    def test_a_start_ceiling_below_the_cruise_ceiling_is_rejected(self):
        with self.assertRaises(ValueError):
            TeleopLimits(max_drive_percent=60,
                         max_start_drive_percent=50).validate()

    def test_the_start_ceiling_cannot_exceed_full_duty(self):
        with self.assertRaises(ValueError):
            TeleopLimits(max_start_drive_percent=120).validate()


class StallKickBurstTest(unittest.TestCase):
    """One attempt is not enough when the threshold moves with the floor.

    A full-lock start broke away at about two seconds on one attempt and not
    at all on the next, in the same room. A single window either catches that
    or the vehicle sits commanded but still for as long as the goal lasts.
    """

    def kick(self):
        return StartupKick(
            forward_percent=50,
            reverse_percent=100,
            duration_sec=0.3,
            steering_center_cdeg=9600,
            stall_speed_mps=0.01,
            max_stall_kick_sec=2.0,
            stall_kick_rest_sec=1.0,
        )

    def command(self):
        return ActuatorCommand(drive_percent=35, steering_cdeg=9600)

    def test_it_pushes_again_after_resting(self):
        kick = self.kick()
        command = self.command()
        kick.apply(command, 0.0, 0.0)
        self.assertEqual(kick.apply(command, 1.0, 0.0).drive_percent, 50)
        # 2.0 ~ 3.0 s is the rest: the plain command goes through.
        self.assertEqual(kick.apply(command, 2.5, 0.0).drive_percent, 35)
        # and then it tries again rather than giving up.
        self.assertEqual(kick.apply(command, 3.5, 0.0).drive_percent, 50)

    def test_rolling_still_ends_it_immediately(self):
        kick = self.kick()
        command = self.command()
        kick.apply(command, 0.0, 0.0)
        self.assertEqual(kick.apply(command, 1.0, 0.08).drive_percent, 35)


class CommandCurvatureLimitTest(unittest.TestCase):
    """Never ask for a turn this vehicle cannot start.

    Curvature is angular over linear, so the slower the command the harder the
    same yaw rate steers. nav2 constrains the *path* through
    minimum_turning_radius but not the instantaneous curvature the controller
    produces while following it, and at 0.08 m/s with 0.35 rad/s that came out
    at 0.23 m -- near full lock, where a stopped vehicle cannot start again at
    any duty the drivetrain has.
    """

    def limits(self):
        return TeleopLimits(min_command_turning_radius_m=0.30)

    def realised_radius(self, command, limits):
        offset = abs(command.steering_cdeg - limits.steering_center_cdeg) / 100.0
        if offset < 0.01:
            return math.inf
        return limits.wheelbase_m / math.tan(math.radians(offset))

    def test_slow_hard_turns_are_opened_up(self):
        limits = self.limits()
        command = map_twist(0.08, 0.35, limits)
        self.assertGreaterEqual(
            self.realised_radius(command, limits), 0.30 - 0.01
        )

    def test_the_limit_scales_with_speed(self):
        limits = self.limits()
        slow = self.realised_radius(map_twist(0.08, 0.35, limits), limits)
        fast = self.realised_radius(map_twist(0.25, 0.35, limits), limits)
        self.assertGreater(fast, slow)

    def test_speed_is_not_reduced_to_achieve_it(self):
        """Slowing down would make the curvature worse, not better.

        ⚠️ 종전에는 직진과 **같아야** 한다고 못 박았는데, steered_speed_boost 가
           1.0(사실상 끔)이던 시절의 표현이다. 1.6 이 된 지금은 회전이 오히려
           더 빠르다 -- 조향륜이 긁는 저항을 이기려는 것이므로 의도대로다.
           지켜야 할 것은 처음부터 "느려지지 않는다" 였다.
        """
        limits = self.limits()
        capped = map_twist(0.08, 0.35, limits)
        straight = map_twist(0.08, 0.0, limits)
        self.assertGreaterEqual(capped.drive_percent, straight.drive_percent)

    def test_gentle_turns_pass_through_untouched(self):
        limits = self.limits()
        capped = map_twist(0.25, 0.05, limits)
        plain = map_twist(0.25, 0.05,
                          TeleopLimits(min_command_turning_radius_m=0.0))
        self.assertEqual(capped.steering_cdeg, plain.steering_cdeg)

    def test_zero_disables_it(self):
        limits = TeleopLimits(min_command_turning_radius_m=0.0)
        command = map_twist(0.08, 0.35, limits)
        self.assertLess(self.realised_radius(command, limits), 0.30)


class SteeringSettleTest(unittest.TestCase):
    """The discount is about where the wheels are, not what we just asked for.

    Straight reverse needs 60% and steered reverse needs 100%, but the check
    reads the commanded steering. Right after a turn the command is centred
    while the servo is still at lock, so the moment that most needs the higher
    figure was getting the lower one. nav2's BackUp commanded -0.100 with zero
    angular for eight seconds and the encoder never left zero.
    """

    def kick(self):
        return StartupKick(
            forward_percent=50,
            reverse_percent=100,
            duration_sec=0.3,
            reverse_straight_percent=60,
            steering_center_cdeg=9600,
            steering_settle_sec=0.6,
        )

    def straight(self):
        return ActuatorCommand(drive_percent=-20, steering_cdeg=9600)

    def turned(self):
        return ActuatorCommand(drive_percent=-20, steering_cdeg=13200)

    # 실속(0.0)을 넘겨 킥을 살려 둔다 -- 그러지 않으면 duration_sec 에 만료돼
    # 명령이 그대로 통과하고, 할인 여부를 볼 수 없다.
    def test_just_centred_still_uses_the_steered_figure(self):
        kick = self.kick()
        kick.apply(self.turned(), 0.0, 0.0)
        self.assertEqual(
            kick.apply(self.straight(), 0.1, 0.0).drive_percent, -100
        )

    def test_after_settling_it_takes_the_discount(self):
        kick = self.kick()
        kick.apply(self.turned(), 0.0, 0.0)
        kick.apply(self.straight(), 0.1, 0.0)
        self.assertEqual(
            kick.apply(self.straight(), 0.8, 0.0).drive_percent, -60
        )

    def test_turning_again_restarts_the_wait(self):
        kick = self.kick()
        kick.apply(self.straight(), 0.0, 0.0)
        kick.apply(self.straight(), 0.8, 0.0)
        kick.apply(self.turned(), 0.9, 0.0)
        self.assertEqual(
            kick.apply(self.straight(), 1.0, 0.0).drive_percent, -100
        )



class PreSteerOnDirectionChangeTest(unittest.TestCase):
    """방향이 뒤집힐 때 서보가 갈 동안 세워 둔다.

    안 하면 차가 먼저 움직이고 바퀴가 나중에 꺾인다 -- 후진 탈출에서는 앞부분을
    방향 안 바꾼 채 쓰고, 좁은 곳에서는 꺾기도 전에 공간이 끝난다.
    사람이 서서 핸들 돌리고 출발하는 것과 같은 동작이다.
    """

    def kick(self, **kwargs):
        from forklift_teleop.mapping import StartupKick
        base = dict(forward_percent=50, reverse_percent=100,
                    duration_sec=0.3, steering_settle_sec=0.6,
                    steering_center_cdeg=9000)
        base.update(kwargs)
        return StartupKick(**base)

    def command(self, drive, steering):
        from forklift_teleop.mapping import ActuatorCommand
        return ActuatorCommand(drive, steering)

    def test_a_reversal_with_a_new_angle_holds_the_drive_at_zero(self):
        k = self.kick()
        k.apply(self.command(60, 7500), 0.0)          # 전진, 왼쪽
        out = k.apply(self.command(-60, 11000), 1.0)  # 후진, 반대쪽
        self.assertEqual(out.drive_percent, 0)
        # ⚠️ 조향은 그대로 나가야 한다 -- 안 그러면 서보가 갈 목표를 못 받는다.
        self.assertEqual(out.steering_cdeg, 11000)

    def test_the_hold_ends_after_the_servo_settle_time(self):
        k = self.kick()
        k.apply(self.command(60, 7500), 0.0)
        k.apply(self.command(-60, 11000), 1.0)
        moving = k.apply(self.command(-60, 11000), 1.7)   # 0.6초 경과
        self.assertNotEqual(moving.drive_percent, 0)

    def test_a_reversal_at_the_same_angle_does_not_wait(self):
        """서보가 갈 곳이 없으면 기다릴 이유도 없다."""
        k = self.kick()
        k.apply(self.command(60, 9000), 0.0)
        out = k.apply(self.command(-60, 9000), 1.0)
        self.assertNotEqual(out.drive_percent, 0)

    def test_steering_changes_without_a_reversal_are_not_delayed(self):
        """조향이 바뀔 때마다 걸면 평소 주행이 0.6초씩 끊긴다."""
        k = self.kick()
        k.apply(self.command(60, 7500), 0.0)
        out = k.apply(self.command(60, 11000), 1.0)   # 같은 방향
        self.assertNotEqual(out.drive_percent, 0)

    def test_it_can_be_turned_off(self):
        k = self.kick(presteer_on_direction_change=False)
        k.apply(self.command(60, 7500), 0.0)
        out = k.apply(self.command(-60, 11000), 1.0)
        self.assertNotEqual(out.drive_percent, 0)

    def test_a_stop_does_not_forget_where_the_wheels_are(self):
        """정지는 서보를 되돌리지 않는다 -- 이력을 지우면 다음 전환을 놓친다."""
        k = self.kick()
        k.apply(self.command(60, 7500), 0.0)
        k.apply(self.command(0, 9000), 0.5)          # 정지
        out = k.apply(self.command(-60, 11000), 1.0)  # 반대 방향으로 출발
        self.assertEqual(out.drive_percent, 0)


if __name__ == "__main__":
    unittest.main()


class SustainFloorTest(unittest.TestCase):
    """구름을 유지하는 하한이 조향 깊이를 따라간다.

    전륜 구동 · 후륜 조향이라, 조향륜이 꺾일수록 바닥을 옆으로 긁는 저항이
    커지고 구동륜이 그만큼 더 밀어야 구름이 유지된다. 직진에서 잰 12% 를
    회전에도 그대로 쓰면 굴러가던 차가 도로 선다.
    """

    def limits(self):
        return TeleopLimits(min_sustain_drive_percent=12,
                            max_sustain_drive_percent=22)

    def test_straight_keeps_the_measured_floor(self):
        """직선과 파렛 진입은 종전 값이어야 한다 -- 진입 깊이가 여기 묶여 있다."""
        self.assertEqual(
            sustain_floor_percent(0.20, 0.0, self.limits()), 12)

    def test_a_deep_turn_raises_the_floor(self):
        deep = sustain_floor_percent(0.20, 2.4, self.limits())
        self.assertGreater(deep, 12)
        self.assertLessEqual(deep, 22)

    def test_deeper_never_asks_for_less(self):
        limits = self.limits()
        floors = [sustain_floor_percent(0.20, w, limits)
                  for w in (0.0, 0.4, 0.9, 1.6, 2.4)]
        self.assertEqual(floors, sorted(floors))

    def test_a_stopped_command_is_not_a_turn(self):
        self.assertEqual(
            sustain_floor_percent(0.0, 2.4, self.limits()), 12)


class SustainFloorValidationTest(unittest.TestCase):
    def test_the_steered_floor_cannot_exceed_the_starting_floor(self):
        """넘으면 '유지' 가 아니라 '출발' 값이 되어 버린다."""
        with self.assertRaises(ValueError):
            TeleopLimits(min_sustain_drive_percent=12,
                         max_sustain_drive_percent=99).validate()

    def test_it_cannot_fall_below_the_straight_floor(self):
        with self.assertRaises(ValueError):
            TeleopLimits(min_sustain_drive_percent=12,
                         max_sustain_drive_percent=5).validate()


class TurningRadiusConsistencyTest(unittest.TestCase):
    """조향 상한이 세 곳에 흩어져 있고, 어긋나면 증상이 조용하다.

        teleop.yaml   min_command_turning_radius_m      명령 곡률 상한
        nav2_params   GridBased.minimum_turning_radius  계획 곡률
        nav2_params   FollowPath.regulated_..._min_radius  감속 시작점

    플래너가 명령 상한보다 급한 경로를 내면 **컨트롤러가 못 따라간다.** 차는
    경로 위에서 헤매다 멎는데, 로그만 봐서는 플래너도 컨트롤러도 정상이다.
    2026-08-09 이전에 실제로 그 상태였다 -- 플래너 0.18(38.7도) vs 명령
    0.10(55.2도).

    값은 tools/steer_limit.py 가 **라이다로 실제 이동을 재서** 정한다.
    엔코더로는 이 경계가 안 보인다 -- 구동륜 회전을 재지 차체 이동을 안 잰다.
    """

    def _nav2(self):
        path = (pathlib.Path(__file__).resolve().parents[3] / "nav2_params.yaml")
        if not path.exists():
            self.skipTest(f"nav2_params.yaml 을 찾지 못했다: {path}")
        with path.open(encoding="utf-8") as handle:
            return yaml.safe_load(handle)

    def _teleop(self):
        path = (pathlib.Path(__file__).resolve().parent.parent
                / "config" / "teleop.yaml")
        if not path.exists():
            self.skipTest("config/teleop.yaml 을 찾지 못했다")
        with path.open(encoding="utf-8") as handle:
            return yaml.safe_load(handle)["uart_teleop_bridge"]["ros__parameters"]

    def test_all_three_radii_agree(self):
        nav2 = self._nav2()
        command = self._teleop()["min_command_turning_radius_m"]
        planner = (nav2["planner_server"]["ros__parameters"]
                   ["GridBased"]["minimum_turning_radius"])
        controller = (nav2["controller_server"]["ros__parameters"]
                      ["FollowPath"]["regulated_linear_scaling_min_radius"])
        self.assertEqual(command, planner,
                         "플래너가 명령 상한보다 급한 경로를 낼 수 있다")
        self.assertEqual(planner, controller,
                         "감속 시작점이 계획 곡률과 어긋난다")

    def test_the_cap_stays_inside_the_servo_envelope(self):
        """명령 상한은 서보 물리 봉투 **안쪽**이어야 한다.

        봉투(rear_steering_limit_deg)는 ALIGN 저속 정렬과 수동 조작이 쓰므로
        좁히지 않는다. 좁히는 것은 주행 중 명령 곡률뿐이다.
        """
        params = self._teleop()
        radius = params["min_command_turning_radius_m"]
        wheelbase = params["wheelbase_m"]
        commanded = math.degrees(math.atan(wheelbase / radius))
        self.assertLess(commanded, params["rear_steering_limit_deg"])

    def test_defaults_match_the_deployed_cap(self):
        """TeleopLimits 기본값이 yaml 과 갈라지면 조용히 틀린 곡률을 쓴다."""
        self.assertEqual(TeleopLimits().min_command_turning_radius_m,
                         self._teleop()["min_command_turning_radius_m"])
