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
"""정렬 구간 전진 속도.

⚠️ **이 값을 낮추면 이제 실제로 느려진다 (2026-08-04 오후, S15P11A304-198).**
`min_drive_percent` 가 50 → 35 로 내려가 매핑이 벌어졌다:

    0.02 → drive 38% ≈ 0.10 m/s
    0.06 → drive 43% ≈ 0.15 m/s   ← 지금 값
    0.12 → drive 50% ≈ 0.178 m/s

**진동(±0.5) 재조사는 이제 가능해졌다.** 다만 값은 **아직 안 바꿨다** — 속도를
낮추는 것이 진폭을 줄이는지는 실물로 확인해야 하고, 확인 없이 내리면 근거 없는
값이 또 하나 박힌다.

⚠️ **35% 아래로는 못 간다.** 그 밑은 구르던 차도 멎고, 멎으면 **스스로 다시
출발하지 못한다**(정지마찰). 0.02 미만을 쓰지 말 것.

⚠️ **되돌림 이력 (2026-08-04 오전).** 0.06 → 0.03 → 0.02 로 낮춰봤으나 **진폭이
±0.5 로 전혀 안 변했다.** 그때는 하한이 50 이라 세 값이 전부 50~52% 로 뭉개져
**실제 속도가 안 변했던 것**이다. 제어 파라미터를 바꿔도 거동이 반응하지 않으면
원인이 제어 루프 밖에 있다는 뜻이다.

아래는 낮출 때의 근거이며 기록으로 남긴다: 설계는 22fps(dt 45ms) 전제인데 젯슨 실측이
5~10fps(dt 100~200ms) 다. 프레임 사이에 차가 너무 많이 가면 다음 프레임에서 본
오차가 이미 낡은 값이라, 같은 게인으로도 **과보정하고 진동한다.** 실제로 lat 부호가
매 프레임 뒤집혔다(+0.20 → -0.30 → -0.08 → +0.28).

게인이 아니라 속도를 줄인 이유: 게인은 "오차 대비 얼마나 꺾을까" 로 기하에서 나온
값이고, 문제는 **프레임당 이동 거리**다. 속도를 줄이면 fps 가 낮아도 프레임당 이동이
줄어 제어가 따라간다. 정렬 구간은 어차피 천천히 가는 것이 맞다.

⚠️ 근본 해결은 fps 를 올리는 것이다(전처리 GPU 이관 등). 그때는 이 값을 되돌린다."""

INSERT_SPEED = 0.05
"""진입 시 `linear.x` 로 내보내는 값. ⚠️ **실제 속도가 아니다** — 아래 참조."""

INSERT_SPEED_ACTUAL = 0.110
"""진입 시 **실제로 나오는 속도**(m/s). 2026-08-04 실주행 로그에서 뽑았다.

⚠️ **재는 방법이 중요하다.** 이 값은 **실주행 로그의 `distance_mm` 시계열**에서
뽑는다 — 재려는 동작 그 자체를 재는 것이라 가장 정확하다:

    INSERT 구간   207 196 185 175 167 157 147 136 124 112 (mm)
    프레임당 약 11mm · 10fps  →  0.110 m/s

지게차를 따로 세워두고 잰 값(0.138)은 **21% 높았다.** INSERT 는 정지에서
출발하는 게 아니라 **ALIGN 속도로 굴러가던 중 전환**되는 것이라 조건이 다르다.
따로 재면 그 차이를 못 잡는다.

값이 실제보다 크면 "이 거리는 이만큼 걸린다" 를 짧게 잡아 **일찍 멈춘다** —
0.138 로 돌렸을 때 142mm 밀어야 할 것을 120mm 만 갔다(실측 22mm 부족).

⚠️ **여전히 명령값(`INSERT_SPEED=0.05`)과 다르다.** `linear.x` 는 PWM 퍼센트로
매핑될 뿐 속도 단위가 아니다. 진입 시간을 명령값으로 계산하면 안 된다.

**0.244 → 0.110 으로 바뀐 이유**: `teleop.yaml` 의 `min_drive_percent` 를 50 → 35
로 내려(S15P11A304-198) 매핑이 달라졌다. `INSERT_SPEED=0.05` 는 이제
`35 + (0.05/0.20)×25 = 41%` 로 간다.

    drive 35%  →  0.070 m/s   (구르는 중에만. 이 값으론 출발 못 한다)
    drive 41%  →  0.110 m/s   ← INSERT 가 여기 (실주행 로그 실측)
    drive 50%  →  0.178 m/s

⚠️ **0.244 는 과대평가였다.** 그때는 1초로 쟀는데, 짧게 잴수록 기동 구간 비중이
커져 평균이 **낮게** 나와야 정상이다. 반대로 나왔으므로 옛 값 자체가 틀렸다.

⚠️ **환경 의존 상수다.** 배터리 전압·바닥 마찰·모터 발열에 따라 달라진다.
시연 장소가 바뀌면 **실주행 로그로 다시 뽑는다**(따로 세워두고 재지 말 것).

⚠️ 진입은 개루프라 이 값이 틀리면 **깊이가 그대로 틀린다.** 값이 실제보다 크면
덜 들어가고(포크가 걸쳐짐), 작으면 파렛트를 밀어낸다."""

# --- 제어 게인 (rad/s per 단위 오차) ---
K_LATERAL = 0.25
"""좌우 오차 → 각속도 게인.

⚠️ **0.45 → 0.25 (2026-08-04 실주행 조정).** 0.45 는 명백히 과했다 — 오차를
0.67 → 0.08 까지 잘 줄이다가 **그대로 지나쳐 -0.52 까지 넘어갔다.** 전형적인
오버슈트다.

    프레임 19   lat +0.08   ← 거의 맞음
    프레임 24   lat -0.52   ← 반대편으로 크게 벗어남

⚠️ 이건 fps 문제가 아니다. ALIGN_SPEED 를 절반으로 낮춰 fps 를 10 으로 회복한
뒤에도 같은 진동이 남았다.

**가까울수록 과해지는 구조**라는 점을 유의할 것 — `lateral_ratio` 는 진입면
반폭으로 정규화돼 있어 거리가 줄면 같은 실제 오차가 큰 비율로 나온다. 고정 게인은
근처에서 세진다. 필요하면 거리에 따라 게인을 줄이는 쪽으로 확장한다."""

K_YAW = 0.60
MAX_ANGULAR = 0.35          # teleop 상한과 동일

ALIGN_YAW_BOOST = 2.0
"""ALIGN에서 요 게인에 곱하는 배수.

두 항을 그냥 더하면 **균형점에 수렴할 뿐 둘 다 0이 되지는 않는다**(`steering_for` 참조).
좌우 오차는 진입 직전까지 계속 볼 수 있지만 요는 코앞에서 못 고치므로(제자리 회전
불가), 마지막 구간에서는 **요를 먼저 죽이는 쪽으로 가중치를 옮긴다.**"""

# --- 수렴 판정 ---
LATERAL_TOLERANCE = 0.25
"""|lateral_ratio| 허용치. 진입면 반폭 대비 비율.

기하학적 한계는 **0.66** 이다 — 여유 ±11.5mm 를 개구 반폭 17.5mm 로 나눈 값.
그보다 훨씬 좁게 잡는 이유는 카메라·포크 장착 오차와 진입 중 슬립이 그 사이를
먹기 때문이다.

⚠️ **0.12 → 0.25 (2026-08-04 실주행).** 0.12 로는 세 번 연속 ABORT 했다. 원인은
정렬 능력이 아니라 **조향 서보가 제어 주기(100ms)를 못 따라가 오차가 계속
출렁이는 것**이다:

    [19] lat -0.06   맞음
    [24] lat +0.57   5프레임 만에 반대편 끝
    [28] lat +0.16   진입 거리 도달 → ABORT (0.04 차이)

0.5초 주기로 맞는 순간이 오는데, 그 순간과 진입 거리가 겹칠 확률이 낮았다.
`INSERT_ENTER_MM` 을 180 → 210 으로 옮겨봐도 타이밍은 운에 맡기는 셈이었다.

0.25 는 여전히 기하학적 한계의 **1/2.6** 이라 물리적 여유가 남는다.

🔴 **이것은 진동을 고친 것이 아니라 우회한 것이다.** 근본 원인(서보 응답 속도)은
그대로다 — S15P11A304-152 에 남긴다. 서보·기구가 개선되면 이 값을 되돌릴 것."""

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
INSERT_ENTER_MM = 210.0
"""(캘리 후) 이 거리 이내면 진입 가능. 여기서 정렬이 안 돼 있으면 ABORT.

⚠️ **180 → 210 (2026-08-04 실주행 조정).** 180 이면 **정렬이 맞은 순간을 놓친다.**
실측 로그:

    203mm   lat +0.02   ← 정렬 맞음. 그런데 아직 진입 거리가 아니다
    189mm   lat +0.13
    179mm   lat +0.21   ← 진입 거리 도달. 이미 벌어져서 ABORT

조향 서보 응답이 제어 주기(100ms)보다 느려 오차가 계속 출렁이므로, **맞는 순간에
바로 진입해야 한다.** 더 가까이 갈수록 다시 벌어질 확률이 올라간다.

검출 여유는 충분하다 — 2026-08-04 실측에서 구멍 2개가 **50mm 까지도 100% 검출**됐다.
210 은 한계에서 한참 먼 값이다.

⚠️ 이 값을 올리면 `ALIGN_ENTER_MM ≥ INSERT_ENTER_MM + 450` 부등식을 다시 봐야 한다.
180 → 210 으로 올리면서 하한이 630 → 660 이 됐고, 그래서 `ALIGN_ENTER_MM` 도
650 → **700** 으로 함께 올렸다. **두 값은 따로 못 움직인다.**"""

INSERT_MARGIN_MM = 40.0
"""진입 목표 거리에서 빼는 여유(mm). 포크 오프셋 90 실측이 기준이지만 **최종값은
실주행으로 잡았다** — 아래 이력 참조.

`distance_mm` 은 **카메라 렌즈 → 파렛트 진입면** 거리인데, **포크 끝은 카메라보다
90mm 앞에 있다**(2026-08-04 실측). 그만큼 빼지 않으면 포크가 이미 구멍을 통과한
뒤에도 계속 밀고 들어간다.

    카메라 ──── 90mm ────[포크끝]── 남은거리 ──파렛트
           └────── distance_mm ──────────────┘

조정 이력 (래치 200mm 기준 전진 거리) — **위에서부터 줄여 내려왔다**:

    15   → 185mm   파렛트를 크게 밀고 나감
    105  → 95mm    많이 줄었으나 아직 조금 밀림
    130  → 70mm    안 밀림. 그런데 5cm 덜 들어감
    80   → 120mm   거의 맞음. 좌 2cm / 우 1cm 부족
    65   → 135mm   깊이 맞음 — 다만 좌우 포크가 4~5cm 덜 들어감
    40   → 160mm   ← 지금. 좌우 둘 다 제대로 물림. 파렛트가 **살짝 밀린다**

⚠️ 위 다섯 줄은 `INSERT_SPEED_ACTUAL` 이 **틀렸던 시절**의 값이다(0.244·0.138).
속도 상수가 크면 실제보다 짧게 가므로, 그때의 "여유" 는 속도 오차와 섞여 있다.
2026-08-04 오후에 속도를 실주행 로그로 바로잡은 뒤 다시 잡은 값이 **40** 이다.

**살짝 미는 것은 허용한다.** 실제 지게차도 포크가 자리를 잡으면서 파렛트를 민다 —
테이퍼가 좌우·수평 잔여 오차를 흡수하는 과정이 곧 그것이다. 덜 물리는 쪽이
화물을 포크 끝에 걸쳐 무게중심을 앞으로 빼는 것보다 나쁘다.

⚠️ **단, 빈 파렛트로 잡은 값이다.** 화물이 실리면 질량·마찰이 달라져 같은 힘에
파렛트가 안 밀리고 **화물이 흔들릴 수 있다.** 적재 상태로 재확인해야 확정이다.

⚠️ 거리 자체도 ±수 mm 흔들린다 (실측 σ 1.3mm @30cm).

⚠️ 카메라를 옮기거나 포크를 교체하면 **다시 재야 한다.**"""

ALIGN_ENTER_MM = 700.0
"""(캘리 후) 파렛트까지 이 거리 이내면 정렬 단계로.

⚠️ 값은 임의로 고르면 안 되고 **아래 산수를 만족해야 한다.** 최소 회전반경 53.7cm 기준
좌우 오프셋 10cm 를 지우는 데 **전진 45cm** 가 필요하다. 그 거리는 ALIGN 구간에서만
벌 수 있으므로(INSERT 는 직선 개루프),

    ALIGN_ENTER_MM ≥ INSERT_ENTER_MM + 450 = 210 + 450 = 660

종전 값 350 은 이 조건을 깨고 있었다 — ALIGN 구간이 350-180 = **170mm 뿐**이라
10cm 틀어져 있으면 기하학적으로 못 고치고 **반드시 ABORT** 했다. 근거를 적어두고
값은 그 근거를 어긴 채로 두면 자리표시자가 아니라 버그다(2026-08-03).

**2026-08-04 실주행으로 확인**: 구멍 2개가 안정적으로 보이는 한계가 약 750~800mm
이므로 700 은 그 안쪽이다. 실제로 574mm 부터 정상 추종했다.

⚠️ 650 → 700 은 `INSERT_ENTER_MM` 을 180 → 210 으로 올리면서 부등식(≥660)이
10mm 모자라게 됐기 때문이다. **두 값은 함께 움직인다.**"""

# 캘리브레이션 전 폭(px) 임계 — **위 mm 값에서 유도한다.**
#
#   span_px = focal_px × HOLE_SPACING_MM / distance_mm     (fork_align.AlignError 참조)
#           = 1277.65 × 48.5 / distance_mm
#
# ⚠️ 두 벌을 각자 손으로 고르면 **같은 이름인데 다른 거리**가 된다. 실제로 그랬다 —
# 종전 260/420px 는 각각 238mm/148mm 라, `--focal-px` 를 주느냐 마느냐로 동작이 달라졌다.
# 카메라·해상도가 바뀌면 px 쪽은 무의미해지므로 **캘리 후에는 mm 를 쓴다.**
_SPAN_PX_AT_1MM = 1277.65 * 48.5

ALIGN_ENTER_PX = _SPAN_PX_AT_1MM / ALIGN_ENTER_MM     # ≈ 89px
"""(캘리 전) 진입면 폭이 이보다 크면 정렬 단계로. `ALIGN_ENTER_MM` 에서 유도."""

INSERT_ENTER_PX = _SPAN_PX_AT_1MM / INSERT_ENTER_MM   # ≈ 295px
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
                 align_yaw_boost: float = ALIGN_YAW_BOOST,
                 align_speed: float = ALIGN_SPEED,
                 insert_margin_mm: float = INSERT_MARGIN_MM) -> None:
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
        # 진동 조사 때 값을 바꿔가며 돌려야 해서 생성자로 뺐다(S15P11A304-152).
        # ⚠️ 0.02 미만은 drive 35% 아래라 구르던 차도 멎고, 멎으면 정지마찰 때문에
        #    **스스로 못 출발한다**. 아래 ALIGN_SPEED 주석 참조.
        self.align_speed = align_speed
        # 진입 깊이를 실주행으로 좁혀 들어가야 해서 같이 뺐다. 줄일수록 깊이
        # 들어간다 — ⚠️ **한 번에 많이 줄이지 말 것.** 너무 줄이면 파렛트를 민다.
        self.insert_margin_mm = insert_margin_mm

        self.phase = Phase.SEARCH
        self.episode = Episode()
        self._t = 0.0
        self._lost_for = 0.0
        self._insert_elapsed = 0.0
        self._insert_target_s = insert_duration_s
        self._last = DriveCommand()

    def reset(self) -> None:
        """다음 시도를 위해 초기화한다(154의 재시도)."""
        self.phase = Phase.SEARCH
        self.episode = Episode()
        self._t = self._lost_for = self._insert_elapsed = 0.0
        self._insert_target_s = self.insert_duration_s
        self._last = DriveCommand()

    def is_finished(self) -> bool:
        return self.phase in (Phase.DONE, Phase.ABORT)

    def aligned(self, error: AlignError) -> bool:
        return (abs(error.lateral_ratio) <= self.lateral_tolerance
                and abs(error.yaw_signal) <= self.yaw_tolerance)

    def _insert_seconds_for(self, error: AlignError) -> float:
        """진입에 쓸 시간(초). **래치 시점의 실측 거리 ÷ 진입 속도.**

        거리를 모르면(캘리브레이션 전) `insert_duration_s` 자리표시자로 떨어진다.

        ⚠️ 왜 고정 시간이면 안 되나 — 래치 거리는 매번 다르다. `INSERT_ENTER_MM`
        은 "이 안쪽이면 진입해도 된다" 는 상한이지 정확한 래치 거리가 아니라,
        정렬이 늦게 맞으면 훨씬 가까이서 래치된다. 2026-08-04 실주행에서 103mm
        에서 래치됐는데 고정 2.5초(=12.5cm)를 그대로 가서 **파렛트를 밀고
        나갔다.** 거리로 계산하면 그 오차가 사라진다.

        여유(`INSERT_MARGIN_MM`)를 빼는 이유는 **모자란 쪽이 안전**하기 때문이다.
        덜 들어가면 포크가 구멍에 걸쳐 있어 다시 밀면 되지만, 더 들어가면 파렛트를
        밀어내 위치가 틀어지고 화물이 흔들린다.
        """
        if error.distance_mm is None:
            return self.insert_duration_s
        travel_mm = max(0.0, error.distance_mm - self.insert_margin_mm)
        return travel_mm / 1000.0 / INSERT_SPEED_ACTUAL

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
            if self._insert_elapsed >= self._insert_target_s:
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
                # **래치 시점의 실측 거리로 진입 시간을 정한다.**
                # 고정 시간(INSERT_DURATION_S)만 쓰면 어느 거리에서 래치되든 같은
                # 거리를 밀고 들어가, 가까이서 래치되면 파렛트를 밀어낸다
                # (2026-08-04 실주행: 103mm 에서 래치 → 2.5초 = 12.5cm 전진 → 관통).
                self._insert_target_s = self._insert_seconds_for(error)
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
                linear_x=self.align_speed,
                angular_z=steering_for(error, self.k_lateral,
                                       self.k_yaw * self.align_yaw_boost),
                phase=Phase.ALIGN, reason="정렬 중(요 우선)")

        self.phase = Phase.APPROACH
        return DriveCommand(linear_x=CRUISE_SPEED,
                            angular_z=steering_for(error, self.k_lateral, self.k_yaw),
                            phase=Phase.APPROACH, reason="접근 중")
