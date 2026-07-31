"""스테이션 파이프라인 테스트 (FR-101-5). 카메라·센서·ONNX 불필요."""

from __future__ import annotations

import pytest

import math

from perception.load_balance import BBox, Detection
from perception.tfnova import Measurement
from station import measure, tilt
from station.config import StationConfig
from station.pipeline import build_payload, hull

CFG = StationConfig()


# --- 치수 공식 (지웅 문서의 검증 예시 그대로) ---

def test_높이_공식은_문서_예시와_일치한다() -> None:
    """FR-103-3 문서: 600px @ 100cm, fy 2039.07 → ≈29.43cm."""
    assert measure.height_cm(600, 100, 2039.07) == pytest.approx(29.43, abs=0.01)


def test_가로_공식은_fx를_쓴다() -> None:
    assert measure.width_cm(600, 100, 2025.17) == pytest.approx(29.63, abs=0.01)


# --- 카메라 롤 보정 (station.tilt) ---

def test_deskew는_기울지_않으면_그대로_돌려준다() -> None:
    assert tilt.deskew_size(500, 300, 0.0) == (500, 300)


def test_deskew는_기울어_부풀려진_bbox를_원래대로_되돌린다() -> None:
    """AABB_w = w·cosθ + h·sinθ, AABB_h = w·sinθ + h·cosθ 의 역산."""
    w, h, theta = 500.0, 300.0, math.radians(2.0)
    aabb_w = w * math.cos(theta) + h * math.sin(theta)
    aabb_h = w * math.sin(theta) + h * math.cos(theta)
    got_w, got_h = tilt.deskew_size(aabb_w, aabb_h, 2.0)
    assert got_w == pytest.approx(w, abs=0.5)
    assert got_h == pytest.approx(h, abs=0.5)


def test_deskew는_부호와_무관하다() -> None:
    """롤이 왼쪽이든 오른쪽이든 bbox가 커지는 정도는 같다."""
    assert tilt.deskew_size(510, 320, 2.0) == tilt.deskew_size(510, 320, -2.0)


def test_deskew는_45도_근처에서_역산을_포기한다() -> None:
    """cos(2θ)→0이라 해가 발산한다. 입력을 그대로 돌려주는 게 안전하다."""
    assert tilt.deskew_size(500, 300, 44.9) == (500, 300)


def test_보정각이_없으면_치수는_보정_전과_같다() -> None:
    detections = [
        Detection("box", BBox(100, 100, 500, 300), 0.9),
        Detection("pallet", BBox(50, 400, 900, 200), 0.9),
    ]
    distance = Measurement(distance_cm=180.0, std_cm=0.1, frames_used=50, frames_seen=50)
    plain = build_payload(detections, distance, CFG)
    assert plain["dimensions"]["tilt_deg"] is None

    tilted = build_payload(detections, distance, CFG, tilt_deg=2.0)
    # 같은 bbox라도 기울었다고 보면 실제 물체는 더 작다
    assert tilted["dimensions"]["width_cm"] < plain["dimensions"]["width_cm"]
    assert tilted["dimensions"]["tilt_deg"] == 2.0


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
    assert p["schema_version"] == "1.1"
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
