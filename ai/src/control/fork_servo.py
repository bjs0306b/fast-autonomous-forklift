"""포크 정렬 제어 루프 — 비주얼 서보잉 상태기계 (S15P11A304-152).

`fork_align`이 낸 정렬 오차를 **주행 명령(`cmd_vel`)으로 닫는다.** 143이 오차 산출까지,
이 모듈이 오차 → 속도·조향, 그리고 언제 진입할지 판단하는 상태기계다.

    from control.fork_servo import ForkServo

    servo = ForkServo()
    cmd = servo.step(error, dt=0.045)        # error=None이면 타깃 소실
    twist.linear.x, twist.angular.z = cmd.linear_x, cmd.angular_z

`load_balance`·`fork_align`과 같은 방침으로 **ROS 의존이 없는 순수 상태기계**다 —
노드 없이 테스트된다. ROS2 배선은 `ros2_ws/.../fork_align_node.py`.

## 설계 제약 — 뒷바퀴 조향 Ackermann

**제자리 회전이 안 된다.** 파렛트 코앞에서 요각을 고칠 수 없으므로 **진입 전에 정렬을
끝내고 직선으로 들어가야 한다**(Jira 152). 상태기계가 그 순서를 강제한다:

    SEARCH ──타깃 획득──> APPROACH ──근접──> ALIGN ──정렬 완료──> INSERT ──> DONE
       ^                     |                |                    (개루프)
       └──── 소실 ───────────┴────────────────┘
                             └── 못 맞추고 너무 가까움 ──> ABORT

**INSERT는 래치된다.** 코앞에서는 구멍이 화면 밖으로 나가 검출이 끊기므로 되돌아갈 수
없다 — 들어가기로 했으면 눈을 감고 직진한다(153 블라인드 진입). 포크 테이퍼가 상하
자기정렬을 해주는 것이 그 물리적 근거다.

⚠️ **게인·임계는 전부 미조정 기본값이다.** 실물 없이 정할 수 없다(카메라 장착 높이·
각도, 바퀴 슬립, 서보 응답). 아래 값은 **부호와 구조가 맞는지 보기 위한 자리표시자**이지
현장 값이 아니다. `docs/ai/onboard-fork-align-design.md` §4의 미측정 항목 참조.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from perception.fork_align import AlignError

# --- 속도(m/s). 상한은 teleop과 같은 0.20을 넘지 않는다. ---
CRUISE_SPEED = 0.12
ALIGN_SPEED = 0.06
INSERT_SPEED = 0.05

# --- 제어 게인 (rad/s per 단위 오차). 미조정. ---
K_LATERAL = 0.45
K_YAW = 0.60
MAX_ANGULAR = 0.35          # teleop 상한과 동일

ALIGN_YAW_BOOST = 2.0
"""ALIGN에서 요 게인에 곱하는 배수.

두 항을 그냥 더하면 **균형점에 수렴할 뿐 둘 다 0이 되지는 않는다**(`steering_for` 참조).
좌우 오차는 진입 직전까지 계속 볼 수 있지만 요는 코앞에서 못 고치므로(제자리 회전
불가), 마지막 구간에서는 **요를 먼저 죽이는 쪽으로 가중치를 옮긴다.**"""

# --- 수렴 판정 ---
LATERAL_TOLERANCE = 0.12
"""|lateral_ratio| 허용치. 진입면 반폭의 12%.

여유 ±11.5mm를 개구 폭 35mm의 반(17.5mm)으로 나누면 0.66이라 기하학적 한계는 훨씬
느슨하다. 0.12는 그 한계의 1/5로 잡은 **보수적 값**이다 — 카메라·포크 장착 오차와
진입 중 슬립이 그 사이를 먹는다. 실물에서 실패율을 보고 풀 것."""

YAW_TOLERANCE = 0.08
"""|yaw_signal| 허용치. 폭 비 기준이라 각도가 아니다.

⚠️ **DoD의 "±5° 이내"로 환산되지 않는다** — 카메라 내부 파라미터가 없어서다
(`AlignError.yaw_deg`가 None인 이유). 캘리브레이션이 끝나면 `yaw_deg` 기준으로
바꾸고 이 값은 버린다."""

# --- 거리 임계 ---
#
# **두 벌이 있고, 방향이 반대다.** 캘리브레이션 전에는 화면 폭(px)밖에 없고, 초점거리가
# 들어오면 실제 거리(mm)를 쓸 수 있다.
#
#   approach_px  : 가까울수록 **커진다** → `>=` 로 비교
#   distance_mm  : 가까울수록 **작아진다** → `<=` 로 비교
#
# 부호가 뒤집히는 지점이라 실수하기 쉽다. `_reached()` 한 곳에서만 비교한다.
#
# ⚠️ px 임계는 **카메라·해상도·장착 높이에 종속**이라 카메라를 조금만 옮겨도 무의미해진다.
# mm 임계는 자로 잴 수 있고 카메라를 옮겨도 살아남는다 — **캘리브레이션 후에는 mm를 쓴다.**
INSERT_ENTER_MM = 180.0
"""(캘리 후) 이 거리 이내면 진입 가능. 여기서 정렬이 안 돼 있으면 ABORT.
**실측 대상** — 너무 가까우면 구멍이 화면 밖으로 나가 정렬 판정 자체를 못 한다."""

ALIGN_ENTER_MM = 650.0
"""(캘리 후) 파렛트까지 이 거리 이내면 정렬 단계로. **실측 대상.**

⚠️ 값은 임의로 고르면 안 되고 **아래 산수를 만족해야 한다.** 최소 회전반경 53.7cm 기준
좌우 오프셋 10cm 를 지우는 데 **전진 45cm** 가 필요하다. 그 거리는 ALIGN 구간에서만
벌 수 있으므로(INSERT 는 직선 개루프),

    ALIGN_ENTER_MM ≥ INSERT_ENTER_MM + 450 = 180 + 450 = 630

종전 값 350 은 이 조건을 깨고 있었다 — ALIGN 구간이 350-180 = **170mm 뿐**이라
10cm 틀어져 있으면 기하학적으로 못 고치고 **반드시 ABORT** 했다. 근거를 적어두고
값은 그 근거를 어긴 채로 두면 자리표시자가 아니라 버그다(2026-08-03)."""

# 캘리브레이션 전 폭(px) 임계 — **위 mm 값에서 유도한다.**
#
#   span_px = focal_px × HOLE_SPACING_MM / distance_mm     (fork_align.AlignError 참조)
#           = 1277.65 × 48.5 / distance_mm
#
# ⚠️ 두 벌을 각자 손으로 고르면 **같은 이름인데 다른 거리**가 된다. 실제로 그랬다 —
# 종전 260/420px 는 각각 238mm/148mm 라, `--focal-px` 를 주느냐 마느냐로 동작이 달라졌다.
# 카메라·해상도가 바뀌면 px 쪽은 무의미해지므로 **캘리 후에는 mm 를 쓴다.**
_SPAN_PX_AT_1MM = 1277.65 * 48.5

ALIGN_ENTER_PX = _SPAN_PX_AT_1MM / ALIGN_ENTER_MM     # ≈ 95px
"""(캘리 전) 진입면 폭이 이보다 크면 정렬 단계로. `ALIGN_ENTER_MM` 에서 유도."""

INSERT_ENTER_PX = _SPAN_PX_AT_1MM / INSERT_ENTER_MM   # ≈ 344px
"""(캘리 전) 진입면 폭이 이보다 크면 진입 가능 거리. `INSERT_ENTER_MM` 에서 유도."""

INSERT_DURATION_S = 2.5
"""개루프 직진 시간. 거리/속도로 잡되 실물에서 잰다."""

LOST_GRACE_S = 0.25
"""타깃을 놓쳐도 이 시간은 직전 명령을 유지한다. `TargetTracker`의 유예와 짝이다."""


class Phase(Enum):
    SEARCH = "search"
    APPROACH = "approach"
    ALIGN = "align"
    INSERT = "insert"
    DONE = "done"
    ABORT = "abort"


@dataclass(frozen=True)
class DriveCommand:
    """ROS `geometry_msgs/Twist`의 두 성분. ROS 관례대로 **+angular_z는 좌회전**이다."""

    linear_x: float = 0.0
    angular_z: float = 0.0
    phase: Phase = Phase.SEARCH
    reason: str = ""


@dataclass
class Episode:
    """정렬 시도 한 번의 기록 — 154(성공·실패 판정)와 나중의 학습용.

    **"어떤 상태에서 어떻게 움직였고 결과가 뭐였나"를 남긴다.** 154가 판정 근거로
    필요로 하는 것과 같은 데이터라 추가 비용이 없고, 모방학습을 나중에 붙일 여지를
    닫지 않는다. (강화학습을 지금 쓰자는 뜻은 아니다 — 이 문제는 기하로 풀린다.)
    """

    samples: list[tuple[float, AlignError | None, DriveCommand]] = field(default_factory=list)
    outcome: str = ""

    def record(self, t: float, error: AlignError | None, cmd: DriveCommand) -> None:
        self.samples.append((t, error, cmd))


def _clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(v, hi))


def steering_for(error: AlignError, k_lateral: float = K_LATERAL,
                 k_yaw: float = K_YAW, max_angular: float = MAX_ANGULAR) -> float:
    """정렬 오차 → 각속도(rad/s). 부호 유도가 핵심이라 근거를 적어둔다.

    카메라를 원점, 전방을 +Y, 오른쪽을 +X로 둔다. 파렛트 면이 반시계로 θ만큼 돌면
    오른쪽 구멍이 더 멀어지고(깊이 D + S/2·sinθ) 화면에서 좁게 보인다 →
    `yaw_signal > 0`. 그때 면의 바깥 법선은 (+sinθ, −cosθ), 즉 **우리 기준 오른쪽**을
    향한다. 그 법선 위로 올라가려면 **오른쪽으로 돌아 들어가야** 한다.

    좌우 오차도 같다 — 파렛트가 화면 오른쪽에 있으면(`lateral_ratio > 0`) 오른쪽으로
    가야 한다. **두 항의 부호가 같고**, ROS 관례에서 우회전은 음의 각속도다.

    두 항을 더하는 것은 "파렛트 앞 standoff 점을 향해 달린다"의 1차 근사다. 오른쪽으로
    돌아 들어가면 파렛트가 화면 왼쪽으로 흐르며 첫 항이 반대로 작용해 저절로 감쇠한다.
    ⚠️ 대신 **두 항이 균형을 이루는 지점에 수렴할 뿐 둘 다 0이 되지는 않는다** — 그래서
    ALIGN에서 yaw 게인을 키워 요를 먼저 죽이고, 진입은 그 뒤에 직선으로 한다.
    """
    return _clamp(-(k_lateral * error.lateral_ratio + k_yaw * error.yaw_signal),
                  -max_angular, max_angular)


class ForkServo:
    """정렬 상태기계. 프레임마다 `step()`을 부른다.

    호출자는 `cmd.linear_x`·`cmd.angular_z`를 그대로 Twist에 넣으면 된다. 상태 전이는
    전부 내부에서 일어나고, 끝나면 `phase`가 `DONE` 또는 `ABORT`가 된다.
    """

    def __init__(self,
                 lateral_tolerance: float = LATERAL_TOLERANCE,
                 yaw_tolerance: float = YAW_TOLERANCE,
                 align_enter_px: float = ALIGN_ENTER_PX,
                 insert_enter_px: float = INSERT_ENTER_PX,
                 align_enter_mm: float = ALIGN_ENTER_MM,
                 insert_enter_mm: float = INSERT_ENTER_MM,
                 insert_duration_s: float = INSERT_DURATION_S,
                 lost_grace_s: float = LOST_GRACE_S,
                 k_lateral: float = K_LATERAL,
                 k_yaw: float = K_YAW,
                 align_yaw_boost: float = ALIGN_YAW_BOOST) -> None:
        self.lateral_tolerance = lateral_tolerance
        self.yaw_tolerance = yaw_tolerance
        self.align_enter_px = align_enter_px
        self.insert_enter_px = insert_enter_px
        self.align_enter_mm = align_enter_mm
        self.insert_enter_mm = insert_enter_mm
        self.insert_duration_s = insert_duration_s
        self.lost_grace_s = lost_grace_s
        self.k_lateral = k_lateral
        self.k_yaw = k_yaw
        self.align_yaw_boost = align_yaw_boost

        self.phase = Phase.SEARCH
        self.episode = Episode()
        self._t = 0.0
        self._lost_for = 0.0
        self._insert_elapsed = 0.0
        self._last = DriveCommand()

    def reset(self) -> None:
        """다음 시도를 위해 초기화한다(154의 재시도)."""
        self.phase = Phase.SEARCH
        self.episode = Episode()
        self._t = self._lost_for = self._insert_elapsed = 0.0
        self._last = DriveCommand()

    def is_finished(self) -> bool:
        return self.phase in (Phase.DONE, Phase.ABORT)

    def aligned(self, error: AlignError) -> bool:
        return (abs(error.lateral_ratio) <= self.lateral_tolerance
                and abs(error.yaw_signal) <= self.yaw_tolerance)

    def _reached(self, error: AlignError, px_threshold: float,
                 mm_threshold: float) -> bool:
        """이 단계에 들어갈 만큼 가까워졌나 — **거리 비교는 전부 여기 한 곳에서** 한다.

        `distance_mm`이 있으면(캘리브레이션 완료) 그쪽을 쓴다. 실제 거리라 카메라를
        옮겨도 값이 살아남고 자로 검증할 수 있다.

        ⚠️ **부등호 방향이 반대다.** `approach_px`는 가까울수록 커지고 `distance_mm`은
        가까울수록 작아진다. 두 벌을 각자 비교하면 언젠가 하나를 뒤집어 쓴다.
        """
        if error.distance_mm is not None:
            return error.distance_mm <= mm_threshold
        return error.approach_px >= px_threshold

    def step(self, error: AlignError | None, dt: float) -> DriveCommand:
        """한 프레임 진행한다. `error=None`이면 타깃을 못 본 프레임이다.

        `dt`는 실제 프레임 간격을 넣는다 — **22fps(45ms) 전제**이고 고정 주기가 아니다.
        """
        self._t += dt
        cmd = self._advance(error, dt)
        self._last = cmd
        self.episode.record(self._t, error, cmd)
        return cmd

    def _advance(self, error: AlignError | None, dt: float) -> DriveCommand:
        # INSERT는 개루프다 — 검출이 끊겨도(정상이다) 계속 간다.
        if self.phase is Phase.INSERT:
            self._insert_elapsed += dt
            if self._insert_elapsed >= self.insert_duration_s:
                self.phase = Phase.DONE
                self.episode.outcome = "inserted"
                return DriveCommand(phase=Phase.DONE, reason="개루프 진입 완료")
            return DriveCommand(linear_x=INSERT_SPEED, angular_z=0.0,
                                phase=Phase.INSERT, reason="직선 진입(개루프)")

        if self.is_finished():
            return DriveCommand(phase=self.phase, reason="종료됨")

        if error is None:
            # 놓친 프레임 — 유예 안이면 직전 명령을 유지한다. 매 프레임 정지하면
            # 검출이 깜빡일 때마다 급제동이 걸린다.
            self._lost_for += dt
            if self._lost_for <= self.lost_grace_s and self.phase is not Phase.SEARCH:
                return DriveCommand(self._last.linear_x, self._last.angular_z,
                                    self.phase, "타깃 소실 — 유예 중")
            self.phase = Phase.SEARCH
            return DriveCommand(phase=Phase.SEARCH, reason="타깃 없음 — 정지")

        self._lost_for = 0.0

        # 진입 거리에 왔다 — 여기서 결판이 난다.
        if self._reached(error, self.insert_enter_px, self.insert_enter_mm):
            if self.aligned(error):
                self.phase = Phase.INSERT
                self._insert_elapsed = 0.0
                return DriveCommand(linear_x=INSERT_SPEED, angular_z=0.0,
                                    phase=Phase.INSERT, reason="정렬 완료 — 직선 진입")
            # 뒷바퀴 조향은 제자리 회전이 안 되므로 **여기서는 못 고친다.**
            # 물러나서 다시 접근해야 한다(154의 재시도).
            self.phase = Phase.ABORT
            self.episode.outcome = "misaligned_at_insert"
            return DriveCommand(phase=Phase.ABORT,
                                reason="진입 거리인데 미정렬 — 재접근 필요")

        if self._reached(error, self.align_enter_px, self.align_enter_mm):
            self.phase = Phase.ALIGN
            return DriveCommand(
                linear_x=ALIGN_SPEED,
                angular_z=steering_for(error, self.k_lateral,
                                       self.k_yaw * self.align_yaw_boost),
                phase=Phase.ALIGN, reason="정렬 중(요 우선)")

        self.phase = Phase.APPROACH
        return DriveCommand(linear_x=CRUISE_SPEED,
                            angular_z=steering_for(error, self.k_lateral, self.k_yaw),
                            phase=Phase.APPROACH, reason="접근 중")
