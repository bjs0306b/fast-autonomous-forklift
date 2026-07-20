"""멀티존 ToF 평면 추정·기울기 보정 테스트 (FR-102)."""

from __future__ import annotations

import math

import numpy as np
import pytest

from perception.geometry import PinholeCamera, measure_face
from perception.size_grade import CargoGrade, classify
from perception.tof import (
    SurfaceUnreliable,
    estimate_surface,
    zone_directions,
)


def synth_plane(distance_cm: float, yaw_deg: float = 0.0, pitch_deg: float = 0.0,
                zones: int = 8, fov_deg: float = 45.0) -> np.ndarray:
    """주어진 거리·기울기의 평면을 보고 있을 때 각 존이 잴 거리를 만든다.

    평면은 광축 위 ``distance_cm`` 지점을 지나고 법선이 ``n``이다.
    원점에서 방향 u로 나간 광선이 평면과 만나는 거리는 ``(p0·n) / (u·n)``.
    """
    n = np.array([
        math.sin(math.radians(yaw_deg)),
        math.sin(math.radians(pitch_deg)),
        -math.cos(math.radians(yaw_deg)) * math.cos(math.radians(pitch_deg)),
    ])
    n /= np.linalg.norm(n)

    p0 = np.array([0.0, 0.0, distance_cm])       # 광축이 평면과 만나는 점
    dirs = zone_directions(zones, fov_deg)
    return (p0 @ n) / (dirs @ n)


# --- 평면 추정 ---

def test_정면_평면은_기울기가_0이다() -> None:
    surface = estimate_surface(synth_plane(100.0))

    assert surface.yaw_deg == pytest.approx(0.0, abs=0.5)
    assert surface.pitch_deg == pytest.approx(0.0, abs=0.5)
    assert surface.distance_cm == pytest.approx(100.0, rel=0.001)
    assert surface.flatness_cm < 0.1
    assert surface.valid_zones == 64


def test_거리는_광선거리가_아니라_수직거리다() -> None:
    """ToF 존은 광선 방향 거리를 잰다. 정면 평면이라도 가장자리 존은 더 멀다.

    핀홀 공식은 광축 방향 수직 거리를 요구하므로, 존 값의 중앙값을 그대로 쓰면
    5%쯤 과대추정된다. 평면 맞춤으로 수직 거리를 뽑아야 한다.
    """
    d = synth_plane(100.0)

    assert float(np.median(d)) > 104.0        # 광선거리 중앙값은 부풀려져 있다
    assert estimate_surface(d).distance_cm == pytest.approx(100.0, rel=0.001)


@pytest.mark.parametrize("yaw", [-30.0, -15.0, 15.0, 30.0])
def test_좌우_기울기를_복원한다(yaw: float) -> None:
    surface = estimate_surface(synth_plane(100.0, yaw_deg=yaw))

    assert surface.yaw_deg == pytest.approx(yaw, abs=1.0)
    assert surface.pitch_deg == pytest.approx(0.0, abs=1.0)


@pytest.mark.parametrize("pitch", [-20.0, 20.0])
def test_상하_기울기를_복원한다(pitch: float) -> None:
    surface = estimate_surface(synth_plane(100.0, pitch_deg=pitch))

    assert surface.pitch_deg == pytest.approx(pitch, abs=1.0)
    assert surface.yaw_deg == pytest.approx(0.0, abs=1.0)


def test_좌우와_상하_기울기를_분리한다() -> None:
    """하나로 뭉뚱그리면 가로/세로 보정이 서로 잘못 적용된다."""
    surface = estimate_surface(synth_plane(100.0, yaw_deg=25.0, pitch_deg=-10.0))

    assert surface.yaw_deg == pytest.approx(25.0, abs=1.5)
    assert surface.pitch_deg == pytest.approx(-10.0, abs=1.5)


# --- 신뢰도 ---

def test_평평하지_않으면_거부한다() -> None:
    """여러 물체나 빈 공간을 보고 있을 때 억지로 치수를 내면 안 된다."""
    rng = np.random.default_rng(0)
    noisy = 100.0 + rng.normal(0, 20, size=(8, 8))

    with pytest.raises(SurfaceUnreliable, match="평면 잔차"):
        estimate_surface(noisy)


def test_유효_존이_모자라면_거부한다() -> None:
    d = np.full((8, 8), np.nan)
    d[0, 0] = 100.0
    d[0, 1] = 101.0

    with pytest.raises(SurfaceUnreliable, match="유효 존"):
        estimate_surface(d)


def test_측정_실패_존은_제외하고_계산한다() -> None:
    d = synth_plane(100.0)
    d[0, :] = 0.0        # 한 줄이 측정 실패
    d[7, 7] = np.nan

    surface = estimate_surface(d)

    assert surface.valid_zones == 64 - 8 - 1
    assert surface.yaw_deg == pytest.approx(0.0, abs=1.0)


def test_정사각형이_아니면_거부한다() -> None:
    with pytest.raises(ValueError, match="정사각"):
        estimate_surface(np.ones((4, 8)))


# --- 보정이 실제로 등급 판정을 구한다 ---

def test_기울기_보정_없이는_등급을_오판한다() -> None:
    """30도 틀어지면 13% 축소돼 구분 여유(26.5%)를 갉아먹는다."""
    cam = PinholeCamera(fx=1000, fy=1000, width=1280, height=720)
    grades = [
        CargoGrade("S", "소형", (27, 18, 15), "mini_s"),
        CargoGrade("M", "중형", (35, 25, 10), "mini_m"),
        CargoGrade("L", "대형", (48, 38, 34), "mini_l"),
    ]

    # 실제로는 35x25 면(M)인데 좌우로 40도 틀어져 가로가 줄어 보인다
    distance = 100.0
    true_w_px = 35 * cam.fx / distance
    seen_w_px = true_w_px * math.cos(math.radians(40))
    h_px = 25 * cam.fy / distance
    bbox = (0.0, 0.0, seen_w_px, h_px)

    raw = classify(measure_face(bbox, distance, cam), grades, tolerance=0.2)

    surface = estimate_surface(synth_plane(distance, yaw_deg=40.0))
    fixed = classify(measure_face(bbox, distance, cam, surface), grades, tolerance=0.2)

    assert raw.grade is None or raw.grade.name != "M", "보정 없이도 맞으면 이 테스트가 무의미하다"
    assert fixed.grade is not None and fixed.grade.name == "M"
    assert fixed.error < raw.error


def test_보정_여부가_결과에_표시된다() -> None:
    cam = PinholeCamera(fx=1000, fy=1000, width=1280, height=720)
    surface = estimate_surface(synth_plane(100.0, yaw_deg=20.0))

    plain = measure_face((0, 0, 200, 100), 100.0, cam)
    corrected = measure_face((0, 0, 200, 100), 100.0, cam, surface)

    assert plain.tilt_corrected is False
    assert corrected.tilt_corrected is True
    assert corrected.width_cm > plain.width_cm      # 축소분을 되돌리므로 커진다
    assert corrected.height_cm == pytest.approx(plain.height_cm, rel=0.01)
