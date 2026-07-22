"""편하중 판정 테스트 (FR-104). 감지 모델·하드웨어 불필요."""

from __future__ import annotations

import pytest

from perception.load_balance import (
    BBox,
    Detection,
    NoTargets,
    assess,
    assess_detections,
    assess_load,
    select_load,
    select_targets,
)


# 파렛트: 중심 (100, 100), 폭·높이 80 (좌상단 60,60).
PALLET = BBox(x=60, y=60, w=80, h=80)


def centered_box(w: float = 40, h: float = 40) -> BBox:
    """파렛트 중심(100,100)에 정확히 놓인 박스."""
    return BBox(x=100 - w / 2, y=100 - h / 2, w=w, h=h)


def test_중앙_정렬은_편하중이_아니다() -> None:
    r = assess(centered_box(), PALLET)

    assert r.ratio_x == pytest.approx(0)
    assert r.ratio_y == pytest.approx(0)
    assert not r.eccentric
    assert r.direction == ""


def test_오른쪽_치우침을_비율로_잡는다() -> None:
    # 폭 40 박스를 오른쪽으로 8px 이동 → 8 / (40/2) = 0.4.
    box = BBox(x=100 - 20 + 8, y=80, w=40, h=40)
    r = assess(box, PALLET)

    assert r.ratio_x == pytest.approx(0.4)
    assert r.ratio_y == pytest.approx(0)
    assert r.eccentric
    assert r.direction == "오른쪽"


def test_왼쪽_위_대각_치우침() -> None:
    # 중심을 x-왼쪽, y-위로 옮긴다.
    box = BBox(x=100 - 20 - 8, y=100 - 20 - 8, w=40, h=40)
    r = assess(box, PALLET)

    assert r.ratio_x == pytest.approx(-0.4)
    assert r.ratio_y == pytest.approx(-0.4)
    assert r.direction == "왼쪽·위"


def test_임계값_경계는_초과라야_편하중() -> None:
    # 정확히 임계값이면 편하중 아님 (> 비교). 8px = 0.4 이므로 임계 0.4로 맞춘다.
    box = BBox(x=100 - 20 + 8, y=80, w=40, h=40)

    assert not assess(box, PALLET, threshold=0.4).eccentric
    assert assess(box, PALLET, threshold=0.39).eccentric


def test_비율은_스케일에_불변이다() -> None:
    """픽셀→cm 캘리브레이션 없이 동작함을 확인. 좌표를 3배 해도 비율 동일."""
    box = BBox(x=100 - 20 + 8, y=80, w=40, h=40)
    r1 = assess(box, PALLET)

    scale = 3
    box3 = BBox(x=box.x * scale, y=box.y * scale, w=box.w * scale, h=box.h * scale)
    pallet3 = BBox(x=PALLET.x * scale, y=PALLET.y * scale,
                   w=PALLET.w * scale, h=PALLET.h * scale)
    r2 = assess(box3, pallet3)

    assert r2.ratio_x == pytest.approx(r1.ratio_x)
    assert r2.ratio_y == pytest.approx(r1.ratio_y)


def test_퇴화_bbox는_거부한다() -> None:
    with pytest.raises(ValueError):
        assess(BBox(x=0, y=0, w=0, h=40), PALLET)


# --- 경고 페이로드 (FR-104 출력, 표면 독립) ---

def test_to_dict는_구조화_경고를_낸다() -> None:
    box = BBox(x=100 - 20 + 8, y=80, w=40, h=40)
    d = assess(box, PALLET).to_dict()

    assert d["eccentric"] is True
    assert d["direction"] == "오른쪽"
    assert d["ratio_x"] == pytest.approx(0.4)
    assert "오른쪽" in d["message"]


# --- 감지 어댑터 (모델 출력 → 편하중) ---

def test_감지목록에서_박스와_파렛트를_판정한다() -> None:
    box = BBox(x=100 - 20 + 8, y=80, w=40, h=40)  # 오른쪽 0.4 치우침
    dets = [
        Detection("pallet", PALLET, 0.95),
        Detection("box", box, 0.9),
    ]
    r = assess_detections(dets)

    assert r.eccentric
    assert r.direction == "오른쪽"


def test_파렛트와_겹치는_박스를_고른다() -> None:
    """배경에 떨어진 박스는 무시하고 파렛트 위 박스를 측정한다."""
    on_pallet = centered_box()                      # 파렛트 중심에 정렬
    far_away = BBox(x=500, y=500, w=40, h=40)        # 겹침 0
    dets = [
        Detection("pallet", PALLET, 0.9),
        Detection("box", far_away, 0.99),            # 점수는 더 높지만
        Detection("box", on_pallet, 0.8),            # 겹치는 이걸 골라야 함
    ]
    box, pallet = select_targets(dets)

    assert box == on_pallet
    assert pallet == PALLET


def test_점수_미달_검출은_버린다() -> None:
    dets = [
        Detection("pallet", PALLET, 0.9),
        Detection("box", centered_box(), 0.3),       # min_score=0.5 미만
    ]
    with pytest.raises(NoTargets):
        assess_detections(dets)


def test_파렛트나_박스가_없으면_거부한다() -> None:
    with pytest.raises(NoTargets):
        assess_detections([Detection("box", centered_box(), 0.9)])
    with pytest.raises(NoTargets):
        assess_detections([Detection("pallet", PALLET, 0.9)])


# --- 다중 박스 (중심 단순 평균, 2026-07-22 결정) ---

def test_단일_박스는_assess와_동일하다() -> None:
    box = BBox(x=100 - 20 + 8, y=80, w=40, h=40)

    single = assess(box, PALLET)
    multi = assess_load([box], PALLET)

    assert multi.ratio_x == pytest.approx(single.ratio_x)
    assert multi.ratio_y == pytest.approx(single.ratio_y)


def test_대칭_배치는_정상이다() -> None:
    """파렛트 중심 기준 좌우 대칭 박스 2개 → 합산 중심이 중앙."""
    left = BBox(x=100 - 30, y=90, w=20, h=20)    # 중심 x=80
    right = BBox(x=100 + 10, y=90, w=20, h=20)   # 중심 x=120
    r = assess_load([left, right], PALLET)

    assert r.ratio_x == pytest.approx(0)
    assert not r.eccentric


def test_한쪽으로_몰린_박스들은_편하중이다() -> None:
    """둘 다 오른쪽에 몰림 → 중심 평균 x=113.75, hull=[95,130] 반폭 17.5 → 0.786."""
    a = BBox(x=95, y=90, w=20, h=20)     # 중심 x=105
    b = BBox(x=115, y=90, w=15, h=20)    # 중심 x=122.5
    r = assess_load([a, b], PALLET)

    assert r.ratio_x == pytest.approx((113.75 - 100) / 17.5)
    assert r.eccentric
    assert "오른쪽" in r.direction


def test_select_load는_겹치는_박스_전부를_고른다() -> None:
    on1 = BBox(x=70, y=90, w=20, h=20)
    on2 = BBox(x=110, y=90, w=20, h=20)
    far = BBox(x=500, y=500, w=40, h=40)         # 배경 — 제외돼야 함
    dets = [
        Detection("pallet", PALLET, 0.9),
        Detection("box", on1, 0.8),
        Detection("box", on2, 0.7),
        Detection("box", far, 0.99),
    ]
    boxes, pallet = select_load(dets)

    assert set((b.x, b.y) for b in boxes) == {(70, 90), (110, 90)}
    assert pallet == PALLET


def test_빈_박스_목록은_거부한다() -> None:
    with pytest.raises(ValueError):
        assess_load([], PALLET)
