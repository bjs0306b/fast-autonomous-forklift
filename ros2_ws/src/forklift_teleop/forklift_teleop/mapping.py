"""Convert cmd_vel into drive PWM and rear-steering servo commands."""

from dataclasses import dataclass, replace
from typing import Optional
import math


@dataclass(frozen=True)
class ActuatorCommand:
    drive_percent: int
    steering_cdeg: int


@dataclass
class StartupKick:
    """Briefly raise duty when the vehicle starts from a standstill.

    Static friction is higher than rolling friction here, and the gap is wide:
    35% keeps a rolling vehicle rolling but cannot start it, 38% stalls with
    the motor buzzing, and 40% is a coin flip. Only 50% starts reliably. The
    measurement table is in config/teleop.yaml -- do not copy it here.

    Reverse is worse and gets its own figure, but only when the wheels are
    turned. A rear-steered chassis reversing puts the steered wheels in the
    lead, ploughing sideways instead of trailing, and the same 60% that drives
    it forward moved it exactly nothing backwards over five seconds at 45
    degrees. Straight back is ordinary rolling resistance and 60% is enough --
    applying the steered figure to a straight reverse just makes it lurch,
    which is what nav2's BackUp recovery (which commands no steering at all)
    was doing.

    A zero command cancels the kick immediately, so the obstacle guard keeps
    full authority to stop. Re-arming on a direction change matters for the
    BackUp recovery behaviour, which reverses from a standstill.

    A fixed-duration kick is a guess at how long starting takes, and it was
    the wrong guess: nav2 regulates speed down to about 0.037 m/s in a tight
    avoidance, which maps below the duty that can start the vehicle, so when
    the 0.3 s expired the vehicle had not moved and never would. Feeding the
    wheel encoder back in turns the guess into a measurement -- hold the kick
    while the wheels are still not turning -- with a ceiling so a vehicle
    pushed against a wall stops driving current into a stalled motor.
    """

    forward_percent: int
    reverse_percent: int
    duration_sec: float
    reverse_straight_percent: int = 0
    # 조향 명령이 중립으로 바뀐 뒤 서보가 실제로 도달하기까지 기다리는 시간.
    #
    # 할인은 **바퀴가 곧을 때** 성립하는데, 판단은 명령으로 한다. 회전 직후에는
    # 명령만 중립이고 서보는 아직 꺾여 있어서, 그 순간 60% 를 주면 물리적으로
    # 100% 가 필요한 상태에 모자란 값을 주게 된다. 2026-08-08 에 실제로 그랬다:
    # BackUp 이 cmd -0.100/+0.000 을 8초간 냈는데 엔코더는 0.000 이었다.
    steering_settle_sec: float = 0.6
    steering_center_cdeg: int = 0
    straight_steering_tolerance_cdeg: int = 300
    stall_speed_mps: float = 0.01
    max_stall_kick_sec: float = 2.0
    # 한 번 밀어 보고 포기하지 않는다. 위 시간만큼 밀고, 이만큼 쉬었다가 다시
    # 민다. 여전히 안 구르는 동안 계속 반복한다.
    #
    # 2026-08-07: 뒷바퀴 완전 조향으로 출발하려다 2초 창을 놓쳐 남은 22초를
    # 못 뜬 채 보냈다. 앞서 성공한 회전은 약 2초에 뜯겨 나갔으니 간발의
    # 차였다 -- 정지마찰은 바닥 위치마다 달라 한 번의 창으로는 못 건넌다.
    #
    # 창을 늘리는 대신 끊는 이유는 전류다. 계속 밀어 두면 멈춘 모터에 큰
    # 전류를 계속 넣게 된다. 쉬는 구간이 평균을 내려 준다.
    stall_kick_rest_sec: float = 1.0
    # ⚠️ **꺾인 채로는 못 뜬다 -- 그러면 펴고 출발한다.**
    #
    # 이 차는 전륜 구동 · 후륜 조향이다. 조향륜(후륜)은 스스로 굴러갈 힘이
    # 없고, 깊게 꺾일수록 진행 방향과 어긋나 바닥을 옆으로 긁는다. 구동륜
    # (전륜)이 자기 마찰에 더해 그 저항까지 끌어야 하므로, **각도가 깊을수록
    # 출발에 필요한 힘이 커진다.** 실측으로는 완전 조향에서 100% 를 줘도 못 떴다.
    #
    # 듀티를 더 올려 이기려는 것이 종전 방식인데 상한이 있다. 대신 순서를
    # 바꾼다: 조향을 중립으로 펴서 긁는 저항을 없앤 채 출발하고, 엔코더가
    # 구름을 확인하면 원래 곡률을 돌려준다. 구르는 중에는 조향륜이 굴러가며
    # 방향을 바꾸므로 긁지 않는다 -- 완전 조향으로도 잘 돈다는 것이 실측돼 있다.
    #
    # ⚠️ 펴는 동안 차는 **의도한 곡선이 아니라 직진**한다. 그래서 시간 상한을
    #    둔다. 그 안에 못 뜨면 원래 명령을 그대로 통과시켜, 가드와 탈출 후진이
    #    맡게 한다 -- 여기서 무한정 밀면 벽으로 곧장 간다.
    # ⚠️ **기본값 True -> False (2026-08-09). 근거였던 실측이 낡았다.**
    #
    #    "완전 조향에서는 100% 듀티로도 못 뜬다" 는 2026-08-07 측정이다. 그 뒤로
    #    조향 봉투를 ±58° 로 넓히고(protocol.py), 가드의 각속도 상한 0.35 를
    #    풀고, steered_speed_boost 를 1.6 으로 올렸다. 2026-08-09 재측정:
    #
    #        후륜  0.0°  엔코더 0.057   출발
    #        후륜 25.6°  엔코더 0.170   출발
    #        후륜 43.8°  엔코더 0.170   출발
    #        후륜 50.2°  엔코더 0.114   출발
    #
    #    50도로 꺾인 채 정지에서 뜬다. 오히려 중간 각도가 직진보다 빠르다.
    #
    #    켜 두면 해가 된다: 멈출 때마다 조향을 폈다 다시 꺾으므로 밖에서 보면
    #    회전을 못 하고 갈팡질팡한다. 구조는 남겨 둔다 -- 바닥이나 적재
    #    조건이 바뀌어 다시 못 뜨면 파라미터 하나로 되살릴 수 있다.
    straighten_to_start: bool = False
    straighten_max_sec: float = 1.5
    # 이 속도를 넘으면 "굴러가고 있다" 로 보고 조향을 돌려준다. stall_speed_mps
    # 보다 넉넉히 위에 둔다 -- 문턱을 막 넘은 순간에 놓으면 다시 멈춘다.
    straighten_release_mps: float = 0.05
    straightening_since: float = math.inf
    deadline: float = -math.inf
    stall_started: float = -math.inf
    straight_since: float = -math.inf
    last_direction: int = 0

    def _pushing(self, now: float) -> bool:
        """True during a push burst, False during the rest between bursts."""
        if not math.isfinite(self.stall_started):
            return False
        period = self.max_stall_kick_sec + self.stall_kick_rest_sec
        if period <= 0.0:
            return False
        return (now - self.stall_started) % period < self.max_stall_kick_sec

    def _steering_is_straight(self, command: ActuatorCommand) -> bool:
        return (abs(command.steering_cdeg - self.steering_center_cdeg)
                <= self.straight_steering_tolerance_cdeg)

    def _steering_has_settled(
        self, command: ActuatorCommand, now: float
    ) -> bool:
        """Straight long enough that the servo is actually there.

        The wait exists because the wheels were just turned, so a kick that
        has never seen a turned command does not owe one -- straight_since
        starts at -inf meaning "straight all along". A turn sets it to +inf,
        which the next straight command replaces with the current time.
        """
        if not self._steering_is_straight(command):
            self.straight_since = math.inf
            return False
        if self.straight_since == math.inf:
            self.straight_since = now
        return now - self.straight_since >= self.steering_settle_sec

    def _straighten(
        self,
        command: ActuatorCommand,
        now: float,
        measured_speed_mps: Optional[float],
        stalled: bool,
    ) -> Optional[ActuatorCommand]:
        """펴고 출발하는 구간이면 그 명령을, 아니면 None 을 돌려준다.

        ⚠️ **엔코더가 없으면 아예 하지 않는다.** 구르기 시작한 것을 볼 수 없으면
           언제 조향을 돌려줘야 할지도 모른다. 그 상태에서 펴 버리면 의도한
           곡선 대신 직진을 계속하게 되고, 그건 못 도는 것보다 나쁘다.
        """
        if not self.straighten_to_start or measured_speed_mps is None:
            return None
        if self._steering_is_straight(command):
            self.straightening_since = math.inf
            return None

        if abs(measured_speed_mps) >= self.straighten_release_mps:
            # 굴러가고 있다. 조향을 돌려준다 -- 여기서부터는 꺾여도 돈다.
            self.straightening_since = math.inf
            return None

        if self.straightening_since == math.inf:
            if not stalled:
                return None
            self.straightening_since = now
        elif now - self.straightening_since >= self.straighten_max_sec:
            # 상한을 넘겼다. 펴서도 못 뜨는 것이므로 원래 명령을 통과시켜
            # 가드와 탈출 후진에 넘긴다.
            return None

        percent = (self.forward_percent if command.drive_percent > 0
                   else -self.reverse_percent)
        return replace(
            command,
            steering_cdeg=self.steering_center_cdeg,
            drive_percent=(max(command.drive_percent, percent)
                           if command.drive_percent > 0
                           else min(command.drive_percent, percent)),
        )

    def apply(
        self,
        command: ActuatorCommand,
        now: float,
        measured_speed_mps: Optional[float] = None,
    ) -> ActuatorCommand:
        direction = (
            1 if command.drive_percent > 0
            else -1 if command.drive_percent < 0
            else 0
        )
        if direction == 0:
            self.deadline = -math.inf
            self.stall_started = -math.inf
            self.straightening_since = math.inf
            self.last_direction = 0
            return command
        if direction != self.last_direction:
            self.deadline = now + self.duration_sec
            self.stall_started = now
            self.straightening_since = math.inf
        self.last_direction = direction
        stalled = (measured_speed_mps is not None
                   and abs(measured_speed_mps) < self.stall_speed_mps
                   and self._pushing(now))

        # ⚠️ **조향 이력은 어느 분기로 가든 먼저 기록한다.** _steering_has_settled
        #    는 판단이면서 동시에 "언제부터 곧았나" 를 적는다. 아래에서 일찍
        #    돌아가며 이걸 건너뛰었더니, 꺾인 명령이 지나갔다는 사실이 안 남아
        #    다음 직진 명령이 곧바로 할인(reverse_straight_percent)을 받았다 --
        #    서보가 아직 꺾여 있는데 모자란 듀티를 주는 바로 그 실패다.
        settled = self._steering_has_settled(command, now)

        straightened = self._straighten(command, now, measured_speed_mps,
                                        stalled)
        if straightened is not None:
            return straightened

        if stalled and direction > 0 and not self._steering_is_straight(command):
            # A forward start with the wheels turned is the same sideways
            # scrub that makes reverse expensive: the 50% that starts this
            # vehicle straight was measured straight. Borrow the steered
            # figure rather than keep pushing a value already known to fail.
            return replace(
                command,
                drive_percent=max(command.drive_percent,
                                  self.reverse_percent
                                  if self.reverse_percent > self.forward_percent
                                  else self.forward_percent),
            )
        if stalled:
            # Commanded but not rolling: still starting, whatever the clock
            # says. Without an encoder reading this branch never runs and the
            # kick stays purely time-based.
            self.deadline = max(self.deadline, now + self.duration_sec)
        if now >= self.deadline:
            return command
        if direction > 0:
            return replace(
                command,
                drive_percent=max(command.drive_percent, self.forward_percent),
            )
        # Reverse duty is negative, so the stronger command is the smaller one.
        percent = (
            self.reverse_straight_percent
            if settled and self.reverse_straight_percent > 0
            else self.reverse_percent
        )
        return replace(
            command,
            drive_percent=min(command.drive_percent, -percent),
        )


@dataclass
class SpeedController:
    """Trim drive duty until the wheels actually turn at the commanded speed.

    Duty is otherwise a guess: the map from speed to duty is a straight line
    fitted on one floor at one load, and it is wrong in both directions at
    once. Below about 0.08 m/s the commanded duty cannot break static
    friction and the vehicle does not move at all; wind the speed up until it
    does and the same duty carries it far too fast down the straight that
    follows. The measured gap between the two is what the encoder closes.

    Integral only, deliberately. Static friction is a threshold, not a gain:
    while the vehicle is stuck the error stays put and the integral climbs
    until duty crosses the threshold, then falls back once it rolls. A
    proportional term would just add a constant offset to a stalled vehicle
    and never get there.

    Magnitudes throughout, never signs. The encoder's direction has not been
    verified and this must not depend on it -- how fast the wheels turn is all
    that is being controlled here. Direction stays with map_twist.
    """

    percent_per_mps_second: float = 500.0
    max_bias_percent: int = 40
    deadband_mps: float = 0.005
    integral: float = 0.0
    last_time: float = -math.inf
    last_direction: int = 0

    def reset(self) -> None:
        self.integral = 0.0
        self.last_direction = 0

    def bias(
        self,
        target_mps: float,
        measured_mps: Optional[float],
        now: float,
    ) -> int:
        """Return duty percent to add in the direction of travel."""
        direction = (
            1 if target_mps > self.deadband_mps
            else -1 if target_mps < -self.deadband_mps
            else 0
        )
        if direction == 0 or measured_mps is None:
            # A stop must not leave wound-up duty waiting for the next start,
            # and with no encoder this stays open loop.
            self.reset()
            self.last_time = now
            return 0
        if direction != self.last_direction:
            self.integral = 0.0
            self.last_direction = direction
            self.last_time = now
            return 0

        # Clamp the step: a stalled reader or a scheduling hiccup must not
        # dump a whole second of error into the integral at once.
        elapsed = now - self.last_time
        self.last_time = now
        if not 0.0 < elapsed <= 0.5:
            return round(self.percent_per_mps_second * self.integral)

        self.integral += (abs(target_mps) - abs(measured_mps)) * elapsed
        limit = self.max_bias_percent / self.percent_per_mps_second
        self.integral = _clamp(self.integral, -limit, limit)
        return round(self.percent_per_mps_second * self.integral)


@dataclass(frozen=True)
class TeleopLimits:
    max_linear_mps: float = 0.40
    # ⚠️ 0.35 → 0.75 (2026-08-05, S15P11A304-152). teleop.yaml 이 0.75 로 올라갈 때
    #    여기만 안 따라왔다 — 아래 steering_* 주석이 경고한 바로 그 자리다.
    #    상한을 올린 것이지 명령을 키운 것이 아니다: 전진 조향은 fork_servo 의
    #    MAX_ANGULAR(0.35)이 그대로 자르고, 후진만 RETREAT_MAX_ANGULAR(0.60)를 쓴다.
    max_angular_rps: float = 2.50
    linear_deadband_mps: float = 0.01
    wheelbase_m: float = 0.144
    # ⚠️ 이 값은 아래 steering_min/max 로 표현 가능한 각도와 **같아야 한다**
    #    (중립 9000 ± 3600 cdeg = ±36°). 어긋나면 turn_ratio 1.0 이 다른 각도를
    #    뜻하게 돼 "최대 조향" 이 실제 최대가 아니게 된다.
    rear_steering_limit_deg: float = 58.0
    # 2026-08-04 실측으로 50 → 35 (S15P11A304-198). 근거·측정표는
    # config/teleop.yaml 주석에 있다 — 여기 옮겨 적으면 갈라진다.
    min_drive_percent: int = 35
    # 구르는 것을 **유지**하는 데 드는 최소 듀티. 출발에 드는 값과 다르다.
    #
    # min_drive_percent 는 정지마찰을 이기는 값이고, 구름마찰은 그보다 훨씬
    # 작다. 둘을 한 값으로 묶어 두면 차가 35% 아래로 못 내려가고, 바닥에서
    # 35% 는 이미 0.17 m/s 다 -- 목표가 0.08 이어도 두 배로 간다.
    #
    # 출발은 SpeedController 의 적분과 StartupKick 이 맡으므로, 일단 구르고
    # 나면 여기까지 내려갈 수 있어야 한다.
    min_sustain_drive_percent: int = 12
    # 조향륜이 완전히 꺾였을 때의 유지 하한. 위 12 는 조향륜이 **펴진** 직진에서
    # 잰 값이다. 깊게 꺾이면 조향륜이 바닥을 옆으로 긁어 구름을 유지하는 데
    # 필요한 힘 자체가 커지고, 12% 로는 굴러가던 차가 도로 선다.
    #
    # ⚠️ **전역으로 올리지 말 것.** 12 에는 파렛 진입 속도가 묶여 있다
    #    (INSERT_SPEED 가 이 하한에 걸려 실제 진입 듀티를 정한다). 조향각에
    #    비례해서만 올려야 직진·진입 구간이 종전 그대로 남는다.
    max_sustain_drive_percent: int = 35
    max_drive_percent: int = 100
    # 정지마찰을 이기는 동안만 쓰는 상한. 위 값은 **순항** 상한이다.
    #
    # 위 주석대로 60 에는 INSERT_SPEED_ACTUAL·진입 깊이·조향 중립이 전부
    # 묶여 있어 올릴 수 없다. 그런데 그건 굴러가는 동안의 이야기이고, 뒷바퀴가
    # 36° 완전히 누운 채 출발하는 것은 다른 영역이다 -- 조향륜이 바닥을 옆으로
    # 긁어서, 실측상 꺾인 후진은 100% 가 필요했다.
    #
    # 2026-08-07: 60 에 막혀 회전이 아예 시작되지 않았다. 25초 동안 명령은
    # 나가는데 엔코더 0.000, 회전 -2° 에서 멈춤. 이 값은 엔코더가 "안 구른다"
    # 고 말하는 동안만 쓰이고, 구르기 시작하면 곧바로 위 값으로 돌아온다.
    max_start_drive_percent: int = 100
    # ⚠️ **크게 꺾으면 직선 주행 속도로는 안 돈다** (2026-08-07 실측).
    #
    # 모터 토크가 약해서 뒷바퀴가 크게 누우면 바닥을 옆으로 긁는 저항을 못
    # 이긴다. 그래서 **조향각에 비례해 속도를 올린다** -- 중립이면 1.0배,
    # 최대 조향이면 이 배수다.
    #
    # 조향각으로 실시간 결정되므로 회전을 빠져나와 핸들이 펴지면 그 순간
    # 배수도 1.0 으로 돌아온다. 전역 최소 속도를 올려 우회하면 직선 구간에
    # 그 속도가 그대로 남아 과속하는데(그렇게 벽을 받았다) 여기엔 그 문제가
    # 없다.
    #
    # 조향각 자체는 원래 명령한 곡률로 정하고 속도만 올리므로, 따라가는 호는
    # 그대로이고 그 위를 더 빨리 지날 뿐이다.
    # 조향륜이 깊게 꺾일수록 목표 속도를 올린다. 중립이면 1.0 배라 직선은
    # 그대로다. 근거와 실측은 config/teleop.yaml 에 있다.
    steered_speed_boost: float = 2.5
    # ⚠️ **정지 상태에서 뒷바퀴가 완전히 누우면 못 출발한다** (2026-08-07 실측,
    #    100% 듀티까지 확인). 굴러가는 중이면 완전 조향으로도 잘 돈다 -- 안 되는
    #    것은 그 상태로 서 있다가 출발하는 것 하나뿐이다.
    #
    # 그 상태는 컨트롤러가 저속에서 큰 곡률을 명령할 때 생긴다. 곡률은
    # 각속도/선속도라 **선속도가 작아질수록 같은 각속도가 더 큰 조향을 만든다**:
    # 0.08 m/s 에 0.35 rad/s 면 반경 0.23 m 로 거의 완전 조향이다.
    #
    # nav2 의 minimum_turning_radius 는 **경로**를 제약할 뿐, 컨트롤러가 그
    # 경로를 따라가며 내는 순간 곡률은 제약하지 않는다. 그래서 여기서 막는다.
    #
    # 0 이면 끈다. 기계 한계는 0.198 m 이고, 출발까지 감안한 여유값을 넣는다.
    min_command_turning_radius_m: float = 0.25
    # ⚠️ **후진은 상한이 다르다** (2026-08-05, S15P11A304-152).
    #
    # 실측: 같은 60% 로 전진 0.178 m/s · 후진 0.033 m/s — **19%** 다. 바닥을 바꿔도
    # 같아서 구조에서 오는 차이로 확인됐다. 뒷바퀴 조향차는 후진할 때 뒷바퀴가
    # **앞장서서**(leading) 바닥을 파고들고, 전진에서는 끌려온다(trailing).
    #
    # ⚠️ **전진 상한은 안 올린다.** 오늘 실측한 INSERT_SPEED_ACTUAL · 진입 깊이 ·
    #    조향 중립이 전부 전진 60% 기준이라 같이 올리면 그 값들이 무효가 된다.
    max_drive_percent_reverse: int = 100
    # ⚠️ **이 기본값들은 계속 낡은 채 방치되는 자리다.** 2026-08-04 오후까지
    #    10000/8500/11500(중립 ±15°) 이었고, 197·198 로 실제 설정이 두 번 바뀌는
    #    동안 여기만 안 따라왔다. 2026-08-05 에 또 한 번 그랬다.
    #
    #    브리지는 항상 teleop.yaml 을 명시적으로 넘기므로 **동작에는 영향이 없다.**
    #    그래서 아무도 안 본다. 인자 없이 `TeleopLimits()` 를 만드는 코드가 하나
    #    생기는 순간 **조용히 틀린 조향값**을 쓰게 된다.
    #
    # 2026-08-05 (S15P11A304-152): 9400/6600/12200 → 9000/5400/12600.
    #   중립 9000 은 펌웨어 펄스 범위(500~2500) 수정 후 실주행으로 다시 잡은 값이고,
    #   ±3600 은 그 뒤 실물에서 확인한 가동 범위다. 근거·측정표는 config/teleop.yaml
    #   주석에 있다 — 여기 옮겨 적으면 갈라진다.
    steering_center_cdeg: int = 9000
    steering_min_cdeg: int = 3200
    steering_max_cdeg: int = 14800

    def validate(self) -> None:
        if self.max_linear_mps <= 0.0 or self.max_angular_rps <= 0.0:
            raise ValueError("maximum velocities must be positive")
        if not 0 <= self.linear_deadband_mps < self.max_linear_mps:
            raise ValueError("linear deadband is invalid")
        if self.wheelbase_m <= 0.0:
            raise ValueError("wheelbase must be positive")
        if not 0.0 < self.rear_steering_limit_deg < 89.0:
            raise ValueError("rear steering limit is invalid")
        # 상한은 펌웨어 TELEOP_MAX_DRIVE_PERCENT · protocol.DRIVE_PERCENT_LIMIT 과
        # 같은 100 이다. 그보다 좁게 두면 여기서 막혀 후진 힘을 못 쓴다.
        if self.steered_speed_boost < 1.0:
            raise ValueError("steered_speed_boost must be at least 1.0")
        if self.min_command_turning_radius_m < 0.0:
            raise ValueError(
                "min_command_turning_radius_m cannot be negative"
            )
        if not 0 <= self.min_sustain_drive_percent <= self.min_drive_percent:
            raise ValueError(
                "min_sustain_drive_percent must be between 0 and "
                "min_drive_percent"
            )
        # 완전 조향 하한은 직진 하한 이상이어야 하고, 출발 하한을 넘으면
        # "유지" 가 아니라 "출발" 값이 되어 버린다.
        if not (self.min_sustain_drive_percent
                <= self.max_sustain_drive_percent
                <= self.min_drive_percent):
            raise ValueError(
                "max_sustain_drive_percent 는 min_sustain_drive_percent 와 "
                "min_drive_percent 사이여야 한다"
            )
        if not (self.max_drive_percent <= self.max_start_drive_percent
                <= 100):
            raise ValueError(
                "max_start_drive_percent must be between max_drive_percent "
                "and 100"
            )
        if not 0 <= self.min_drive_percent <= self.max_drive_percent <= 100:
            raise ValueError("drive percentage limits are invalid")
        if not self.min_drive_percent <= self.max_drive_percent_reverse <= 100:
            raise ValueError("reverse drive percentage limit is invalid")
        # 서보 물리 안전 범위(펌웨어 config.h: SERVO_MIN/MAX_ANGLE_DEG = 30~150°).
        # cdeg = 도 × 100 이므로 3000~15000.
        #
        # ⚠️ 종전에는 8500~11500 으로 **훨씬 좁게** 박혀 있었다. 그 값은 "서보
        #    원점이 곧 기구 직진" 이라는 가정에서 나온 것인데, 실제로는 혼이 약 15°
        #    틀어져 끼워져 있어 **물리 직진이 11500(상한)** 이었다. 그래서 오른쪽
        #    조향을 표현할 수가 없었다 — 검증이 그 범위를 막고 있었기 때문이다
        #    (2026-08-04, S15P11A304-197).
        #
        # 좁은 상수로 두 번 막을 이유가 없다. 서보 보호는 펌웨어가 한다.
        if not (
            3000 <= self.steering_min_cdeg
            <= self.steering_center_cdeg
            <= self.steering_max_cdeg
            <= 15000
        ):
            raise ValueError(
                "steering limits are invalid: "
                f"min={self.steering_min_cdeg} center={self.steering_center_cdeg} "
                f"max={self.steering_max_cdeg} (허용 3000~15000, min≤center≤max)")


def _clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(value, maximum))


@dataclass
class TurnSpeedBoost:
    """Wind speed up while a turn is not actually turning, then let it go.

    A multiplier fixed to the steering angle is the wrong shape: it is either
    too small to get the vehicle round or, set large enough to work, it is
    already large the instant the wheels move off centre. What matters is not
    how far the wheels are turned but how long the vehicle has been failing
    to make the turn -- so this accumulates while that is true and unwinds as
    soon as it is not.

    It multiplies linear and angular together, never one alone. Curvature is
    their ratio, so scaling both leaves the steering angle exactly where the
    planner put it and the vehicle traverses the same arc faster. Scaling
    only the speed would straighten the wheels, which is the opposite of what
    a turn that is not turning needs.

    The result is still capped at max_linear_mps by map_twist, and that limit
    is what the stopping distance in obstacle_avoidance.yaml was derived from
    (tools/geometry_limits.py) -- so this can spend the guard's slowdown
    margin but never the braking margin.
    """

    gain_per_second: float = 0.8
    decay_per_second: float = 2.0
    max_multiplier: float = 2.5
    engage_turn_ratio: float = 0.25
    keeping_up_fraction: float = 0.7
    multiplier: float = 1.0
    last_time: float = -math.inf

    def update(
        self,
        target_mps: float,
        measured_mps: Optional[float],
        turn_ratio: float,
        now: float,
    ) -> float:
        elapsed = now - self.last_time
        self.last_time = now
        if not 0.0 < elapsed <= 0.5:
            return self.multiplier

        turning = (turn_ratio >= self.engage_turn_ratio
                   and abs(target_mps) > 1e-6)
        behind = (measured_mps is None
                  or abs(measured_mps)
                  < abs(target_mps) * self.keeping_up_fraction)
        if turning and behind:
            self.multiplier += self.gain_per_second * elapsed
            self.multiplier = _clamp(self.multiplier, 1.0, self.max_multiplier)
        else:
            # Dropped, not decayed. A wound-up multiplier that unwinds over
            # some tenths of a second keeps overspeeding after the wheels have
            # gripped, and the extra yaw carries the vehicle past the heading
            # it was turning to. The extra speed exists to break out of a turn
            # that is not happening; the moment it is happening there is
            # nothing left for it to do. The step is abrupt, which at a tenth
            # of a metre per second costs a jolt and buys back the overshoot.
            self.multiplier = 1.0
        return self.multiplier

    def reset(self) -> None:
        self.multiplier = 1.0

    def limited(
        self,
        multiplier: float,
        linear_x: float,
        angular_z: float,
        limits: "TeleopLimits",
    ) -> float:
        """Shrink the multiplier until neither limit clips.

        map_twist bounds linear and angular separately, so if one saturates
        and the other does not, their ratio changes -- and that ratio is the
        curvature. Boosting into the angular limit would steer harder than
        the planner asked, which is the opposite of leaving its path alone.
        """
        if abs(linear_x) > 1e-9:
            multiplier = min(multiplier,
                             limits.max_linear_mps / abs(linear_x))
        if abs(angular_z) > 1e-9:
            multiplier = min(multiplier,
                             limits.max_angular_rps / abs(angular_z))
        return max(1.0, multiplier)


def rear_steering_angle(
    linear_x: float,
    angular_z: float,
    limits: "TeleopLimits",
) -> float:
    """Rear-wheel angle for a commanded curvature, in radians."""
    return -math.atan(limits.wheelbase_m * (angular_z / linear_x))


def steering_turn_ratio(
    linear_x: float,
    angular_z: float,
    limits: "TeleopLimits",
) -> float:
    """How far toward full lock this command bends the rear wheels, 0..1.

    Shared so a caller outside map_twist can ask "is this a turn?" without
    reimplementing the geometry and drifting away from it.
    """
    if abs(linear_x) < 1e-9 or abs(angular_z) < 1e-9:
        return 0.0
    limit = math.radians(limits.rear_steering_limit_deg)
    return _clamp(
        abs(rear_steering_angle(linear_x, angular_z, limits)) / limit, 0.0, 1.0
    )


def sustain_floor_percent(
    linear_x: float,
    angular_z: float,
    limits: "TeleopLimits",
) -> int:
    """구름을 유지하는 최소 듀티. 조향이 깊을수록 커진다.

    전륜 구동 · 후륜 조향이라, 조향륜이 꺾일수록 바닥을 옆으로 긁는 저항이
    커지고 구동륜이 그만큼 더 밀어야 구름이 유지된다. 직진에서 잰 하한을
    그대로 쓰면 회전 중에 굴러가던 차가 도로 선다.

    중립이면 min_sustain_drive_percent 그대로이므로 직선 구간과 파렛 진입은
    종전 값을 유지한다 -- 그쪽에는 진입 깊이가 묶여 있어 바꾸면 안 된다.
    """
    ratio = steering_turn_ratio(linear_x, angular_z, limits)
    span = limits.max_sustain_drive_percent - limits.min_sustain_drive_percent
    return limits.min_sustain_drive_percent + int(round(span * ratio))


def map_twist(
    linear_x: float,
    angular_z: float,
    limits: TeleopLimits = TeleopLimits(),
) -> ActuatorCommand:
    """Map Twist values to signed PWM and a rear-steering servo command.

    Positive rear-wheel angle points left. For a rear-steered vehicle,
    delta_rear = -atan(wheelbase * angular_z / linear_x). The installed
    linkage maps negative rear-wheel angle toward the larger servo command.
    """
    limits.validate()

    if abs(linear_x) < limits.linear_deadband_mps:
        return ActuatorCommand(0, limits.steering_center_cdeg)

    bounded_linear_x = math.copysign(
        min(abs(linear_x), limits.max_linear_mps),
        linear_x,
    )
    bounded_angular_z = _clamp(
        angular_z,
        -limits.max_angular_rps,
        limits.max_angular_rps,
    )
    # 실행 가능한 곡률 안으로 각속도를 깎는다. 선속도가 작을수록 상한도 작아야
    # 같은 반경이 나온다 -- 이걸 안 하면 저속에서 곡률이 무한정 커진다.
    #
    # 선속도는 건드리지 않는다. 여기서 막으려는 것은 "너무 빠름" 이 아니라
    # "이 차가 낼 수 없는 조향" 이고, 속도를 줄이면 곡률은 오히려 더 커진다.
    if limits.min_command_turning_radius_m > 0.0:
        angular_ceiling = (abs(bounded_linear_x)
                           / limits.min_command_turning_radius_m)
        bounded_angular_z = _clamp(
            bounded_angular_z, -angular_ceiling, angular_ceiling
        )

    # 조향각을 먼저 정한다. 속도를 올려도 이 각은 그대로 두어야 따라가는 호가
    # 안 바뀐다 -- 같은 호를 더 빨리 지나는 것이 목적이다.
    straight = abs(bounded_angular_z) < 1e-6
    if straight:
        turn_ratio = 0.0
    else:
        rear_steering_angle_rad = rear_steering_angle(
            bounded_linear_x, bounded_angular_z, limits
        )
        turn_ratio = steering_turn_ratio(
            bounded_linear_x, bounded_angular_z, limits
        )

    boost = 1.0 + turn_ratio * (limits.steered_speed_boost - 1.0)
    boosted_linear_x = math.copysign(
        min(abs(bounded_linear_x) * boost, limits.max_linear_mps),
        bounded_linear_x,
    )
    speed_ratio = _clamp(
        abs(boosted_linear_x) / limits.max_linear_mps,
        0.0,
        1.0,
    )
    # 후진은 상한이 다르다 — 같은 듀티로 훨씬 덜 나간다(TeleopLimits 주석 참조).
    max_percent = (limits.max_drive_percent if bounded_linear_x > 0.0
                   else limits.max_drive_percent_reverse)
    drive_magnitude = round(
        limits.min_drive_percent
        + speed_ratio * (max_percent - limits.min_drive_percent)
    )
    drive_percent = (
        drive_magnitude if bounded_linear_x > 0.0 else -drive_magnitude
    )

    if straight:
        return ActuatorCommand(drive_percent, limits.steering_center_cdeg)

    servo_direction = -1 if rear_steering_angle_rad > 0.0 else 1

    if servo_direction > 0:
        steering_span = (
            limits.steering_max_cdeg - limits.steering_center_cdeg
        )
    else:
        steering_span = (
            limits.steering_center_cdeg - limits.steering_min_cdeg
        )

    steering_cdeg = (
        limits.steering_center_cdeg
        + servo_direction * round(turn_ratio * steering_span)
    )
    return ActuatorCommand(drive_percent, steering_cdeg)


def select_command(
    linear_x: float,
    angular_z: float,
    command_age_sec: float,
    timeout_sec: float,
    limits: TeleopLimits = TeleopLimits(),
) -> ActuatorCommand:
    """Return a centered stop when the latest Twist is stale."""
    if timeout_sec <= 0.0:
        raise ValueError("command timeout must be positive")
    if command_age_sec > timeout_sec:
        return map_twist(0.0, 0.0, limits)
    return map_twist(linear_x, angular_z, limits)
