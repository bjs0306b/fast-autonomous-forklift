"""포크 정렬 제어 루프 테스트 (S15P11A304-152).

게인 값은 미조정이라 테스트하지 않는다 — **부호·전이·안전성**만 본다. 실물에서 바뀔
숫자를 테스트에 박으면 조정할 때마다 테스트가 깨진다.
"""

from __future__ import annotations

from control.fork_servo import (
    _SPAN_PX_AT_1MM,
    ALIGN_ENTER_MM,
    CRUISE_SPEED,
    ALIGN_ENTER_PX,
    INSERT_ENTER_MM,
    INSERT_ENTER_PX,
    MAX_ANGULAR,
    DriveCommand,
    RETREAT_MAX_ANGULAR,
    RETREAT_MAX_STEP_MM,
    RETREAT_MIN_STEP_MM,
    RETREAT_SPEED_ACTUAL,
    RETREAT_TARGET_MM,
    VERIFY_FRAMES,
    ForkServo,
    Phase,
    retreat_steering_for,
    retreat_step_for,
    steering_for,
)
from perception.fork_align import AlignError

DT = 0.045      # 22fps

APPROACH_PX = ALIGN_ENTER_PX / 2
"""아직 ALIGN 에 못 든 거리(=APPROACH 단계).

⚠️ **임계에서 유도한다.** 종전에 200.0 이 박혀 있었는데, 임계가 바뀌자(2026-08-03,
ALIGN_ENTER_PX 260→95) 같은 값이 조용히 **다른 단계**를 뜻하게 됐다. 테스트가 잡아
줬지만, 고정값을 튜닝 대상 상수와 비교하면 언제든 되풀이된다."""


def err(lateral=0.0, yaw=0.0, approach=None, distance_mm=None) -> AlignError:
    """시험용 오차 하나.

    ⚠️ **거리를 주면 폭은 그 거리에서 나올 값으로 맞춘다.** 실물에서는 둘 다 같은
    검출에서 나오므로 서로 모순일 수 없고, 제어기는 그 모순을 **검출 오류로 보고
    버린다**(`_implausible`). 따로 주면 시험이 실물에 없는 입력을 만들게 된다.
    """
    if approach is None:
        approach = (_SPAN_PX_AT_1MM / distance_mm
                    if distance_mm else APPROACH_PX)
    return AlignError(lateral_ratio=lateral, yaw_signal=yaw, approach_px=approach,
                      distance_mm=distance_mm)


ALIGNED_FAR = err(approach=APPROACH_PX)
ALIGNED_INSERT = err(approach=INSERT_ENTER_PX + 10)


def decide(servo: ForkServo, at_insert: AlignError,
           far: AlignError = ALIGNED_FAR) -> DriveCommand:
    """진입 거리까지 정상 경로로 데려가 **판단이 끝난 명령**을 돌려준다.

    두 가지를 대신해준다:

    - **진입 거리 밖을 한 번 보여준다.** 첫 프레임부터 안쪽이면 상태기계가
      `started_too_close` 로 끊는다 — 재접근이 아니라 출발 위치가 틀린 것이라서다.
    - **VERIFY 프레임을 채운다.** 진입 여부는 한 프레임이 아니라 여러 프레임의
      중앙값으로 정해진다.
    """
    servo.step(far, DT)
    cmd = servo.step(at_insert, DT)
    for _ in range(VERIFY_FRAMES):
        if cmd.phase is not Phase.VERIFY:
            break
        cmd = servo.step(at_insert, DT)
    return cmd


# --- 조향 부호 ---

def test_파렛트가_오른쪽이면_우회전한다() -> None:
    """ROS 관례상 우회전은 음의 각속도."""
    assert steering_for(err(lateral=0.5)) < 0


def test_파렛트가_왼쪽이면_좌회전한다() -> None:
    assert steering_for(err(lateral=-0.5)) > 0


def test_오른쪽_끝이_멀면_우회전한다() -> None:
    """yaw_signal>0 = 면 법선이 우리 오른쪽 → 그 법선 위로 돌아 들어가야 한다."""
    assert steering_for(err(yaw=0.4)) < 0


def test_정렬되면_조향이_0() -> None:
    assert steering_for(err()) == 0.0


def test_조향은_상한을_넘지_않는다() -> None:
    assert abs(steering_for(err(lateral=99, yaw=99))) <= MAX_ANGULAR
    assert abs(steering_for(err(lateral=-99, yaw=-99))) <= MAX_ANGULAR


# --- 상태 전이 ---

def test_타깃이_없으면_정지한다() -> None:
    servo = ForkServo()
    cmd = servo.step(None, DT)
    assert cmd.phase is Phase.SEARCH
    assert cmd.linear_x == 0.0 and cmd.angular_z == 0.0


def test_멀면_접근한다() -> None:
    servo = ForkServo()
    cmd = servo.step(ALIGNED_FAR, DT)
    assert cmd.phase is Phase.APPROACH
    assert cmd.linear_x > 0


def test_가까워지면_정렬단계로() -> None:
    servo = ForkServo()
    cmd = servo.step(err(approach=ALIGN_ENTER_PX + 10), DT)
    assert cmd.phase is Phase.ALIGN


def test_정렬단계가_접근보다_느리다() -> None:
    """가까울수록 천천히 — 조향 여지가 줄어든다."""
    servo = ForkServo()
    approach = servo.step(ALIGNED_FAR, DT)
    align = servo.step(err(approach=ALIGN_ENTER_PX + 10), DT)
    assert align.linear_x < approach.linear_x


def test_정렬단계는_요를_더_세게_잡는다() -> None:
    servo = ForkServo()
    servo.step(err(yaw=0.2, approach=APPROACH_PX), DT)
    approach_turn = servo._last.angular_z
    servo.step(err(yaw=0.2, approach=ALIGN_ENTER_PX + 10), DT)
    assert abs(servo._last.angular_z) > abs(approach_turn)


def test_정렬된_채_진입거리면_진입한다() -> None:
    servo = ForkServo()
    cmd = decide(servo, ALIGNED_INSERT)
    assert cmd.phase is Phase.INSERT
    assert cmd.angular_z == 0.0, "진입은 직선이다 — 조향하면 포크가 긁는다"
    assert cmd.linear_x > 0


def test_진입_판단은_한_프레임으로_안_한다() -> None:
    """튄 프레임 하나가 진입을 확정시켜 110mm만 가고 끝난 적이 있다(2026-08-05)."""
    servo = ForkServo()
    servo.step(ALIGNED_FAR, DT)
    cmd = servo.step(ALIGNED_INSERT, DT)
    assert cmd.phase is Phase.VERIFY
    assert cmd.angular_z == 0.0, "판단 중에는 조향하지 않는다"
    assert cmd.linear_x > 0, "멈추면 정지마찰로 다시 못 출발한다"


def test_판단은_중앙값이라_튄_프레임_하나에_안_속는다() -> None:
    servo = ForkServo()
    servo.step(ALIGNED_FAR, DT)
    servo.step(err(lateral=9.9, approach=INSERT_ENTER_PX + 10), DT)   # 튄 프레임
    decide(servo, ALIGNED_INSERT)
    cmd = servo.step(ALIGNED_INSERT, DT)
    assert cmd.phase is Phase.INSERT, "정상 두 프레임이 이상치 하나를 눌러야 한다"


def test_출발부터_진입거리_안쪽이면_시끄럽게_끝낸다() -> None:
    """조용히 물러나면 '왜 갑자기 뒤로 가지'가 된다 — 출발 위치가 틀린 것이다."""
    servo = ForkServo()
    cmd = servo.step(ALIGNED_INSERT, DT)
    assert cmd.phase is Phase.ABORT
    assert cmd.linear_x == 0.0
    assert servo.episode.outcome == "started_too_close"


def test_미정렬로_진입거리에_닿으면_중단한다() -> None:
    """뒷바퀴 조향은 제자리 회전이 안 돼 코앞에서 못 고친다.

    재접근을 끄면(`max_retries=0`) 종전처럼 그 자리에서 끝난다."""
    servo = ForkServo(max_retries=0)
    cmd = decide(servo, err(lateral=0.5, approach=INSERT_ENTER_PX + 10))
    assert cmd.phase is Phase.ABORT
    assert cmd.linear_x == 0.0
    assert servo.episode.outcome == "misaligned_at_insert"


def test_요만_틀어져도_중단한다() -> None:
    servo = ForkServo(max_retries=0)
    cmd = decide(servo, err(yaw=0.5, approach=INSERT_ENTER_PX + 10))
    assert cmd.phase is Phase.ABORT


# --- 재접근 (S15P11A304-154) ---

MISALIGNED_INSERT = err(lateral=0.5, approach=INSERT_ENTER_PX + 10)
"""진입 거리인데 좌우가 틀어진 상태 — 재접근을 유발하는 입력."""

FAR = err(lateral=0.5, approach=ALIGN_ENTER_PX - 10)
"""ALIGN 진입 거리보다 **더 먼** 상태 = 충분히 물러난 것."""


def test_미정렬이면_기본적으로_물러난다() -> None:
    servo = ForkServo()
    cmd = decide(servo, MISALIGNED_INSERT)
    assert cmd.phase is Phase.RETREAT
    assert cmd.linear_x < 0, "후진이므로 음수여야 한다"
    assert cmd.angular_z == 0.0, "후진 조향 부호는 미검증이라 곧게 물러난다"
    assert servo.retries == 1


def test_충분히_물러나면_다시_접근한다() -> None:
    servo = ForkServo()
    decide(servo, MISALIGNED_INSERT)
    cmd = servo.step(FAR, DT)
    assert cmd.phase is Phase.APPROACH
    assert cmd.linear_x > 0, "다시 앞으로 가야 한다"


def test_아직_덜_물러났으면_계속_후진한다() -> None:
    servo = ForkServo()
    decide(servo, MISALIGNED_INSERT)
    cmd = servo.step(err(lateral=0.5, approach=INSERT_ENTER_PX), DT)
    assert cmd.phase is Phase.RETREAT
    assert cmd.linear_x < 0


def test_재시도를_소진하면_중단한다() -> None:
    """무한 재시도는 같은 실패를 반복한다 — 사람이 보게 끝낸다."""
    servo = ForkServo(max_retries=2)
    cmd = decide(servo, MISALIGNED_INSERT)
    for _ in range(2):
        assert cmd.phase is Phase.RETREAT
        assert servo.step(FAR, DT).phase is Phase.APPROACH
        cmd = decide(servo, MISALIGNED_INSERT, far=FAR)
    assert cmd.phase is Phase.ABORT
    assert cmd.linear_x == 0.0
    assert servo.episode.outcome == "misaligned_at_insert"
    assert servo.retries == 2


# --- 후진량은 틀어진 만큼 ---

def test_조금_틀어졌으면_조금만_물러난다() -> None:
    small = retreat_step_for(0.1, 2.0)
    big = retreat_step_for(0.8, 25.0)
    assert small < big
    assert small == RETREAT_MIN_STEP_MM, "아주 작으면 최소 걸음으로 떨어진다"


def test_후진량은_한_걸음_상한을_안_넘는다() -> None:
    """크게 틀어졌다고 한 번에 다 물러나지 않는다 — 여러 번 조금씩."""
    assert retreat_step_for(9.9, 80.0) == RETREAT_MAX_STEP_MM


def test_요각이_크면_그만큼_활주로를_잡는다() -> None:
    """R·sin ψ — 30°면 268mm 가 필요하다."""
    # 30°는 R·sin30° = 268mm 를 요구한다 — 한 걸음 상한(120mm)을 넘으므로 잘린다.
    # 여러 번 나눠 물러나는 것이 설계다.
    assert retreat_step_for(0.0, 30.0) == RETREAT_MAX_STEP_MM
    assert retreat_step_for(0.0, 10.0) < retreat_step_for(0.0, 30.0)


def test_요각을_모르면_좌우로만_계산한다() -> None:
    assert retreat_step_for(0.5, None) == retreat_step_for(0.5, 0.0)


def test_후진_목표는_지금_거리에서_그만큼_더_간다() -> None:
    servo = ForkServo()
    cmd = decide(servo, err(lateral=0.5, distance_mm=INSERT_ENTER_MM - 10),
                 far=err(distance_mm=ALIGN_ENTER_MM + 50))
    assert cmd.phase is Phase.RETREAT
    expected = (INSERT_ENTER_MM - 10) + retreat_step_for(0.5, None)
    assert servo._retreat_target == expected


def test_후진이_시간_상한에_걸리면_정지한다() -> None:
    """거리 판정이 죽은 채 계속 뒤로 가는 것이 가장 위험하다. 뒤는 안 보인다."""
    servo = ForkServo(retreat_max_s=0.2)
    decide(servo, MISALIGNED_INSERT)
    for _ in range(20):
        cmd = servo.step(err(lateral=0.5, approach=INSERT_ENTER_PX), DT)
        if cmd.phase is Phase.ABORT:
            break
    assert cmd.phase is Phase.ABORT
    assert cmd.linear_x == 0.0
    assert servo.episode.outcome == "retreat_timeout"


def test_후진_중_타깃을_잃으면_정지한다() -> None:
    servo = ForkServo(lost_grace_s=0.05)
    decide(servo, MISALIGNED_INSERT)
    for _ in range(10):
        cmd = servo.step(None, DT)
        if cmd.phase is Phase.ABORT:
            break
    assert cmd.phase is Phase.ABORT
    assert cmd.linear_x == 0.0
    assert servo.episode.outcome == "lost_while_retreating"


def test_요각이_크면_진입을_허가하지_않는다() -> None:
    """2026-08-05: 요각 -58.5°인데 yaw_signal은 -0.07이라 통과했고 포크가 스쳤다."""
    servo = ForkServo()
    at_insert = AlignError(lateral_ratio=0.0, yaw_signal=-0.07,
                           approach_px=INSERT_ENTER_PX + 10, yaw_deg=-58.5,
                           distance_mm=200.0)
    cmd = decide(servo, at_insert, far=err(distance_mm=ALIGN_ENTER_MM + 50))
    assert cmd.phase is not Phase.INSERT, "각도를 알면 각도로 판정해야 한다"


def test_요각이_작으면_진입한다() -> None:
    servo = ForkServo()
    at_insert = AlignError(lateral_ratio=0.0, yaw_signal=-0.07,
                           approach_px=INSERT_ENTER_PX + 10, yaw_deg=-3.0,
                           distance_mm=200.0)
    cmd = decide(servo, at_insert, far=err(distance_mm=ALIGN_ENTER_MM + 50))
    assert cmd.phase is Phase.INSERT


def test_후진_조향은_최대_권한까지_쓴다() -> None:
    """0.15를 곱해 상한의 6%만 나가던 탓에 3초를 물러나도 자세가 5°밖에 안 바뀌었다."""
    big = AlignError(lateral_ratio=0.0, yaw_signal=-0.09,
                     approach_px=INSERT_ENTER_PX, yaw_deg=-45.0)
    assert abs(retreat_steering_for(big, gain=1.0)) == RETREAT_MAX_ANGULAR


def test_후진_조향_게인이_음수면_뒤집힌다() -> None:
    """링키지 극성이 전진으로만 맞춰져 있어 부호를 실물로 갈라야 한다."""
    e = AlignError(lateral_ratio=0.0, yaw_signal=0.0,
                   approach_px=INSERT_ENTER_PX, yaw_deg=-30.0)
    assert retreat_steering_for(e, gain=1.0) == -retreat_steering_for(e, gain=-1.0)


def test_후진_조향_게인_0이면_곧게_물러난다() -> None:
    assert retreat_steering_for(err(lateral=0.5, yaw=0.5), gain=0.0) == 0.0
    servo = ForkServo(retreat_steer_gain=0.0)
    assert decide(servo, MISALIGNED_INSERT).angular_z == 0.0


def test_후진_조향은_요를_같은_방향으로_지운다() -> None:
    """"오른쪽으로 돈다"는 진행 방향과 무관하다 — 요 항은 전진과 부호가 같다."""
    assert retreat_steering_for(err(yaw=0.4), gain=0.15) < 0
    assert retreat_steering_for(err(yaw=-0.4), gain=0.15) > 0


def test_후진_조향은_좌우를_안_본다() -> None:
    """좌우까지 넣었더니 두 항이 상쇄돼 요각 40°를 남기고 조향이 0으로 죽었다."""
    assert retreat_steering_for(err(lateral=0.9), gain=1.0) == 0.0
    assert steering_for(err(lateral=0.9)) != 0.0


def test_후진_조향도_상한을_넘지_않는다() -> None:
    assert abs(retreat_steering_for(err(yaw=-99), gain=9.9)) <= RETREAT_MAX_ANGULAR


def test_후진_중_타깃을_놓치면_조향을_안_건다() -> None:
    """안 보이는 채로 꺾으면 어디로 도는지 아무도 모른다."""
    servo = ForkServo(retreat_steer_gain=0.15)
    decide(servo, MISALIGNED_INSERT)
    assert servo.step(None, DT).angular_z == 0.0


def test_후진_속도가_양수면_거부한다() -> None:
    """양수를 주면 물러나는 대신 파렛트로 밀고 들어간다."""
    import pytest
    with pytest.raises(ValueError):
        ForkServo(retreat_speed=0.12)


# --- 진입은 개루프 ---

def test_진입_중_타깃을_잃어도_계속_간다() -> None:
    """코앞에서 구멍이 화면 밖으로 나가는 건 정상이다(153 블라인드 진입)."""
    servo = ForkServo()
    decide(servo, ALIGNED_INSERT)
    for _ in range(10):
        cmd = servo.step(None, DT)
        assert cmd.phase is Phase.INSERT
        assert cmd.linear_x > 0


def test_진입_시간이_지나면_완료된다() -> None:
    servo = ForkServo(insert_duration_s=0.2)
    decide(servo, ALIGNED_INSERT)
    for _ in range(10):
        cmd = servo.step(None, DT)
    assert cmd.phase is Phase.DONE
    assert cmd.linear_x == 0.0
    assert servo.episode.outcome == "inserted"


def test_진입_중에는_되돌아가지_않는다() -> None:
    """오차가 커 보여도 무시한다 — 되돌릴 수 없는 구간이다."""
    servo = ForkServo()
    decide(servo, ALIGNED_INSERT)
    cmd = servo.step(err(lateral=9.0, approach=INSERT_ENTER_PX + 200), DT)
    assert cmd.phase is Phase.INSERT


# --- 소실 유예 ---

def test_잠깐_놓치면_직전_명령을_유지한다() -> None:
    """매 프레임 급제동하면 검출이 깜빡일 때마다 덜컹거린다."""
    servo = ForkServo(lost_grace_s=0.2)
    moving = servo.step(err(lateral=0.3, approach=APPROACH_PX), DT)
    kept = servo.step(None, DT)
    assert kept.phase is Phase.APPROACH
    assert kept.linear_x == moving.linear_x
    assert kept.angular_z == moving.angular_z


def test_유예를_넘기면_정지한다() -> None:
    servo = ForkServo(lost_grace_s=0.1)
    servo.step(err(lateral=0.3, approach=APPROACH_PX), DT)
    for _ in range(5):
        cmd = servo.step(None, DT)
    assert cmd.phase is Phase.SEARCH
    assert cmd.linear_x == 0.0


def test_종료_후에는_계속_정지한다() -> None:
    servo = ForkServo(max_retries=0)
    decide(servo, err(lateral=0.5, approach=INSERT_ENTER_PX + 10))
    assert servo.is_finished()
    assert servo.step(ALIGNED_FAR, DT).linear_x == 0.0


def test_reset하면_다시_시도할_수_있다() -> None:
    servo = ForkServo()
    decide(servo, err(lateral=0.5, approach=INSERT_ENTER_PX + 10))
    servo.reset()
    assert servo.phase is Phase.SEARCH
    assert servo.step(ALIGNED_FAR, DT).phase is Phase.APPROACH


# --- 에피소드 로깅 ---

# --- 거리 임계: 캘리브레이션 전(px) / 후(mm) ---

def test_거리가_있으면_mm_임계를_쓴다() -> None:
    """캘리 후에는 실제 거리로 판단한다 — 픽셀 값은 무시한다."""
    servo = ForkServo()
    # 폭(px)으로는 아직 멀지만, 실제 거리는 이미 진입 범위다.
    # ⚠️ 폭을 아무 값이나 주면 안 된다 — 거리와 너무 어긋나면 제어기가 **검출
    # 오류로 보고 버린다**(`_implausible`). 예측 폭의 절반이면 게이트는 통과하되
    # px 임계(295)에는 못 미쳐, mm 이 우선인지 그대로 가른다.
    cmd = decide(servo, err(approach=_SPAN_PX_AT_1MM / (INSERT_ENTER_MM - 10) * 0.5,
                            distance_mm=INSERT_ENTER_MM - 10),
                 far=err(distance_mm=ALIGN_ENTER_MM + 50))
    assert cmd.phase is Phase.INSERT


def test_부등호_방향이_반대다() -> None:
    """approach_px는 가까울수록 크고 distance_mm은 가까울수록 작다.

    한쪽을 뒤집어 쓰면 '멀수록 진입'이 돼 파렛트를 향해 돌진한다.
    """
    servo = ForkServo()
    # 폭은 **크게** 줘도 게이트에 안 걸린다(작을 때만 모순으로 본다).
    far = servo.step(err(approach=9999.0, distance_mm=ALIGN_ENTER_MM + 500), DT)
    assert far.phase is Phase.APPROACH, "거리가 멀면 폭이 커도 접근 단계여야 한다"

    servo.reset()
    near = servo.step(err(approach=_SPAN_PX_AT_1MM / (ALIGN_ENTER_MM - 10) * 0.5,
                          distance_mm=ALIGN_ENTER_MM - 10), DT)
    assert near.phase is Phase.ALIGN, "거리가 가까우면 폭이 작아도 정렬 단계여야 한다"


def test_거리가_없으면_px_임계로_돌아간다() -> None:
    """캘리브레이션 전에도 동작해야 한다 — 회귀 방지."""
    servo = ForkServo()
    assert decide(servo, err(approach=INSERT_ENTER_PX + 10)).phase is Phase.INSERT
    servo.reset()
    assert servo.step(err(approach=ALIGN_ENTER_PX + 10), DT).phase is Phase.ALIGN
    servo.reset()
    assert servo.step(err(approach=APPROACH_PX), DT).phase is Phase.APPROACH


def test_mm_임계도_미정렬이면_중단한다() -> None:
    servo = ForkServo(max_retries=0)
    cmd = decide(servo, err(lateral=0.5, distance_mm=INSERT_ENTER_MM - 10), far=err(distance_mm=ALIGN_ENTER_MM + 50))
    assert cmd.phase is Phase.ABORT


def test_mm_임계로도_물러났는지_판정한다() -> None:
    """⚠️ 부등호가 `_reached`와 반대다 — 멀어질수록 mm는 커지고 px는 작아진다."""
    servo = ForkServo()
    decide(servo, err(lateral=0.5, distance_mm=INSERT_ENTER_MM - 10),
           far=err(distance_mm=ALIGN_ENTER_MM + 50))
    target = servo._retreat_target
    assert servo.step(err(lateral=0.5, distance_mm=target - 10),
                      DT).phase is Phase.RETREAT
    # 목표를 넘겼으면 후진을 끝내고 앞으로 간다. 목표가 ALIGN 진입 거리(700) 안쪽
    # 이므로 단계는 ALIGN 이다 — 중요한 것은 **전진으로 돌아섰다**는 것.
    resumed = servo.step(err(lateral=0.5, distance_mm=target + 10), DT)
    assert resumed.phase is Phase.ALIGN
    assert resumed.linear_x > 0


# --- 멎음 감지 ---

def test_거리가_안_줄면_멎은_것으로_중단한다() -> None:
    """2026-08-05에 세 번 조용히 실패했다 — 명령은 나가는데 차가 안 움직였다."""
    servo = ForkServo(stall_window_s=1.0)
    for _ in range(60):
        cmd = servo.step(err(distance_mm=500.0), DT)
        if cmd.phase is Phase.ABORT:
            break
    assert cmd.phase is Phase.ABORT
    assert cmd.linear_x == 0.0
    assert servo.episode.outcome == "stalled"


def test_정상_전진이면_멎음으로_안_본다() -> None:
    servo = ForkServo(stall_window_s=1.0)
    distance = 690.0
    for _ in range(60):
        cmd = servo.step(err(distance_mm=distance), DT)
        assert cmd.phase is not Phase.ABORT, "정상 접근을 멎음으로 오판했다"
        if cmd.phase is Phase.INSERT:
            break
        # ⚠️ **`ALIGN_SPEED`(0.06)로 바꾸지 말 것.** 그건 명령값이고 이건 실측 속도다 —
        #    구동 PWM 하한 때문에 실제로는 명령보다 빨리 구른다(S15P11A304-198).
        #    대응하는 상수가 없어서 리터럴로 둔다. 둘을 같은 값으로 만들면 이 시뮬은
        #    2.5배 느리게 돌아 멎음 판정을 잘못 검증한다.
        distance -= 0.15 * 1000 * DT      # ALIGN 실측 속도 0.15 m/s
    assert servo.episode.outcome != "stalled"


def test_후진은_거리가_늘어야_나아간_것이다() -> None:
    """전진과 부호가 반대다 — 방향을 안 나누면 정상 후진이 멎음으로 잡힌다."""
    servo = ForkServo(stall_window_s=1.0)
    decide(servo, err(lateral=0.5, distance_mm=INSERT_ENTER_MM - 10), far=err(distance_mm=ALIGN_ENTER_MM + 50))
    distance = 200.0
    for _ in range(60):
        cmd = servo.step(err(lateral=0.5, distance_mm=distance), DT)
        assert cmd.phase is not Phase.ABORT, "정상 후진을 멎음으로 오판했다"
        if cmd.phase is not Phase.RETREAT:
            break
        # ⚠️ 실측 후진 속도를 **상수에서 가져온다.** 종전엔 0.138 이 박혀 있었는데,
        #    전진 쪽 INSERT_SPEED_ACTUAL 은 0.244 → 0.138 → 0.110 으로 두 번 바뀌었다.
        #    후진 값이 바뀌는 날 이 테스트만 옛 속도로 시뮬레이션하게 된다.
        distance += RETREAT_SPEED_ACTUAL * 1000 * DT
    assert servo.episode.outcome != "stalled"


def test_후진이_안_되면_멎음으로_잡는다() -> None:
    servo = ForkServo(stall_window_s=1.0)
    decide(servo, err(lateral=0.5, distance_mm=INSERT_ENTER_MM - 10), far=err(distance_mm=ALIGN_ENTER_MM + 50))
    for _ in range(60):
        cmd = servo.step(err(lateral=0.5, distance_mm=200.0), DT)
        if cmd.phase is Phase.ABORT:
            break
    assert servo.episode.outcome == "stalled"


def test_거리를_모르면_멎음_판정을_안_한다() -> None:
    """캘리브레이션 전에는 잴 자가 없다 — 없는 근거로 중단하지 않는다."""
    servo = ForkServo(stall_window_s=1.0)
    for _ in range(60):
        cmd = servo.step(err(approach=APPROACH_PX), DT)
        assert cmd.phase is not Phase.ABORT


def test_멎음_감지는_끌_수_있다() -> None:
    servo = ForkServo(stall_window_s=0.0)
    for _ in range(60):
        cmd = servo.step(err(distance_mm=500.0), DT)
        assert cmd.phase is not Phase.ABORT


def test_모든_프레임이_기록된다() -> None:
    """154 판정 근거이자, 나중에 모방학습을 붙일 여지."""
    servo = ForkServo()
    servo.step(ALIGNED_FAR, DT)
    servo.step(None, DT)
    assert len(servo.episode.samples) == 2
    t, e, cmd = servo.episode.samples[0]
    assert t > 0 and e is ALIGNED_FAR and isinstance(cmd, DriveCommand)


def test_reset하면_기록이_새로_시작된다() -> None:
    servo = ForkServo()
    servo.step(ALIGNED_FAR, DT)
    servo.reset()
    assert servo.episode.samples == []


# --- 감쇠항 (S15P11A304-152) ---
#
# 08-04 실주행에서 lat 이 주기 1.8초 사인파로 흔들렸다. 조향각 → 요레이트 →
# 헤딩 → 좌우위치로 적분이 두 번 들어가는 계에 비례항만 걸면 감쇠비가 0 이다.
# 아래는 **부호와 기본 꺼짐**만 못 박는다 — 효과는 실물로만 확인된다.


def test_감쇠는_기본으로_꺼져_있다() -> None:
    """검증 전까지 기본 거동이 바뀌면 안 된다."""
    with_rate = steering_for(err(lateral=0.3), lateral_rate=5.0)
    without = steering_for(err(lateral=0.3), lateral_rate=0.0)
    assert with_rate == without


def test_감쇠는_오차가_커지는_방향을_거스른다() -> None:
    """오차가 벌어지는 중이면 조향을 더 세게, 좁혀지는 중이면 덜 세게."""
    base = steering_for(err(lateral=0.3), k_lateral_rate=0.05)
    widening = steering_for(err(lateral=0.3), lateral_rate=+1.0,
                            k_lateral_rate=0.05)
    closing = steering_for(err(lateral=0.3), lateral_rate=-1.0,
                           k_lateral_rate=0.05)
    # lateral>0 이면 각속도가 음수(우회전)다. 벌어지는 중이면 더 음수여야 한다.
    assert widening < base < closing


def test_감쇠도_각속도_상한을_넘지_않는다() -> None:
    assert steering_for(err(lateral=0.3), lateral_rate=1e6,
                        k_lateral_rate=0.05) >= -MAX_ANGULAR


def test_첫_프레임에는_변화율을_지어내지_않는다() -> None:
    """직전 값이 없는데 변화율을 만들면 출발하자마자 조향이 튄다."""
    servo = ForkServo(k_lateral_rate=0.05)
    first = servo.step(err(lateral=0.3, approach=APPROACH_PX), DT)
    plain = ForkServo().step(err(lateral=0.3, approach=APPROACH_PX), DT)
    assert first.angular_z == plain.angular_z


def test_타깃을_놓친_구간은_변화율에_안_섞인다() -> None:
    """놓친 프레임을 건너뛴 차이를 변화율로 쓰면 없는 급변을 만들어낸다."""
    servo = ForkServo(k_lateral_rate=0.05, lost_grace_s=1.0)
    servo.step(err(lateral=0.1, approach=APPROACH_PX), DT)
    servo.step(None, DT)                       # 놓침 — 여기서 갱신하면 안 된다
    assert servo._last_lateral == 0.1


# --- 조향 저항 보정 ---

def test_곧게_갈_때는_ALIGN_속도_그대로() -> None:
    servo = ForkServo()
    assert servo._speed_for_turn(0.0) == servo.align_speed


def test_꺾을수록_빨라진다() -> None:
    """꺾인 채로는 ALIGN 속도로 못 움직인다 — 2026-08-05 에 3초간 한 발도 못 갔다."""
    servo = ForkServo()
    gentle = servo._speed_for_turn(MAX_ANGULAR * 0.25)
    hard = servo._speed_for_turn(MAX_ANGULAR)
    assert servo.align_speed < gentle < hard


def test_최대_조향에서는_순항_속도까지_올린다() -> None:
    """0.12 = 구동 50% 는 실측표에서 정지 출발이 보장되는 구간이다."""
    servo = ForkServo()
    assert servo._speed_for_turn(MAX_ANGULAR) == CRUISE_SPEED


def test_보정을_끄면_원래대로() -> None:
    servo = ForkServo(steer_drag_boost=0.0)
    assert servo._speed_for_turn(MAX_ANGULAR) == servo.align_speed


# --- 시야 유지·복귀 ---

def test_화면_밖으로_밀리면_좌우부터_잡는다() -> None:
    """타깃을 놓치면 재접근 자체가 끝난다 — 자세보다 시야가 먼저다."""
    far_side = AlignError(lateral_ratio=2.0, yaw_signal=0.0,
                          approach_px=INSERT_ENTER_PX, yaw_deg=30.0)
    turn = retreat_steering_for(far_side, gain=1.0)
    # 요각(+30°)만 봤다면 음수여야 하는데, 좌우를 잡느라 부호가 반대다.
    assert turn > 0
    assert retreat_steering_for(AlignError(lateral_ratio=0.0, yaw_signal=0.0,
                                           approach_px=INSERT_ENTER_PX,
                                           yaw_deg=30.0), gain=1.0) < 0


def test_타깃을_놓치면_마지막_방향으로_되찾아본다() -> None:
    servo = ForkServo(lost_grace_s=0.05, retreat_recover_s=1.0)
    decide(servo, err(lateral=0.9, approach=INSERT_ENTER_PX + 10))
    servo.step(err(lateral=0.9, approach=INSERT_ENTER_PX), DT)   # 마지막으로 본 위치
    cmd = servo.step(None, DT)
    for _ in range(4):
        cmd = servo.step(None, DT)
    assert cmd.phase is Phase.RETREAT, "바로 포기하지 않는다"
    assert cmd.linear_x < 0
    assert cmd.angular_z != 0.0, "마지막으로 본 방향으로 꺾어야 한다"


def test_복귀에도_못_찾으면_끝낸다() -> None:
    servo = ForkServo(lost_grace_s=0.05, retreat_recover_s=0.2)
    decide(servo, err(lateral=0.9, approach=INSERT_ENTER_PX + 10))
    servo.step(err(lateral=0.9, approach=INSERT_ENTER_PX), DT)
    for _ in range(20):
        cmd = servo.step(None, DT)
        if cmd.phase is Phase.ABORT:
            break
    assert cmd.phase is Phase.ABORT
    assert servo.episode.outcome == "lost_while_retreating"


# --- 검출 위생 게이트 (2026-08-07, 30회차) ---

def test_말이_안_되는_요각은_못_본_것으로_친다() -> None:
    """진입면이 보이는데 요각 -88° 는 나올 수 없다 — 구멍 짝을 잘못 묶은 것이다.

    30회차에서 **정면 파렛트**인데 이런 프레임이 섞여 재접근을 6번 유발했다.
    """
    servo = ForkServo()
    servo.step(err(distance_mm=ALIGN_ENTER_MM + 200), DT)
    bad = AlignError(lateral_ratio=74.6, yaw_signal=-0.17,
                     approach_px=_SPAN_PX_AT_1MM / 139.0, yaw_deg=-88.1,
                     distance_mm=139.0)
    cmd = servo.step(bad, DT)
    assert cmd.phase is not Phase.RETREAT, "쓰레기 검출로 물러나면 안 된다"


def test_폭과_거리가_모순이면_못_본_것으로_친다() -> None:
    """둘 다 같은 검출에서 나오므로 어긋날 수 없다 — 어긋나면 다른 물체다."""
    servo = ForkServo()
    servo.step(err(distance_mm=ALIGN_ENTER_MM + 200), DT)
    bad = AlignError(lateral_ratio=0.0, yaw_signal=0.0, approach_px=14.0,
                     yaw_deg=0.0, distance_mm=139.0)
    cmd = servo.step(bad, DT)
    assert cmd.phase is not Phase.INSERT
