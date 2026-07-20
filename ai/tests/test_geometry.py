"""화물 치수 측정·미니어처 환산 테스트 (FR-102)."""

from __future__ import annotations

import pytest

from perception.geometry import PinholeCamera, measure_box

CAM = PinholeCamera(fx=1000, fy=1000, width=1280, height=720)


# --- 핀홀 환산 ---

def test_같은_bbox라도_거리에_따라_크기가_다르다() -> None:
    """거리를 모르면 크기를 알 수 없다는 것이 이 모듈의 존재 이유다."""
    near = measure_box((0, 0, 200, 100), 50, CAM)
    far = measure_box((0, 0, 200, 100), 200, CAM)

    assert near.width.value_cm == pytest.approx(10.0)
    assert far.width.value_cm == pytest.approx(40.0)


def test_가로와_세로를_각각_환산한다() -> None:
    m = measure_box((0, 0, 340, 250), 100, CAM)

    assert m.width.value_cm == pytest.approx(34.0)
    assert m.height.value_cm == pytest.approx(25.0)


def test_화각으로_초점거리를_만든다() -> None:
    cam = PinholeCamera.from_fov(width=1280, height=720, hfov_deg=90)
    assert cam.fx == pytest.approx(640.0)   # (w/2) / tan(45)


# --- 오차 전파 ---

def test_거리_오차는_비율_그대로_크기_오차가_된다() -> None:
    """TF-Nova ±3cm를 50cm에서 재면 6%, 100cm에서 재면 3%."""
    near = measure_box((0, 0, 200, 100), 50, CAM, edge_error_px=0)
    far = measure_box((0, 0, 200, 100), 100, CAM, edge_error_px=0)

    assert near.width.relative_error == pytest.approx(0.06, abs=0.001)
    assert far.width.relative_error == pytest.approx(0.03, abs=0.001)


def test_bbox_경계_오차는_거리가_멀수록_커진다() -> None:
    near = measure_box((0, 0, 200, 100), 50, CAM, distance_error_cm=0)
    far = measure_box((0, 0, 200, 100), 200, CAM, distance_error_cm=0)

    assert far.width.error_cm > near.width.error_cm


def test_두_오차원을_제곱합으로_합친다() -> None:
    only_distance = measure_box((0, 0, 200, 100), 100, CAM, edge_error_px=0)
    only_edges = measure_box((0, 0, 200, 100), 100, CAM, distance_error_cm=0)
    both = measure_box((0, 0, 200, 100), 100, CAM)

    expected = (only_distance.width.error_cm**2 + only_edges.width.error_cm**2) ** 0.5
    assert both.width.error_cm == pytest.approx(expected)


def test_실제_예상_오차는_수_퍼센트다() -> None:
    """34cm 박스를 60cm에서 촬영하는 상황 — 미니어처로 mm 단위 오차."""
    m = measure_box((0, 0, 34 * CAM.fx / 60, 25 * CAM.fy / 60), 60, CAM)

    assert m.width.value_cm == pytest.approx(34.0)
    assert 0.03 < m.width.relative_error < 0.07
    assert m.to_miniature(10).width.error_cm < 0.3      # 3mm 미만


# --- 미니어처 환산 ---

def test_축척비로_나눈다() -> None:
    real = measure_box((0, 0, 340, 250), 100, CAM)
    mini = real.to_miniature(10)

    assert mini.width.value_cm == pytest.approx(3.4)
    assert mini.height.value_cm == pytest.approx(2.5)


def test_불확실성도_함께_줄어든다() -> None:
    """치수만 넘기면 배치 쪽에서 마진을 추측하게 된다."""
    real = measure_box((0, 0, 340, 250), 100, CAM)
    mini = real.to_miniature(10)

    assert mini.width.error_cm == pytest.approx(real.width.error_cm / 10)
    assert mini.width.relative_error == pytest.approx(real.width.relative_error)


def test_잘못된_축척비는_거부한다() -> None:
    m = measure_box((0, 0, 340, 250), 100, CAM)

    with pytest.raises(ValueError, match="축척비"):
        m.to_miniature(0)


# --- 입력 검증 ---

@pytest.mark.parametrize(
    ("bbox", "distance", "pattern"),
    [
        ((0, 0, 0, 100), 50, "bbox"),
        ((0, 0, 200, -1), 50, "bbox"),
        ((0, 0, 200, 100), 0, "거리"),
        ((0, 0, 200, 100), -5, "거리"),
    ],
)
def test_비정상_입력은_거부한다(bbox, distance, pattern) -> None:
    with pytest.raises(ValueError, match=pattern):
        measure_box(bbox, distance, CAM)


def test_초점거리가_0이면_거부한다() -> None:
    with pytest.raises(ValueError, match="초점거리"):
        PinholeCamera(fx=0, fy=1000, width=1280, height=720)
