"""포크 정렬 제어 루프 테스트 (S15P11A304-152).

게인 값은 미조정이라 테스트하지 않는다 — **부호·전이·안전성**만 본다. 실물에서 바뀔
숫자를 테스트에 박으면 조정할 때마다 테스트가 깨진다.
"""

from __future__ import annotations

from control.fork_servo import (
    ALIGN_ENTER_MM,
    ALIGN_ENTER_PX,
    INSERT_ENTER_MM,
    INSERT_ENTER_PX,
    MAX_ANGULAR,
    DriveCommand,
    ForkServo,
    Phase,
    steering_for,
)
from perception.fork_align import AlignError

DT = 0.045      # 22fps

APPROACH_PX = ALIGN_ENTER_PX / 2
"""아직 ALIGN 에 못 든 거리(=APPROACH 단계).

⚠️ **임계에서 유도한다.** 종전에 200.0 이 박혀 있었는데, 임계가 바뀌자(2026-08-03,
ALIGN_ENTER_PX 260→95) 같은 값이 조용히 **다른 단계**를 뜻하게 됐다. 테스트가 잡아
줬지만, 고정값을 튜닝 대상 상수와 비교하면 언제든 되풀이된다."""


def err(lateral=0.0, yaw=0.0, approach=APPROACH_PX, distance_mm=None) -> AlignError:
    return AlignError(lateral_ratio=lateral, yaw_signal=yaw, approach_px=approach,
                      distance_mm=distance_mm)


ALIGNED_FAR = err(approach=APPROACH_PX)
ALIGNED_INSERT = err(approach=INSERT_ENTER_PX + 10)


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
    cmd = servo.step(ALIGNED_INSERT, DT)
    assert cmd.phase is Phase.INSERT
    assert cmd.angular_z == 0.0, "진입은 직선이다 — 조향하면 포크가 긁는다"
    assert cmd.linear_x > 0


def test_미정렬로_진입거리에_닿으면_중단한다() -> None:
    """뒷바퀴 조향은 제자리 회전이 안 돼 코앞에서 못 고친다 — 물러나야 한다."""
    servo = ForkServo()
    cmd = servo.step(err(lateral=0.5, approach=INSERT_ENTER_PX + 10), DT)
    assert cmd.phase is Phase.ABORT
    assert cmd.linear_x == 0.0
    assert servo.episode.outcome == "misaligned_at_insert"


def test_요만_틀어져도_중단한다() -> None:
    servo = ForkServo()
    cmd = servo.step(err(yaw=0.5, approach=INSERT_ENTER_PX + 10), DT)
    assert cmd.phase is Phase.ABORT


# --- 진입은 개루프 ---

def test_진입_중_타깃을_잃어도_계속_간다() -> None:
    """코앞에서 구멍이 화면 밖으로 나가는 건 정상이다(153 블라인드 진입)."""
    servo = ForkServo()
    servo.step(ALIGNED_INSERT, DT)
    for _ in range(10):
        cmd = servo.step(None, DT)
        assert cmd.phase is Phase.INSERT
        assert cmd.linear_x > 0


def test_진입_시간이_지나면_완료된다() -> None:
    servo = ForkServo(insert_duration_s=0.2)
    servo.step(ALIGNED_INSERT, DT)
    for _ in range(10):
        cmd = servo.step(None, DT)
    assert cmd.phase is Phase.DONE
    assert cmd.linear_x == 0.0
    assert servo.episode.outcome == "inserted"


def test_진입_중에는_되돌아가지_않는다() -> None:
    """오차가 커 보여도 무시한다 — 되돌릴 수 없는 구간이다."""
    servo = ForkServo()
    servo.step(ALIGNED_INSERT, DT)
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
    servo = ForkServo()
    servo.step(err(lateral=0.5, approach=INSERT_ENTER_PX + 10), DT)
    assert servo.is_finished()
    assert servo.step(ALIGNED_FAR, DT).linear_x == 0.0


def test_reset하면_다시_시도할_수_있다() -> None:
    servo = ForkServo()
    servo.step(err(lateral=0.5, approach=INSERT_ENTER_PX + 10), DT)
    servo.reset()
    assert servo.phase is Phase.SEARCH
    assert servo.step(ALIGNED_FAR, DT).phase is Phase.APPROACH


# --- 에피소드 로깅 ---

# --- 거리 임계: 캘리브레이션 전(px) / 후(mm) ---

def test_거리가_있으면_mm_임계를_쓴다() -> None:
    """캘리 후에는 실제 거리로 판단한다 — 픽셀 값은 무시한다."""
    servo = ForkServo()
    # 폭(px)으로는 아직 멀지만, 실제 거리는 이미 진입 범위다
    cmd = servo.step(err(approach=10.0, distance_mm=INSERT_ENTER_MM - 10), DT)
    assert cmd.phase is Phase.INSERT


def test_부등호_방향이_반대다() -> None:
    """approach_px는 가까울수록 크고 distance_mm은 가까울수록 작다.

    한쪽을 뒤집어 쓰면 '멀수록 진입'이 돼 파렛트를 향해 돌진한다.
    """
    servo = ForkServo()
    far = servo.step(err(approach=9999.0, distance_mm=ALIGN_ENTER_MM + 500), DT)
    assert far.phase is Phase.APPROACH, "거리가 멀면 폭이 커도 접근 단계여야 한다"

    servo.reset()
    near = servo.step(err(approach=1.0, distance_mm=ALIGN_ENTER_MM - 10), DT)
    assert near.phase is Phase.ALIGN, "거리가 가까우면 폭이 작아도 정렬 단계여야 한다"


def test_거리가_없으면_px_임계로_돌아간다() -> None:
    """캘리브레이션 전에도 동작해야 한다 — 회귀 방지."""
    servo = ForkServo()
    assert servo.step(err(approach=INSERT_ENTER_PX + 10), DT).phase is Phase.INSERT
    servo.reset()
    assert servo.step(err(approach=ALIGN_ENTER_PX + 10), DT).phase is Phase.ALIGN
    servo.reset()
    assert servo.step(err(approach=APPROACH_PX), DT).phase is Phase.APPROACH


def test_mm_임계도_미정렬이면_중단한다() -> None:
    servo = ForkServo()
    cmd = servo.step(err(lateral=0.5, distance_mm=INSERT_ENTER_MM - 10), DT)
    assert cmd.phase is Phase.ABORT


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
