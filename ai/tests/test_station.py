"""스테이션 파이프라인 테스트 (FR-101-5). 카메라·센서·ONNX 불필요."""

from __future__ import annotations

import pytest

from perception.load_balance import BBox, Detection
from perception.tfnova import Measurement
from station import measure
from station.config import StationConfig
from station.pipeline import build_payload, hull

CFG = StationConfig()


# --- 치수 공식 (지웅 문서의 검증 예시 그대로) ---

def test_높이_공식은_문서_예시와_일치한다() -> None:
    """FR-103-3 문서: 600px @ 100cm, fy 2039.07 → ≈29.43cm."""
    assert measure.height_cm(600, 100, 2039.07) == pytest.approx(29.43, abs=0.01)


def test_가로_공식은_fx를_쓴다() -> None:
    assert measure.width_cm(600, 100, 2025.17) == pytest.approx(29.63, abs=0.01)


# --- hull ---

def test_hull은_박스들을_감싼다() -> None:
    h = hull([BBox(10, 20, 30, 40), BBox(50, 10, 20, 20)])
    assert (h.x, h.y, h.w, h.h) == (10, 10, 60, 50)


# --- 페이로드 ---

def _dets(box_x: float = 860) -> list[Detection]:
    """파렛트 중심 x=960, 폭 400px 박스 하나."""
    return [
        Detection("pallet", BBox(660, 700, 600, 200), 0.99),
        Detection("box", BBox(box_x, 300, 400, 400), 0.95),
    ]


DIST = Measurement(distance_cm=150.0, std_cm=0.3, frames_used=40, frames_seen=41)


def test_정상_측정_페이로드() -> None:
    p = build_payload(_dets(760), DIST, CFG)   # 박스 중심 960 = 파렛트 중심

    assert p["status"] == "ok"
    assert p["schema_version"] == "1.0"
    # 높이 = 400px × 150cm / 2039.07 ≈ 29.4
    assert p["dimensions"]["height_cm"] == pytest.approx(29.4, abs=0.1)
    assert p["dimensions"]["width_cm"] == pytest.approx(29.6, abs=0.1)
    assert p["dimensions"]["depth_cm"] is None            # 항상 null (규격)
    assert p["load_balance"]["eccentric"] is False
    assert p["load_balance"]["ratio_y"] is None           # 정면 뷰 — y축 판정 없음
    assert p["detection"]["box_count"] == 1


def test_오른쪽_치우침은_right_코드를_낸다() -> None:
    # 박스 중심 x = 860+200 = 1060, 파렛트 중심 960 → offset 100 / 반폭 200 = 0.5
    p = build_payload(_dets(860), DIST, CFG)

    lb = p["load_balance"]
    assert lb["ratio_x"] == pytest.approx(0.5)
    assert lb["eccentric"] is True
    assert lb["direction"] == ["right"]
    assert "오른쪽" in lb["message"]


def test_박스_없으면_no_detection() -> None:
    p = build_payload([Detection("pallet", BBox(0, 0, 100, 100), 0.9)], DIST, CFG)

    assert p["status"] == "no_detection"
    assert p["dimensions"] is None and p["load_balance"] is None


def test_파렛트_없으면_치수만_낸다() -> None:
    """3D 프린트 파렛트 전 임시 검증 — 박스 치수는 나오고 편하중은 null."""
    p = build_payload([Detection("box", BBox(760, 300, 400, 400), 0.95)], DIST, CFG)

    assert p["status"] == "dimensions_only"
    assert p["dimensions"]["height_cm"] == pytest.approx(29.4, abs=0.1)
    assert p["load_balance"] is None
    assert p["detection"]["pallet"] is None


def test_거리_없으면_unreliable() -> None:
    p = build_payload(_dets(), None, CFG)

    assert p["status"] == "unreliable"
    assert p["distance"] is None and p["dimensions"] is None


def test_다중_박스는_외곽으로_잰다() -> None:
    """박스 2개 — 치수는 hull(전체 영역, FR-103-2b) 기준, 편하중은
    assess_load(중심 단순 평균, 2026-07-22 결정) 기준."""
    dets = [
        Detection("pallet", BBox(660, 700, 600, 200), 0.99),
        Detection("box", BBox(700, 300, 200, 400), 0.9),
        Detection("box", BBox(1000, 350, 200, 350), 0.9),
    ]
    p = build_payload(dets, DIST, CFG)

    assert p["status"] == "ok"
    assert p["detection"]["box_count"] == 2
    # hull: x 700~1200 → 폭 500px → 500×150/2025.17 ≈ 37.0
    assert p["dimensions"]["width_cm"] == pytest.approx(37.0, abs=0.1)
