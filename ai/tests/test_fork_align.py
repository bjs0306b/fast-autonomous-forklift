"""포크 정렬 타깃 선택 테스트 (S15P11A304-143).

수치는 실측 라벨에서 가져왔다 — `onboard_eval.json`의 4구멍 프레임
(onbeval_20260730-162334_0109~0114)이 다면 케이스의 원본이다.
"""

from __future__ import annotations

import math

from perception.fork_align import (
    AlignTarget,
    EntryFace,
    TargetTracker,
    choose_entry_face,
    eligible_targets,
)
from perception.load_balance import BBox, Detection


def det(label: str, x, y, w, h, score=0.9) -> Detection:
    return Detection(label=label, box=BBox(x=x, y=y, w=w, h=h), score=score)


def hole(cx, w, cy=520.0, h=60.0) -> Detection:
    """중심 x와 폭으로 구멍을 만든다 — 규칙이 보는 것이 그 둘뿐이다."""
    return det("hole", cx - w / 2, cy - h / 2, w, h)


PALLET = det("pallet", 400, 452, 673, 127, score=0.93)

# eval 프레임 0109 실측: 앞면(넓은 개구) 2개 + 옆면(좁은 개구) 2개
FACE_NEAR = [hole(504.4, 134.0), hole(709.1, 161.0)]
FACE_SIDE = [hole(935.0, 81.0), hole(1025.4, 54.0)]


# --- 진입면 선택 ---

def test_구멍이_둘이면_그_쌍이_진입면() -> None:
    face = choose_entry_face(FACE_NEAR)
    assert face is not None
    assert face.left.box.center_x < face.right.box.center_x
    assert math.isclose(face.span, 204.7, abs_tol=0.1)


def test_구멍이_하나면_진입면이_없다() -> None:
    assert choose_entry_face([hole(500, 120)]) is None
    assert choose_entry_face([]) is None


def test_다면_프레임에서_넓은_면을_고른다() -> None:
    """eval 0109 실측값. 앞면(134·161px)이 옆면(81·54px)을 이긴다."""
    face = choose_entry_face(FACE_NEAR + FACE_SIDE)
    assert face is not None
    assert {round(face.left.box.w), round(face.right.box.w)} == {134, 161}


def test_교차쌍은_후보가_아니다() -> None:
    """앞면 왼쪽 + 옆면 오른쪽이 선분은 가장 길다 — 인접 제한이 없으면 이게 뽑힌다.

    실측: 전체 쌍을 후보로 두면 train 40장 중 0장, eval 7장 중 0장만 맞는다.
    """
    face = choose_entry_face(FACE_NEAR + FACE_SIDE)
    assert face is not None
    widest_span = 1025.4 - 504.4
    assert face.span < widest_span


def test_최소폭이_평균폭보다_여유를_벌린다() -> None:
    """정답은 같아도 1등/2등 차이가 커야 22fps에서 면이 덜 떤다.

    이 프레임(eval 0109) 하나의 성질이 아니라 셋 전체 경향이다 — 마진 중앙값이
    train 1.62→1.90배, eval 1.09→1.50배. 45° 정각 프레임에서는 둘 다 1.0배로
    붙으므로 `scripts/eval_entry_face.py`로 전수 확인할 것.
    """
    holes = FACE_NEAR + FACE_SIDE
    ordered = sorted(holes, key=lambda d: d.box.center_x)
    pairs = list(zip(ordered, ordered[1:]))

    def ratio(width_of) -> float:
        scores = sorted(
            (abs(r.box.center_x - l.box.center_x) * width_of(l.box.w, r.box.w)
             for l, r in pairs), reverse=True)
        return scores[0] / scores[1]

    assert ratio(min) > ratio(lambda a, b: (a + b) / 2)


# --- 파렛트 선택 ---

def test_구멍_없는_파렛트는_탈락한다() -> None:
    """진입면이 없으면 정렬 대상이 아니다 — score가 높아도 마찬가지."""
    bare = det("pallet", 50, 100, 900, 300, score=0.99)
    targets = eligible_targets([bare, PALLET] + FACE_NEAR)
    assert len(targets) == 1
    assert targets[0].pallet is PALLET


def test_진입면이_큰_파렛트가_먼저다() -> None:
    """가까울수록 간격이 넓고 정면일수록 개구가 넓다 — 점수 하나가 둘을 담는다."""
    far = det("pallet", 60, 470, 200, 40)
    far_holes = [hole(100, 40, cy=490, h=18), hole(160, 38, cy=490, h=18)]
    targets = eligible_targets([far, PALLET] + FACE_NEAR + far_holes)
    assert [t.pallet for t in targets] == [PALLET, far]


def test_파렛트가_없으면_대상도_없다() -> None:
    assert eligible_targets(FACE_NEAR) == []


def test_구멍은_자기를_감싸는_작은_파렛트에_붙는다() -> None:
    """파렛트 bbox가 겹칠 때 구멍이 양쪽에 중복 배정되면 안 된다."""
    big = det("pallet", 380, 440, 720, 160)
    targets = eligible_targets([big, PALLET] + FACE_NEAR)
    assert len(targets) == 1
    assert targets[0].pallet is PALLET


# --- 오차 산출 ---

def test_정면_정렬이면_오차가_0에_가깝다() -> None:
    left, right = hole(540, 120), hole(740, 120)
    target = AlignTarget(pallet=PALLET, face=choose_entry_face([left, right]))
    err = target.error(image_width=1280)
    assert math.isclose(err.lateral_ratio, 0.0, abs_tol=1e-9)
    assert math.isclose(err.yaw_signal, 0.0, abs_tol=1e-9)
    assert math.isclose(err.approach_px, 200.0)


def test_파렛트가_오른쪽이면_좌우오차가_양수() -> None:
    target = AlignTarget(pallet=PALLET,
                         face=choose_entry_face([hole(740, 120), hole(940, 120)]))
    assert target.error(image_width=1280).lateral_ratio > 0


def test_먼_쪽_구멍이_좁아_요신호가_생긴다() -> None:
    """§3-① 근거 그대로 — 오른쪽이 좁으면 오른쪽 끝이 더 멀다(+)."""
    target = AlignTarget(pallet=PALLET,
                         face=choose_entry_face([hole(540, 140), hole(740, 100)]))
    err = target.error(image_width=1280)
    assert err.yaw_signal > 0
    assert math.isclose(err.yaw_signal, 40 / 240)


def test_포크_중심선_오프셋을_반영한다() -> None:
    """카메라를 차체 중심에서 벗어나게 달면 그 편차가 그대로 정상상태 오차가 된다."""
    face = choose_entry_face([hole(540, 120), hole(740, 120)])
    target = AlignTarget(pallet=PALLET, face=face)
    assert target.error(image_width=1280, fork_center_x=590).lateral_ratio > 0


def test_focal을_주면_절대_각도와_거리가_나온다() -> None:
    face = choose_entry_face([hole(540, 140), hole(740, 100)])
    err = AlignTarget(pallet=PALLET, face=face).error(image_width=1280, focal_px=900)
    assert err.yaw_deg is not None and err.yaw_deg > 0
    assert err.distance_mm is not None and err.distance_mm > 0


def test_focal이_없으면_절대값은_None() -> None:
    face = choose_entry_face(FACE_NEAR)
    err = AlignTarget(pallet=PALLET, face=face).error(image_width=1280)
    assert err.yaw_deg is None and err.distance_mm is None


# --- 프레임 간 락 ---

def test_같은_파렛트를_계속_쫓는다() -> None:
    tracker = TargetTracker()
    first = tracker.update([PALLET] + FACE_NEAR)
    moved = det("pallet", 410, 455, 673, 127)
    second = tracker.update([moved] + [hole(514, 134), hole(719, 161)])
    assert first is not None and second is not None
    assert second.pallet is moved


RIVAL = det("pallet", 1050, 452, 650, 127)


def test_비슷한_점수로는_타깃을_안_바꾼다() -> None:
    """45° 부근에서 1.0~1.2배로 붙는다 — 그 구간에서 갈아타면 조향이 떤다."""
    tracker = TargetTracker()
    tracker.update([PALLET] + FACE_NEAR)
    rival_holes = [hole(1230, 150), hole(1430, 160)]
    # 경쟁 후보가 실제로 자격을 갖췄는지부터 확인한다 — 구멍이 파렛트 밖으로 삐져
    # 나가 탈락하면 히스테리시스가 아니라 자격으로 통과해 테스트가 무의미해진다.
    assert len(eligible_targets([PALLET, RIVAL] + FACE_NEAR + rival_holes)) == 2
    kept = tracker.update([PALLET, RIVAL] + FACE_NEAR + rival_holes)
    assert kept is not None and kept.pallet is PALLET


def test_확실히_더_좋은_후보로는_갈아탄다() -> None:
    tracker = TargetTracker()
    tracker.update([PALLET] + FACE_NEAR)
    rival_holes = [hole(1230, 300), hole(1480, 320)]
    switched = tracker.update([PALLET, RIVAL] + FACE_NEAR + rival_holes)
    assert switched is not None and switched.pallet is RIVAL


def test_몇_프레임_놓쳐도_락을_유지한다() -> None:
    tracker = TargetTracker(grace_frames=3)
    locked = tracker.update([PALLET] + FACE_NEAR)
    for _ in range(3):
        assert tracker.update([]) is locked


def test_유예를_넘기면_락이_풀린다() -> None:
    tracker = TargetTracker(grace_frames=2)
    tracker.update([PALLET] + FACE_NEAR)
    for _ in range(2):
        tracker.update([])
    assert tracker.update([]) is None
    assert tracker.locked is None


def test_reset은_락을_즉시_푼다() -> None:
    tracker = TargetTracker()
    tracker.update([PALLET] + FACE_NEAR)
    tracker.reset()
    assert tracker.locked is None
