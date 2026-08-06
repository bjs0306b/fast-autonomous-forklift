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

import math
from dataclasses import dataclass, field
from enum import Enum
from statistics import median

from perception.fork_align import HOLE_SPACING_MM, AlignError

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

STEER_DRAG_BOOST = 1.0
"""조향을 꺾을수록 전진 속도를 얼마나 올릴지 (0 = 안 올림, 1 = 최대 조향에서
`CRUISE_SPEED` 까지).

⚠️ **꺾인 채로는 ALIGN 속도로 못 움직인다** (2026-08-05 실측). 뒷바퀴가 36° 누우면
바닥을 옆으로 긁는 꼴이라 저항이 크게 오르는데, ALIGN 의 0.06(구동 43%)은 곧게 갈
때 기준으로 잡힌 값이라 그 저항을 못 이긴다. 실제로 45° 파렛트 재접근 뒤 전진에서
**3초간 한 발도 못 나가** 멎음 감지에 걸렸다(같은 자리에서 곧게 43% 는 잘 나갔다).

    실제 속도 = align_speed + boost × (|조향|/최대조향) × (CRUISE_SPEED - align_speed)

조향 0 이면 종전 그대로고, 최대로 꺾으면 `CRUISE_SPEED`(0.12 = 50%)가 된다. 50% 는
실측표에서 **정지 출발이 보장되는** 구간이다.

⚠️ 속도를 올리면 프레임당 이동이 늘어 제어가 거칠어진다. 그래서 **꺾을 때만** 올린다
— 정렬이 수렴해 조향이 작아지면 자동으로 느려진다."""

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

K_LATERAL_RATE = 0.0
"""좌우 오차의 **변화율**에 걸리는 감쇠 게인. **기본 0 = 꺼짐.**

⚠️ **기본값을 0으로 둔 것은 의도다.** 2026-08-04에 넣었지만 실물로 검증하지
못했다. 검증 없이 켜면 근거 없는 값이 하나 더 박힌다. `--k-lateral-rate` 로
켜서 시험한 뒤, 효과가 확인되면 그때 기본값을 올린다.

**왜 필요한가** — 08-04 실주행에서 `lat` 이 **주기 1.8초의 깨끗한 사인파**로
흔들렸다(±0.3~0.4). 신호가 매끈하니 노이즈가 아니라 **폐루프 한계진동**이다.
속도를 0.02로 낮추고 요각을 평활해도 **안 줄었다.**

구조가 설명한다. 조향각 → 요레이트 → 헤딩 → 좌우위치로 **적분이 두 번** 들어간다.
이중적분기에 비례제어만 걸면 감쇠비가 0이라 반드시 진동하고, **게인을 낮추면
주기만 길어진다.**

⚠️ `steering_for` 의 docstring 은 "첫 항이 반대로 작용해 저절로 감쇠한다" 고
적고 있는데 **그것은 감쇠가 아니다.** 오차를 되돌리는 **복원력**(스프링)이지,
속도에 반대로 작용하는 **감쇠**(댐퍼)가 아니다. 스프링만 있으면 진동한다.

진짜 감쇠는 **오차의 변화율**에 비례하는 항이다 — 그것이 이 게인이다.

⚠️ **미분은 노이즈를 증폭한다.** 10fps 에서 dt 0.1초면 잡음이 10배가 된다.
`lateral_ratio` 는 비교적 깨끗하지만(요각과 달리 양자화 계단이 안 보인다),
켜고 나서 조향 명령이 떨면 이쪽을 의심할 것.

**먼저 할 실험**: `--k-lateral 0.10` 으로 돌린다. 진폭이 확 줄면 게인 문제이고,
**주기만 길어지고 진폭이 그대로면 감쇠 부재가 확정**된다(이론 예측). 후자일 때
이 게인을 켠다."""

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


# --- 재접근(S15P11A304-154) ---

RETREAT_SPEED = -0.12
"""후진 속도(m/s). **음수여야 뒤로 간다.**

⚠️ **2026-08-05 저녁에 -0.20 으로 올렸다가 되돌렸다.** 올린 근거("후진이 전진의
19% 밖에 안 나온다")가 **틀린 측정**이었다 — 조향 한계를 탐색하던 for 문이
`steering_center_cdeg` 를 11800 으로 남겨둔 채 끝나서, 그 뒤 모든 주행이 **바퀴가
한쪽으로 꺾인 상태**에서 측정됐다. 중립을 9000 으로 되돌리자 전후 모두 정상으로
돌아왔다.

**교훈**: 값을 바꾸는 탐색 루프는 **끝나면 원래 값으로 되돌려야 한다.** 중간에서
멈추면 그 값이 남고, 이후 측정이 전부 조용히 오염된다. 오늘 이걸로 네 개의 측정을
버렸다(후진 60%/100% 거리 · 전진 43% 미동 · 전후 비대칭 19%).

크기 근거: **정지 출발**이라 구동 50% 가 필요하다. 이 크기는 구동
PWM 50%로 매핑되고, 실측표에서 정지→출발이 보장되는 구간은 거기부터다(35%는 구르는
중에만, 40%는 갔다 안 갔다). ALIGN 속도(0.06=43%)로 물러나려 하면 **안 움직이는데
로그는 정상으로 찍힌다.**

⚠️ **뒤는 안 보인다.** 카메라는 앞만 본다 — 거리 판정도 파렛트가 멀어지는 것으로만
하므로, 뒤에 뭐가 있는지는 아무도 모른다. `RETREAT_MAX_S`가 유일한 방어다."""

RETREAT_TARGET_MM = 600.0
"""**거리를 모를 때만** 쓰는 후진 목표(mm). 캘리브레이션 전(px 모드) 자리표시자다.

거리를 알면 후진량은 고정값이 아니라 **틀어진 만큼 계산한다** — `retreat_step_for`
참조. 조금 틀어졌으면 조금만, 많이 틀어졌으면 많이 물러난다.

⚠️ 이 값은 2026-08-05 이전에 **모든 재접근의 고정 목표**였다. 700 → 600 으로
내렸다가, 결국 **고정값 자체가 틀린 접근**이라 계산식으로 바꿨다. 0.1 틀어졌든 1.5
틀어졌든 39cm 를 물러나는 것은 낭비고, 반대로 크게 틀어졌을 땐 39cm 로도 모자란다."""

STEER_RADIUS_MM = 537.0
"""최소 회전반경(mm). 뒷바퀴 조향 실측값 — `onboard-fork-align-design.md`.

요각 ψ 를 지우는 데 필요한 **전진 거리**가 `R·sin ψ` 다. 30° 면 268mm, 10° 면 93mm."""

RUNWAY_PER_LATERAL = 4.5
"""좌우 오차 1mm 를 지우는 데 필요한 전진 거리(mm/mm).

설계 문서의 실측 한 쌍에서 왔다 — **좌우 10cm 를 지우는 데 전진 45cm**. 좌우는
"돌았다가 되돌아오는" 두 호가 필요해서 요각보다 비싸다."""

LATERAL_RATIO_TO_MM = HOLE_SPACING_MM / 2.0
"""`lateral_ratio` 1.0 이 몇 mm 인가.

`lateral_ratio` 는 진입면 반폭으로 정규화된 값이고, 그 반폭이 화면에서 차지하는 것이
구멍 간격(`HOLE_SPACING_MM`)의 절반이다. **거리와 무관하게** 이 환산이 성립한다 —
화면 폭도 거리에 반비례해 같이 줄기 때문이다."""

RETREAT_MIN_STEP_MM = 80.0
"""한 번에 최소 이만큼은 물러난다.

이보다 짧으면 관성(명령 뒤 0.3초는 오히려 전진, 약 44mm)에 먹혀 **뒤로 간 게 없다.**"""

RETREAT_MAX_STEP_MM = 120.0
"""한 번에 최대 이만큼만 물러난다.

크게 틀어졌다고 한 번에 다 물러나지 않는다 — **여러 번 조금씩** 물러나며 매번 다시
판단하는 쪽이 정확하다. 뒤가 안 보이므로 한 번에 멀리 가는 것 자체가 위험하기도 하다.

⚠️ 250 → 120 (2026-08-05 저녁). 두 가지가 같이 바뀌었다:

- **조향이 4.5배가 됐다**(8° → 36°). 후진 **1초에 요각 12.5°** 를 편다(실측). 자세를
  펴는 데 멀리 물러날 이유가 없어졌다.
- **이 바닥에서 후진이 약하다.** 60% 로 3초에 100mm(0.033 m/s) — 전진(0.178)의 19%
  다. 250mm 를 채우려면 8초가 걸려 시간 상한에 걸린다.

⚠️ **바닥이 바뀌면 다시 재야 한다.** 오전 바닥에서는 후진이 0.138 m/s 였다."""

RETREAT_SPEED_ACTUAL = 0.138
"""후진 **실측** 속도(m/s). 2026-08-05 실주행 로그의 `distance_mm` 시계열에서 뽑았다.

명령값 0.12 와 다르고 **전진(0.178)보다 느리다.** 후진 시간 예산을 이 값으로 잡는다 —
전진 속도로 계산했다가 26mm 를 남기고 상한에 끊긴 적이 있다."""

RETREAT_MAX_S = 5.0
"""후진 시간 상한(초). **목표가 아니라 안전 상한이다.**

⚠️ **처음에 4.0 으로 뒀다가 실주행에서 26mm 차이로 끊겼다**(2026-08-05). 전진 속도
0.178 로 계산한 것이 틀렸다 — 실측 **후진 속도는 0.138 m/s** 로 더 느리고, 명령 직후
**0.3~0.4초는 관성으로 오히려 앞으로 간다**(198mm → 154mm, 두 번 재현). 지금 목표
390mm 는 관성 44mm 를 더해 434mm/0.138 ≈ **3.1초**, 여기에 여유를 준 값이 5.0 이다.

정상 종료는 거리 판정으로 끝난다. 여기에 걸렸다면 거리 판정이 죽은 것이므로 실패로
본다(`retreat_timeout`).

⚠️ 08-04에 고정 횟수 루프로 후진시켰다가 **벽에 박았고** 검출 범위 밖까지 물러났다.
그래서 거리를 매 프레임 보고(닫힌 루프), 시간 상한은 그 판정이 죽었을 때만 쓴다."""

YAW_DEG_TOLERANCE = 10.0
"""진입을 허가할 **요각 상한(도)**. `yaw_deg` 를 아는 경우에만 쓴다.

⚠️ **이게 없어서 2026-08-05에 요각 -58.5° 인 채로 진입이 허가됐다.** 포크는 구멍에
안 들어가고 **비스듬히 스쳤는데** 로그는 `done (inserted)` 였다.

원인은 두 지표가 서로 다른 말을 하기 때문이다. `yaw_signal` 은 두 구멍의 **화면 폭 비**
라 큰 각도에서 포화된다 — 실제 -58.5° 인데 신호는 -0.07 이라 허용치(0.08) 안이었다.
각도를 아는 순간부터는 **각도로 판정해야 한다.**

10° 근거: 두 구멍 간격이 48.5mm 이므로 요각 ψ 면 좌우 포크의 진입 깊이가
`48.5·sin ψ` 만큼 어긋난다 — 10° 에서 8.4mm, 30° 에서 24mm. 테이퍼가 흡수할 수 있는
범위를 넘지 않게 잡은 값이다.

⚠️ **아직 실측으로 좁힌 값이 아니다.** 몇 도까지 물리는지는 각도를 바꿔가며 재야
한다. 지금은 "58° 를 통과시키던 것" 보다 나은 자리표시자다."""

RETREAT_FULL_YAW_DEG = 20.0
"""후진 조향이 **최대 권한까지 올라가는** 요각(도).

이보다 크게 틀어져 있으면 조향을 아끼지 않는다. 후진은 뒷바퀴 조향에서 **안정적인
방향**이므로(조향축이 앞장선다) 꺾어도 튀지 않는다."""

RETREAT_RECOVER_S = 2.0
"""타깃을 놓친 뒤 **마지막으로 본 방향으로 꺾어 되찾아보는** 시간(초).

파렛트는 화면 옆으로 밀려나 사라지므로, 어느 쪽으로 사라졌는지 알면 그쪽으로 돌려
다시 넣을 수 있다. 후진 중이라 물러날수록 시야도 같이 넓어진다.

⚠️ **눈 감고 하는 동작이다.** 2초면 후진으로 약 20cm — 그 안에 못 찾으면 포기한다.
0 이면 종전처럼 즉시 중단한다."""

RETREAT_KEEP_IN_VIEW = 1.2
"""후진 중 좌우 오차가 이보다 커지면 **요각 대신 좌우를 잡는다.**

⚠️ 요각만 보게 했더니 좌우가 발산했다(2026-08-05 실측). 45° 파렛트에서 재접근 두 번에
`lat` 이 -0.01 → +2.04 로 밀렸고 결국 파렛트를 화면 밖으로 놓쳤다
(`lost_while_retreating`). 요각을 펴려고 도는 만큼 옆으로 밀리는데 그걸 아무도 안
막았기 때문이다.

**타깃을 놓치면 재접근 자체가 끝난다.** 자세를 조금 덜 펴더라도 파렛트를 화면 안에
두는 쪽이 낫다 — 남은 자세는 다음 걸음에서 마저 편다.

1.2 인 이유: `lateral_ratio` 1.0 이 진입면 반폭이라 그 언저리가 화면 가장자리다.
2.0 을 넘기면 검출이 끊기기 시작한다(실측 -2.5 부근에서 소실)."""

RETREAT_MAX_ANGULAR = 0.60
"""후진 전용 각속도 상한(rad/s). **전진(`MAX_ANGULAR` 0.35)보다 크다.**

⚠️ 0.44 → 0.60 (2026-08-05 저녁). 조향 한계가 28° → 36° 로 넓어졌기 때문이다
(펌웨어 펄스 범위 수정 + 가동폭 재설정). 0.44 는 28° 시절 값이라 지금은 27.8° 까지만
낸다 — 36° 를 다 쓰려면 `0.12 × tan(36°) / 0.144 = 0.605` 가 필요하다.

이유는 `map_twist` 가 조향각을 **곡률**로 내기 때문이다:

    곡률 = angular_z / linear_x,   뒷바퀴각 = atan(축간거리 × 곡률)

후진 속도 0.12 에서 `MAX_ANGULAR` 0.35 를 주면 곡률 2.92 → 뒷바퀴각 **22.8°** 다.
기계 한계는 28° 이므로 **끝까지 안 꺾고 있었다.** 28° 를 다 쓰려면
`0.12 × tan(28°) / 0.144 = 0.443` 이 필요하다.

⚠️ **`teleop.yaml` 의 `max_angular_rps` 도 같이 올려야 한다.** 브리지가 거기서 한 번
더 자르므로, 0.35 로 두면 여기서 아무리 키워도 그대로 잘린다.

⚠️ 전진 상한은 **안 올린다.** ALIGN 은 0.06 이라 0.35 로도 이미 기계 한계에
닿아 있고(곡률 5.8), 한계진동 조사 중이라 조향 권한을 건드리면 변수가 늘어난다."""

RETREAT_STEER_GAIN = -1.0
"""후진 중 조향 세기. **최대 조향에 대한 비율이고, 부호가 방향이다.**

⚠️ **음수인 것이 실측 결과다**(2026-08-05). 전권으로 양쪽을 다 돌려봤다:

    +1.0 : lat +0.33 → +4.8 (화면 밖) · 요각 -44° → -56° · 타깃 놓칠 뻔
    -1.0 : lat +0.49 → -0.85 (0을 지나 수렴) · 요각 -35.6° → -40° · 4회 정상 순환

`map_twist` 가 `곡률 = angular_z / linear_x` 로 조향각을 내므로 `linear_x` 가 음수면
부호가 뒤집히는데, 링키지 극성은 `mapping.py` 주석대로 **전진으로만 맞춘 값**이라
그 뒤집힘이 상쇄되지 않는다. 계산으로 예상은 됐지만 **확정은 실물로 했다.**

⚠️ **요각이 실제로 줄어드는 것까지는 아직 못 봤다.** 좌우 항이 요 항을 상쇄해
조향이 0으로 죽고 있었고(`retreat_steering_for` 참조), 그걸 고친 뒤로는 아직 안
재봤다. 0 을 주면 종전처럼 곧게 물러난다.

⚠️ **1.0 이 "최대 조향까지 쓴다" 는 뜻이다.** 종전에는 이 값이 전진 게인에 곱해지는
축소 계수라 0.15 를 주면 실제 명령이 `w +0.02`(상한의 6%)밖에 안 나왔다 — 3초를
물러나도 자세가 5° 밖에 안 바뀌어 **사실상 곧게 물러난 것과 같았다.**

⚠️ **음수를 주면 방향이 뒤집힌다.** 부호 검증용이다 — 아래 참조.

곧게 물러나면 거리만 벌고 자세는 그대로다. 조향을 걸면 **물러나면서 자세까지 고칠 수
있다** — 사람이 주차할 때 하는 그것이고, 뒷바퀴 조향은 후진 쪽이 오히려 안정적이다
(조향축이 앞장서므로 승용차 전진과 같은 형상이 된다. 전진은 반대로 오버스티어 쪽).

⚠️ **기본을 0으로 둔 이유는 부호를 실물로 확인 안 했기 때문이다.** `map_twist` 는
`curvature = angular_z / linear_x` 로 조향각을 내는데, `linear_x` 가 음수면 그 부호가
뒤집힌다. 링키지 극성은 **전진으로만 맞춰본 값**이라(mapping.py "installed linkage"),
후진에서 같은 방향으로 도는지는 재본 적이 없다. 틀린 부호로 걸면 재접근이 자세를
**더 틀어놓는다.**

**가르는 실험**: `--retreat-steer 0.15` 로 한 번 돌려 후진 구간의 `yaw_deg` 를 본다.
절댓값이 줄면 부호가 맞은 것이고, 커지면 뒤집어야 한다."""

# --- 멎음 감지 ---

STALL_WINDOW_S = 3.0
"""이 시간 동안 거리가 안 변하면 멎은 것으로 본다.

**왜 필요한가 — 2026-08-05 하루에 세 번 조용히 실패했다.** 브리지가 죽고(10:13),
ESP32 링크가 흔들리고(10:57), 그때마다 정렬 노드는 `align` 을 **60~90초 내내 정상처럼
찍었다.** 거리가 1mm 도 안 줄었는데도. 사람이 로그를 눈으로 훑어야 알 수 있었다.

`teleop.yaml` 이 이 구멍을 예고해뒀다: *"ALIGN 도중 무언가에 걸려 멎으면 스스로 못
출발한다. **카메라 거리가 안 줄어드는 것으로 감지해야 한다 — 아직 없다.**"*

3.0초인 이유: ALIGN 실속도 0.15 m/s 로 45cm 를 가는 시간이라 정상 주행이면 절대
안 걸린다. 짧게 잡으면 검출이 잠깐 튈 때 오탐이 난다."""

STALL_PROGRESS_MM = 20.0
"""한 창 동안 최소 이만큼은 나아가야 한다.

정상이면 3초에 450mm 를 간다 — 20mm 는 그 **1/20** 이라 여유가 크다. 이렇게 헐겁게
잡은 이유는 **오탐이 오검출보다 비싸기** 때문이다. 진짜로 멎은 경우는 진행이 0 이라
어떤 값을 골라도 걸린다.

⚠️ 창의 **중앙값**끼리 비교한다. 평균이 아니다 — 요각이 크면 `distance_mm` 이
251~559mm 로 튀는데(2026-08-05 실측), 평균은 그 이상치에 끌려가고 순간값 비교는
"튄 프레임 하나 = 진행" 으로 읽힌다."""

VERIFY_FRAMES = 3
"""진입 직전 자세를 **몇 프레임 모아서** 판단할지.

⚠️ **한 프레임으로 결정하면 안 된다.** 2026-08-05 실주행에서 튄 프레임 하나가
`distance_mm` 을 149mm(실제 약 350mm)로 찍었고, 그 한 줄이 진입을 확정시켜 지게차가
**110mm 만 가고 멈춘 채 `done` 을 찍었다.** 검출은 가끔 부호까지 뒤집힌다(생요각
+64.8° ↔ -45°).

3 프레임의 **중앙값**으로 판단한다. 0.3초, 굴러가는 거리로 약 45mm — 진입 여유
안에서 감당된다. 더 늘리면 정확해지지만 그만큼 파렛트에 다가붙는다.

멈춰서 재지 않는 이유: 정지하면 **정지마찰 때문에 다시 못 출발할 수 있다**(진입 속도
0.05 는 구동 41% 대). 굴러가는 채로 조향만 끊고 잰다."""

MAX_RETRIES = 20
"""재접근 시도 횟수 상한. 넘으면 ABORT.

2 → 4 → **20**(2026-08-05). 한 걸음이 250mm 로 작아졌으므로 걸음 수가 많아야 한다.
20 은 "사실상 제한 없음" 이고, **실질적인 제동은 `--max-seconds`** 다 — 시간이
곧 예산이라 그쪽 하나로 관리하는 편이 사람이 이해하기 쉽다.

⚠️ 그래도 **무한은 아니다.** 개선 없이 같은 자리를 오가는 경우가 있다 — 45° 돌아간
파렛트가 그렇다. `R·sin 45° = 380mm` 라 한 걸음 상한 250mm 로는 매번 모자라고,
재접근할 때마다 같은 자세로 돌아온다. 그런 조건은 **재접근이 아니라 배치로 풀 문제**
이므로 언젠가는 사람에게 넘겨야 한다."""


class Phase(Enum):
    SEARCH = "search"
    APPROACH = "approach"
    ALIGN = "align"
    VERIFY = "verify"
    INSERT = "insert"
    RETREAT = "retreat"
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
                 k_yaw: float = K_YAW, max_angular: float = MAX_ANGULAR,
                 lateral_rate: float = 0.0,
                 k_lateral_rate: float = K_LATERAL_RATE) -> float:
    """정렬 오차 → 각속도(rad/s). 부호 유도가 핵심이라 근거를 적어둔다.

    카메라를 원점, 전방을 +Y, 오른쪽을 +X로 둔다. 파렛트 면이 반시계로 θ만큼 돌면
    오른쪽 구멍이 더 멀어지고(깊이 D + S/2·sinθ) 화면에서 좁게 보인다 →
    `yaw_signal > 0`. 그때 면의 바깥 법선은 (+sinθ, −cosθ), 즉 **우리 기준 오른쪽**을
    향한다. 그 법선 위로 올라가려면 **오른쪽으로 돌아 들어가야** 한다.

    좌우 오차도 같다 — 파렛트가 화면 오른쪽에 있으면(`lateral_ratio > 0`) 오른쪽으로
    가야 한다. **두 항의 부호가 같고**, ROS 관례에서 우회전은 음의 각속도다.

    두 항을 더하는 것은 "파렛트 앞 standoff 점을 향해 달린다"의 1차 근사다.
    ⚠️ 대신 **두 항이 균형을 이루는 지점에 수렴할 뿐 둘 다 0이 되지는 않는다** — 그래서
    ALIGN에서 yaw 게인을 키워 요를 먼저 죽이고, 진입은 그 뒤에 직선으로 한다.

    ⚠️ **여기에 적혀 있던 "첫 항이 반대로 작용해 저절로 감쇠한다" 는 틀렸다**
    (2026-08-04 정정). 그것은 오차를 되돌리는 **복원력**(스프링)이지 **감쇠**(댐퍼)가
    아니다. 조향각 → 요레이트 → 헤딩 → 좌우위치로 적분이 두 번 들어가는 계에
    비례항만 걸면 감쇠비가 0이라 **반드시 진동한다.** 실제로 실주행 `lat` 이 주기
    1.8초 사인파로 흔들렸다.

    `lateral_rate`(좌우 오차의 시간 변화율)와 `k_lateral_rate` 가 그 감쇠항이다.
    **기본은 꺼져 있다** — `K_LATERAL_RATE` 주석 참조.
    """
    return _clamp(-(k_lateral * error.lateral_ratio
                    + k_yaw * error.yaw_signal
                    + k_lateral_rate * lateral_rate),
                  -max_angular, max_angular)


def retreat_steering_for(error: AlignError, gain: float = RETREAT_STEER_GAIN,
                         max_angular: float = RETREAT_MAX_ANGULAR,
                         full_yaw_deg: float = RETREAT_FULL_YAW_DEG,
                         keep_in_view: float = RETREAT_KEEP_IN_VIEW) -> float:
    """후진 중 조향(rad/s). **꺾으면서 물러난다** — 곧게 물러나면 자세가 안 바뀐다.

    ⚠️ **요각만 본다. 좌우는 안 본다.** 좌우까지 넣었다가 2026-08-05 실주행에서
    **두 항이 서로 상쇄돼 조향이 0으로 죽었다**:

        [  35] w -0.35  lat +0.03  요 -41.6°
        [  41] w -0.00  lat -0.61  요 -40.3°   ← 요각이 40° 남았는데 안 꺾는다

    좌우 오차는 **다음 접근 구간이 지운다** — 그러라고 활주로를 버는 것이다. 후진이
    할 일은 자세를 펴는 것 하나다.

    ⚠️ **전진 게인(`K_YAW`)을 재활용하지 않는다.** `yaw_signal` 은 큰 각도에서
    포화돼(-58° 가 -0.09) 곱해봐야 명령이 안 나온다. 그렇게 짰다가 `w +0.02`(상한의
    6%)가 나가 3초 후진에 자세가 5° 밖에 안 바뀌었다. 여기서는 **각도를 직접 쓴다** —
    `RETREAT_FULL_YAW_DEG`(20°)에서 이미 최대 조향이고 그 이상은 포화시킨다.

    `yaw_deg` 가 없으면(캘리브레이션 전) `yaw_signal` 로 대체한다.

    `gain` 은 **최대 조향에 대한 비율**이다 — 1.0 이 전권, 음수면 방향이 뒤집힌다.
    """
    if gain == 0.0:
        return 0.0

    # ⚠️ **화면 밖으로 나가려 하면 좌우가 먼저다.** 타깃을 놓치면 재접근 자체가
    # 끝나므로, 자세를 덜 펴더라도 파렛트를 화면 안에 둔다(`RETREAT_KEEP_IN_VIEW`).
    # 좌우 항은 요 항과 **부호가 반대**다 — 후진에서는 같은 쪽으로 꺾어도 옆으로
    # 밀리는 방향이 뒤집히기 때문이다.
    if abs(error.lateral_ratio) > keep_in_view:
        pull_back = _clamp(error.lateral_ratio / keep_in_view, -1.0, 1.0)
        return _clamp(gain * max_angular * pull_back, -max_angular, max_angular)

    if error.yaw_deg is not None:
        yaw_term = _clamp(error.yaw_deg / full_yaw_deg, -1.0, 1.0)
    else:
        yaw_term = _clamp(error.yaw_signal / YAW_TOLERANCE, -1.0, 1.0)
    return _clamp(gain * max_angular * -yaw_term, -max_angular, max_angular)


def retreat_step_for(lateral_ratio: float, yaw_deg: float | None,
                     min_step_mm: float = RETREAT_MIN_STEP_MM,
                     max_step_mm: float = RETREAT_MAX_STEP_MM) -> float:
    """**얼마나 물러나야 하나**(mm). 틀어진 만큼만 물러난다.

    앞으로 가면서 오차를 지우려면 활주로가 필요하고, 그 길이는 기하로 나온다:

    - **요각 ψ** — 반경 `R` 짜리 호로 돌면 `R·sin ψ` 만큼 전진해야 한다
    - **좌우 오차** — 돌았다가 되돌아오는 두 호가 필요해 더 비싸다. 설계 실측이
      "좌우 10cm 에 전진 45cm" 라 `RUNWAY_PER_LATERAL` 배로 잡는다

    둘 중 **큰 쪽**을 쓴다. 동시에 필요한 것이 아니라 둘 다 만족해야 하는 조건이라,
    더 긴 쪽이 정해지면 짧은 쪽은 그 안에서 처리된다.

    ⚠️ **한 걸음에 다 해결하지 않는다.** `max_step_mm` 으로 자르고, 물러난 뒤 다시
    재서 또 판단한다. 크게 틀어졌으면 걸음이 여러 번 나뉜다 — 뒤가 안 보이는 상태에서
    한 번에 멀리 가는 것보다 낫고, 매번 새 측정으로 판단하므로 정확하다.

    `yaw_deg` 가 없으면(캘리브레이션 전) 좌우 항만 쓴다.
    """
    lateral_mm = abs(lateral_ratio) * LATERAL_RATIO_TO_MM
    needed = RUNWAY_PER_LATERAL * lateral_mm
    if yaw_deg is not None:
        needed = max(needed, STEER_RADIUS_MM * abs(math.sin(math.radians(yaw_deg))))
    return _clamp(needed, min_step_mm, max_step_mm)


class ForkServo:
    """정렬 상태기계. 프레임마다 `step()`을 부른다.

    호출자는 `cmd.linear_x`·`cmd.angular_z`를 그대로 Twist에 넣으면 된다. 상태 전이는
    전부 내부에서 일어나고, 끝나면 `phase`가 `DONE` 또는 `ABORT`가 된다.
    """

    def __init__(self,
                 lateral_tolerance: float = LATERAL_TOLERANCE,
                 yaw_tolerance: float = YAW_TOLERANCE,
                 yaw_deg_tolerance: float = YAW_DEG_TOLERANCE,
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
                 insert_margin_mm: float = INSERT_MARGIN_MM,
                 k_lateral_rate: float = K_LATERAL_RATE,
                 retreat_speed: float = RETREAT_SPEED,
                 retreat_target_mm: float = RETREAT_TARGET_MM,
                 retreat_max_s: float = RETREAT_MAX_S,
                 retreat_steer_gain: float = RETREAT_STEER_GAIN,
                 retreat_recover_s: float = RETREAT_RECOVER_S,
                 max_retries: int = MAX_RETRIES,
                 verify_frames: int = VERIFY_FRAMES,
                 steer_drag_boost: float = STEER_DRAG_BOOST,
                 stall_window_s: float = STALL_WINDOW_S,
                 stall_progress_mm: float = STALL_PROGRESS_MM) -> None:
        self.lateral_tolerance = lateral_tolerance
        self.yaw_tolerance = yaw_tolerance
        self.yaw_deg_tolerance = yaw_deg_tolerance
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
        self.k_lateral_rate = k_lateral_rate
        # 재접근(154). 후진 속도는 **음수**로 들어와야 한다 — 양수를 주면 물러나는
        # 대신 파렛트로 밀고 들어간다. 생성자에서 막는다.
        if retreat_speed >= 0.0:
            raise ValueError("retreat_speed 는 음수여야 한다 (후진)")
        self.retreat_speed = retreat_speed
        self.retreat_target_mm = retreat_target_mm
        self.retreat_max_s = retreat_max_s
        self.retreat_steer_gain = retreat_steer_gain
        self.retreat_recover_s = retreat_recover_s
        self._last_seen_lateral: float | None = None
        self.max_retries = max_retries
        self.verify_frames = verify_frames
        self.steer_drag_boost = steer_drag_boost
        self._verify: list[tuple[float, float, float | None, float | None]] = []
        # 이번 재접근에서 "여기까지 물러난다" 는 값. 걸음마다 다시 계산한다.
        self._retreat_target = retreat_target_mm
        # **진입 거리 밖을 한 번이라도 봤나.** 시작부터 코앞이면 재접근이 아니라
        # 출발 위치가 틀린 것이라, 조용히 물러나지 말고 시끄럽게 끝낸다.
        self._ever_beyond_insert = False
        # 멎음 감지. 0 이하면 끈다 — 대차를 손으로 밀며 시험할 때 쓴다.
        self.stall_window_s = stall_window_s
        self.stall_progress_mm = stall_progress_mm
        self._stall_window_t = 0.0
        self._stall_samples: list[float] = []
        self._stall_prev: float | None = None
        self._stall_forward: bool | None = None

        self.phase = Phase.SEARCH
        # 감쇠항용 — 직전 프레임의 좌우 오차. None 이면 변화율을 못 구한다.
        self._last_lateral: float | None = None
        self.episode = Episode()
        self._t = 0.0
        self._lost_for = 0.0
        self._insert_elapsed = 0.0
        self._insert_target_s = insert_duration_s
        self._retreat_elapsed = 0.0
        self.retries = 0
        # 후진 한 번의 (시작 요각, 시작 거리). 끝날 때 **실효 회전반경**을 낸다.
        self._retreat_start: tuple[float | None, float | None] = (None, None)
        self.retreat_reports: list[str] = []
        self._last = DriveCommand()

    def reset(self) -> None:
        """다음 시도를 위해 초기화한다 — **에피소드째로** 버린다.

        ⚠️ 154 의 재접근은 이걸 쓰지 않는다. 재접근은 한 에피소드 **안에서** 일어나야
        기록이 이어지고 시도 횟수가 집계된다. `reset()` 은 사람이 처음부터 다시
        돌릴 때 쓴다."""
        self.phase = Phase.SEARCH
        self.episode = Episode()
        self._t = self._lost_for = self._insert_elapsed = 0.0
        self._retreat_elapsed = 0.0
        self.retries = 0
        self._insert_target_s = self.insert_duration_s
        self._last = DriveCommand()
        self._last_lateral = None
        self._verify = []
        self._retreat_target = self.retreat_target_mm
        self._retreat_start = (None, None)
        self._last_seen_lateral = None
        self.retreat_reports = []
        self._ever_beyond_insert = False
        self._reset_stall()

    def _reset_stall(self) -> None:
        self._stall_window_t = self._t
        self._stall_samples = []
        self._stall_prev = None
        self._stall_forward = None

    def _stalled(self, error: AlignError | None, cmd: DriveCommand) -> bool:
        """명령을 내보내는데 **거리가 안 변하나.** 그러면 차가 멎은 것이다.

        가장 비싼 실패는 에러를 안 내고 그럴듯하게 도는 것이다 — 이 검사가 그걸
        시끄럽게 만든다. 근거는 `STALL_WINDOW_S` 주석에 있다.

        검사하지 않는 경우:

        - **`distance_mm` 이 없을 때**(캘리브레이션 전) — 잴 자가 없다
        - **INSERT** — 개루프고 코앞에서 검출이 끊기는 게 정상이라 거리가 안 온다
        - **정지 명령 중**(`linear_x == 0`) — 안 움직이는 게 맞다
        - **놓친 프레임** — 표본이 없다. 창 시계는 계속 간다
        """
        if self.stall_window_s <= 0.0 or cmd.linear_x == 0.0:
            return False
        if cmd.phase not in (Phase.APPROACH, Phase.ALIGN, Phase.VERIFY,
                             Phase.RETREAT):
            return False
        if error is None or error.distance_mm is None:
            return False

        # 전진과 후진은 "나아간다"의 부호가 반대다. 방향이 바뀌면 창을 새로 연다 —
        # 안 그러면 후진 첫 창이 직전 전진 창과 비교돼 없는 멎음을 만든다.
        forward = cmd.linear_x > 0.0
        if forward is not self._stall_forward:
            self._reset_stall()
            self._stall_forward = forward

        self._stall_samples.append(error.distance_mm)
        if self._t - self._stall_window_t < self.stall_window_s:
            return False

        now = median(self._stall_samples)
        prev, self._stall_prev = self._stall_prev, now
        self._stall_window_t = self._t
        self._stall_samples = []
        if prev is None:
            return False
        progress = (prev - now) if forward else (now - prev)
        return progress < self.stall_progress_mm

    def _lateral_rate(self, error: AlignError, dt: float) -> float:
        """좌우 오차의 시간 변화율(1/s). 감쇠항의 입력이다.

        직전 값이 없거나 `dt`가 0이면 **0을 돌려준다** — 첫 프레임에 없는 변화율을
        지어내면 출발하자마자 조향이 튄다.
        """
        if self._last_lateral is None or dt <= 0.0:
            return 0.0
        return (error.lateral_ratio - self._last_lateral) / dt

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

    def _speed_for_turn(self, angular_z: float) -> float:
        """조향 크기에 맞춘 전진 속도. 꺾을수록 빠르다.

        곧게 갈 때는 `align_speed` 그대로, 최대로 꺾으면 `CRUISE_SPEED` 다.
        근거는 `STEER_DRAG_BOOST` 주석에 있다.
        """
        if self.steer_drag_boost <= 0.0 or MAX_ANGULAR <= 0.0:
            return self.align_speed
        ratio = _clamp(abs(angular_z) / MAX_ANGULAR, 0.0, 1.0)
        return self.align_speed + (self.steer_drag_boost * ratio
                                   * max(0.0, CRUISE_SPEED - self.align_speed))

    def _verify_step(self, error: AlignError) -> DriveCommand:
        """진입 직전 **자세 확인**. 굴러가면서 몇 프레임을 모아 중앙값으로 판단한다.

        조향을 끊는 이유: 이 거리에서 꺾으면 포크가 구멍 벽을 긁는다. 판단하는 동안은
        직진만 한다.
        """
        self._verify.append((error.lateral_ratio, error.yaw_signal,
                             error.yaw_deg, error.distance_mm))
        if len(self._verify) < self.verify_frames:
            return DriveCommand(self.align_speed, 0.0, Phase.VERIFY,
                                f"진입 직전 자세 확인 {len(self._verify)}/{self.verify_frames}")

        lat = median(v[0] for v in self._verify)
        yaw = median(v[1] for v in self._verify)
        yaws_deg = [v[2] for v in self._verify if v[2] is not None]
        yaw_deg = median(yaws_deg) if yaws_deg else None
        self._verify = []

        # ⚠️ **각도를 알면 각도로 판정한다.** `yaw_signal` 만 보면 큰 각도를 통과시킨다
        # (2026-08-05: 실제 -58.5° 인데 신호는 -0.07 이라 허용치 안이었고, 포크가
        # 구멍에 안 들어가고 비스듬히 스쳤는데 로그는 `done (inserted)` 였다).
        yaw_ok = abs(yaw) <= self.yaw_tolerance
        if yaw_deg is not None:
            yaw_ok = abs(yaw_deg) <= self.yaw_deg_tolerance
        if abs(lat) <= self.lateral_tolerance and yaw_ok:
            self.phase = Phase.INSERT
            self._insert_elapsed = 0.0
            # 진입 시간은 **가장 최근 거리**로 잡는다. 판단은 중앙값으로 하되 남은
            # 거리는 지금 값이라야 맞다 — 확인하는 동안에도 굴러왔기 때문이다.
            self._insert_target_s = self._insert_seconds_for(error)
            return DriveCommand(INSERT_SPEED, 0.0, Phase.INSERT,
                                f"자세 확인됨(lat {lat:+.2f}) — 직선 진입")

        if self.retries >= self.max_retries:
            self.phase = Phase.ABORT
            self.episode.outcome = "misaligned_at_insert"
            return DriveCommand(phase=Phase.ABORT,
                                reason=f"미정렬(lat {lat:+.2f}) — 재시도 {self.retries}회 소진")

        # **틀어진 만큼만** 물러난다.
        step = retreat_step_for(lat, yaw_deg)
        # 거리를 모르면(px 모드) 계산할 근거가 없다 — 고정 목표로 떨어진다.
        self._retreat_target = (error.distance_mm + step
                                if error.distance_mm is not None
                                else self.retreat_target_mm)
        self.retries += 1
        self.phase = Phase.RETREAT
        self._retreat_elapsed = 0.0
        self._last_lateral = None
        self._retreat_start = (yaw_deg, error.distance_mm)
        return DriveCommand(
            self.retreat_speed,
            retreat_steering_for(error, self.retreat_steer_gain),
            Phase.RETREAT,
            f"미정렬(lat {lat:+.2f}"
            + (f" 요 {yaw_deg:+.0f}°" if yaw_deg is not None else "")
            + f") — {step:.0f}mm 물러난다 {self.retries}/{self.max_retries}")

    def _report_retreat(self, error: AlignError) -> None:
        """후진 한 번이 **실제로 무엇을 했는지** 한 줄로 남긴다.

        모델은 반경 537mm 로 250mm 물러나면 요각이 27° 바뀐다고 말한다. 실측이
        그보다 훨씬 작으면(2026-08-05 에 6° 였다) 조향이 명령대로 안 들어가는
        것이므로 **제어가 아니라 그 아래를 봐야 한다.** 그 판단을 매번 로그에서
        바로 할 수 있게 실효 반경까지 계산해 둔다.
        """
        yaw0, dist0 = self._retreat_start
        if yaw0 is None or dist0 is None or error.distance_mm is None:
            return
        backed = error.distance_mm - dist0
        turned = (error.yaw_deg - yaw0) if error.yaw_deg is not None else None
        line = f"후진 끝 — {backed:.0f}mm"
        if turned is not None:
            line += f" 물러나며 요각 {yaw0:+.1f}° → {error.yaw_deg:+.1f}° ({turned:+.1f}°)"
            if abs(turned) > 0.5 and backed > 0:
                radius = backed / math.radians(abs(turned))
                line += f" · 실효반경 {radius:.0f}mm"
        self.retreat_reports.append(line)

    def _retreated_far_enough(self, error: AlignError) -> bool:
        """충분히 물러났나 — `_reached`의 **정확한 반대**다.

        ⚠️ 부등호가 `_reached`와 둘 다 뒤집힌다: 멀어질수록 `distance_mm`은 커지고
        `approach_px`는 작아진다. 두 벌을 각자 손으로 쓰면 언젠가 하나를 뒤집는다.
        """
        if error.distance_mm is not None:
            return error.distance_mm >= self._retreat_target
        return error.approach_px <= self.align_enter_px

    def _retreat(self, error: AlignError | None, dt: float) -> DriveCommand | None:
        """후진 한 프레임. **`None`을 돌려주면 후진이 끝났다**는 뜻이고,
        호출자는 그 프레임을 평소 흐름으로 마저 처리한다.

        조향은 `retreat_steer_gain` 이 0이면(기본) **0**이라 곧게 물러난다. 켜면
        물러나면서 자세까지 고친다 — 부호 근거와 검증 방법은 `retreat_steering_for`
        와 `RETREAT_STEER_GAIN` 주석에 있다.

        ⚠️ **타깃을 놓친 프레임에서는 조향을 걸지 않는다.** 안 보이는 채로 꺾으면
        어디로 도는지 아무도 모른다.
        """
        self._retreat_elapsed += dt
        # 시간 상한이 거리 판정보다 **먼저** 온다. 거리 판정이 죽은 상황(검출 실패·
        # 값 튐)에서 계속 뒤로 가는 것이 가장 위험하기 때문이다.
        if self._retreat_elapsed >= self.retreat_max_s:
            self.phase = Phase.ABORT
            self.episode.outcome = "retreat_timeout"
            return DriveCommand(phase=Phase.ABORT, reason="후진 시간 상한 — 정지")

        if error is None:
            self._lost_for += dt
            if self._lost_for <= self.lost_grace_s:
                return DriveCommand(self.retreat_speed, 0.0, Phase.RETREAT,
                                    "후진 중 — 타깃 소실 유예")
            # **마지막으로 본 방향으로 꺾어 되찾아본다.** 파렛트는 화면 옆으로
            # 밀려나서 사라지므로, 어느 쪽으로 사라졌는지 알면 그 반대로 돌리면
            # 다시 들어온다. 후진 중이라 시야도 같이 넓어진다.
            #
            # ⚠️ **눈 감고 하는 동작이라 시간으로 못 박는다.** 되찾으면 정상 흐름으로
            #    돌아가고, `retreat_recover_s` 안에 못 찾으면 그때 끝낸다.
            if (self._last_seen_lateral is not None
                    and self._lost_for <= self.lost_grace_s + self.retreat_recover_s):
                pull = 1.0 if self._last_seen_lateral > 0 else -1.0
                return DriveCommand(
                    self.retreat_speed,
                    _clamp(self.retreat_steer_gain * RETREAT_MAX_ANGULAR * pull,
                           -RETREAT_MAX_ANGULAR, RETREAT_MAX_ANGULAR),
                    Phase.RETREAT,
                    f"타깃 소실 — 마지막 방향({'우' if pull > 0 else '좌'})으로 복귀 시도")
            # 뒤가 안 보이는 채로 계속 물러나지 않는다.
            self.phase = Phase.ABORT
            self.episode.outcome = "lost_while_retreating"
            return DriveCommand(phase=Phase.ABORT, reason="후진 중 타깃 소실 — 정지")

        self._lost_for = 0.0
        self._last_seen_lateral = error.lateral_ratio
        if self._retreated_far_enough(error):
            self._report_retreat(error)
            self.phase = Phase.APPROACH
            # 후진 구간의 좌우 오차 변화는 전진 제어의 감쇠항 입력이 될 수 없다
            # (부호가 반대다). 끊어준다.
            self._last_lateral = None
            return None

        return DriveCommand(self.retreat_speed,
                            retreat_steering_for(error, self.retreat_steer_gain),
                            Phase.RETREAT,
                            f"후진 재접근 {self.retries}/{self.max_retries}")

    def step(self, error: AlignError | None, dt: float) -> DriveCommand:
        """한 프레임 진행한다. `error=None`이면 타깃을 못 본 프레임이다.

        `dt`는 실제 프레임 간격을 넣는다 — **22fps(45ms) 전제**이고 고정 주기가 아니다.
        """
        self._t += dt
        cmd = self._advance(error, dt)
        if self._stalled(error, cmd):
            # **여기서 시끄럽게 끝낸다.** 그냥 두면 남은 시간 내내 정상처럼 로그를
            # 찍다가 "제한 시간 초과" 로 끝나, 원인이 로그 어디에도 안 남는다.
            self.phase = Phase.ABORT
            self.episode.outcome = "stalled"
            cmd = DriveCommand(
                phase=Phase.ABORT,
                reason=f"{self.stall_window_s:.0f}초 동안 안 나아갔다 — "
                       "차가 멎었다(브리지·전원·구동계를 본다)")
        # 감쇠항은 **직전 프레임과의 차이**로 구하므로 여기서 갱신한다.
        # 타깃을 놓친 프레임은 기록하지 않는다 — 놓친 구간을 건너뛴 차이를
        # 변화율로 쓰면 없는 급변을 만들어낸다.
        if error is not None:
            self._last_lateral = error.lateral_ratio
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

        # 재접근 중 — 끝나면 None 이 와서 아래 평소 흐름으로 이어진다.
        if self.phase is Phase.RETREAT:
            cmd = self._retreat(error, dt)
            if cmd is not None:
                return cmd

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

        # 진입 거리에 왔다 — 여기서 결판이 난다. 단, **한 프레임으로 정하지 않는다**:
        # VERIFY 로 넘어가 몇 프레임을 모아 중앙값으로 판단한다.
        if self.phase is Phase.VERIFY or self._reached(error, self.insert_enter_px,
                                                       self.insert_enter_mm):
            # ⚠️ 시작부터 진입 거리 안쪽이면 그건 재접근이 아니라 **출발 위치가
            # 틀린 것**이다. 조용히 물러나면 "왜 갑자기 뒤로 가지" 가 된다.
            if not self._ever_beyond_insert:
                self.phase = Phase.ABORT
                self.episode.outcome = "started_too_close"
                return DriveCommand(
                    phase=Phase.ABORT,
                    reason="출발부터 진입 거리 안쪽이다 — 차를 뒤로 빼고 시작한다")
            if self.phase is not Phase.VERIFY:
                self.phase = Phase.VERIFY
                self._verify = []
            return self._verify_step(error)

        # 진입 거리 밖을 봤다 — 정상적인 접근 경로에 있다는 뜻.
        self._ever_beyond_insert = True

        lateral_rate = self._lateral_rate(error, dt)

        if self._reached(error, self.align_enter_px, self.align_enter_mm):
            self.phase = Phase.ALIGN
            turn = steering_for(error, self.k_lateral,
                                self.k_yaw * self.align_yaw_boost,
                                lateral_rate=lateral_rate,
                                k_lateral_rate=self.k_lateral_rate)
            return DriveCommand(
                # 꺾을수록 저항이 커서 ALIGN 속도로는 못 움직인다 —
                # `STEER_DRAG_BOOST` 주석 참조.
                linear_x=self._speed_for_turn(turn),
                angular_z=turn,
                phase=Phase.ALIGN, reason="정렬 중(요 우선)")

        self.phase = Phase.APPROACH
        return DriveCommand(linear_x=CRUISE_SPEED,
                            angular_z=steering_for(
                                error, self.k_lateral, self.k_yaw,
                                lateral_rate=lateral_rate,
                                k_lateral_rate=self.k_lateral_rate),
                            phase=Phase.APPROACH, reason="접근 중")
