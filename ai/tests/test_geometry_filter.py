"""hole 기하 필터 테스트 (S15P11A304-68).

라벨 가이드 §4의 sanity check를 런타임에 적용한 것 — 창밖 건물을 hole로 오탐한
2026-07-31 젯슨 라이브가 계기다. **진짜 구멍을 버리지 않는 것**이 이 테스트의 핵심이다
(G1 재현율 99.5%를 필터가 깎으면 안 된다).
"""

from __future__ import annotations

from perception.load_balance import BBox, Detection
from perception.trt_detector import filter_by_geometry, hole_inside_pallet


def det(label: str, x, y, w, h, score=0.9) -> Detection:
    return Detection(label=label, box=BBox(x=x, y=y, w=w, h=h), score=score)


PALLET = det("pallet", 300, 400, 700, 120)
HOLE_L = det("hole", 420, 440, 90, 25)     # 파렛트 안
HOLE_R = det("hole", 700, 440, 90, 25)     # 파렛트 안


def test_파렛트_안의_구멍은_남는다() -> None:
    out = filter_by_geometry([PALLET, HOLE_L, HOLE_R])
    assert len(out) == 3
    assert sum(1 for d in out if d.label == "hole") == 2


def test_파렛트가_없으면_hole을_전부_버린다() -> None:
    """창밖 건물 오탐 케이스 — 구멍은 파렛트의 일부다."""
    window = det("hole", 950, 210, 110, 45, score=0.70)
    assert filter_by_geometry([window]) == []


def test_파렛트_밖의_구멍은_버린다() -> None:
    outside = det("hole", 50, 100, 90, 25)
    out = filter_by_geometry([PALLET, HOLE_L, outside])
    labels = [(d.label, d.box.x) for d in out]
    assert ("hole", 50) not in labels
    assert ("hole", 420) in labels


def test_파렛트는_필터가_건드리지_않는다() -> None:
    """파렛트 오탐은 이 규칙으로 못 거른다 — 손대지 않는다."""
    lone = det("pallet", 10, 20, 50, 30)
    out = filter_by_geometry([lone])
    assert out == [lone]


def test_경계에_살짝_걸친_구멍은_slack으로_살린다() -> None:
    """사람이 그은 bbox도 몇 px 흔들린다 — 딱 잘라내면 진짜 구멍을 잃는다."""
    edge = det("hole", 295, 440, 90, 25)     # 파렛트 x=300보다 5px 왼쪽
    out = filter_by_geometry([PALLET, edge])
    assert len(out) == 2


def test_파렛트가_둘이면_어느_쪽에든_속하면_된다() -> None:
    p2 = det("pallet", 1200, 400, 400, 120)
    h2 = det("hole", 1250, 440, 90, 25)
    out = filter_by_geometry([PALLET, p2, HOLE_L, h2])
    assert sum(1 for d in out if d.label == "hole") == 2


def test_포함_판정_자체() -> None:
    assert hole_inside_pallet(HOLE_L.box, PALLET.box)
    assert not hole_inside_pallet(BBox(x=50, y=100, w=90, h=25), PALLET.box)
