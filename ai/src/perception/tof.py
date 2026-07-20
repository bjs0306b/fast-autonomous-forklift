"""VL53L8CX 멀티존 ToF 처리 (FR-102 / FR-103 공용).

VL53L8CX는 단순 거리계가 아니라 **8×8 = 64존**을 동시에 재는 저해상도 뎁스
센서다. 이 성질을 쓰면 거리뿐 아니라 **면이 얼마나 기울어져 있는지**까지 알 수
있고, 그게 크기 판정의 두 가지 난점을 동시에 푼다:

1. **기울기 왜곡** — 정면에서 θ만큼 틀어진 면은 ``cos θ`` 만큼 작게 보인다.
   30° 틀어지면 13% 축소인데, 등급 구분 여유가 26.5%라 그 이상은 오판이 된다.
   존들의 거리에 평면을 맞추면 법선이 나오고, 거기서 θ를 구해 보정할 수 있다.

2. **엉뚱한 대상 측정** — 평면 맞춤의 잔차가 크면 평평한 박스 면이 아니라 여러
   물체나 빈 공간을 보고 있다는 뜻이다. 이때는 판정을 포기해야 한다.

**좌우 기울기(yaw)와 상하 기울기(pitch)를 따로 구한다.** 좌우로 틀어지면 가로가,
상하로 틀어지면 세로가 줄어들기 때문에 하나로 뭉뚱그리면 보정이 틀린다.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

# VL53L8CX 기본값
DEFAULT_ZONES = 8
DEFAULT_FOV_DEG = 45.0


@dataclass(frozen=True)
class SurfaceEstimate:
    """관측한 면의 상태."""

    distance_cm: float   # 광축 방향 수직 거리 (핀홀 공식에 그대로 쓴다)
    yaw_deg: float       # 좌우 기울기 (+면 오른쪽이 멀다) → 가로 축소
    pitch_deg: float     # 상하 기울기 (+면 아래가 멀다) → 세로 축소
    flatness_cm: float   # 평면 잔차 RMS. 작을수록 평평한 면
    valid_zones: int

    @property
    def width_scale(self) -> float:
        """관측된 가로를 실제 가로로 되돌리는 배율."""
        return 1.0 / math.cos(math.radians(self.yaw_deg))

    @property
    def height_scale(self) -> float:
        return 1.0 / math.cos(math.radians(self.pitch_deg))


class SurfaceUnreliable(Exception):
    """면을 신뢰할 수 없어 치수를 낼 수 없다."""


def zone_directions(zones: int = DEFAULT_ZONES, fov_deg: float = DEFAULT_FOV_DEG) -> np.ndarray:
    """각 존이 바라보는 방향의 단위 벡터. 반환 shape은 (zones, zones, 3).

    좌표계는 카메라 관례를 따른다: +x 오른쪽, +y 아래, +z 정면.
    """
    # 존 중심의 각도. 8존이 FoV를 균등 분할한다고 본다.
    offsets = (np.arange(zones) + 0.5) / zones - 0.5      # -0.5 ~ +0.5
    angles = np.radians(offsets * fov_deg)

    tan_x = np.tan(angles)[np.newaxis, :]                  # 열 방향 = 좌우
    tan_y = np.tan(angles)[:, np.newaxis]                  # 행 방향 = 상하

    x = np.broadcast_to(tan_x, (zones, zones))
    y = np.broadcast_to(tan_y, (zones, zones))
    z = np.ones((zones, zones))

    directions = np.stack([x, y, z], axis=-1)
    return directions / np.linalg.norm(directions, axis=-1, keepdims=True)


def estimate_surface(
    distances_cm: np.ndarray,
    fov_deg: float = DEFAULT_FOV_DEG,
    min_valid_zones: int = 6,
    max_flatness_cm: float = 3.0,
    max_tilt_deg: float = 45.0,
) -> SurfaceEstimate:
    """존별 거리에서 면의 거리·기울기를 추정한다.

    거리가 0 이하이거나 NaN인 존은 측정 실패로 보고 제외한다.
    유효 존이 부족하거나 평면이 잘 맞지 않으면 ``SurfaceUnreliable``.
    """
    d = np.asarray(distances_cm, dtype=float)
    if d.ndim != 2 or d.shape[0] != d.shape[1]:
        raise ValueError(f"정사각 존 배열이어야 합니다: shape={d.shape}")

    valid = np.isfinite(d) & (d > 0)
    n_valid = int(valid.sum())
    if n_valid < min_valid_zones:
        raise SurfaceUnreliable(
            f"유효 존이 {n_valid}개뿐입니다 (최소 {min_valid_zones}). "
            "측정 실패이거나 대상이 시야에 없습니다."
        )

    points = zone_directions(d.shape[0], fov_deg) * d[..., np.newaxis]
    x, y, z = (points[..., i][valid] for i in range(3))

    a, b, c = _fit_plane(x, y, z)

    # 평면 z = ax + by + c 의 법선은 (a, b, -1). 정면이면 (0, 0, -1)로 카메라를 본다.
    yaw = math.degrees(math.atan(a))
    pitch = math.degrees(math.atan(b))
    if abs(yaw) > max_tilt_deg or abs(pitch) > max_tilt_deg:
        raise SurfaceUnreliable(
            f"면이 너무 기울어져 있습니다 (좌우 {yaw:.0f}도, 상하 {pitch:.0f}도, "
            f"허용 {max_tilt_deg:.0f}도). 보정 배율이 커져 신뢰할 수 없습니다."
        )

    # 점-평면 거리의 RMS. 평평한 면이면 작다.
    residual = (a * x + b * y - z + c) / math.sqrt(a * a + b * b + 1.0)
    flatness = float(np.sqrt((residual**2).sum() / n_valid))
    if flatness > max_flatness_cm:
        raise SurfaceUnreliable(
            f"평면 잔차가 {flatness:.1f}cm입니다 (허용 {max_flatness_cm}cm). "
            "평평한 박스 면이 아니라 여러 물체나 빈 공간을 보고 있을 수 있습니다."
        )

    # 광축(x=y=0)에서의 평면 높이 c가 곧 수직 거리다.
    # ToF 존 값은 광선 방향 거리라서 가장자리일수록 멀게 나온다. 그 중앙값을 쓰면
    # 정면 평면인데도 5%쯤 과대추정된다. 핀홀 공식은 수직 거리를 요구하므로 c를 쓴다.
    return SurfaceEstimate(
        distance_cm=c,
        yaw_deg=yaw,
        pitch_deg=pitch,
        flatness_cm=flatness,
        valid_zones=n_valid,
    )


def _fit_plane(x: np.ndarray, y: np.ndarray, z: np.ndarray) -> tuple[float, float, float]:
    """최소제곱으로 z = ax + by + c 를 맞춘다.

    3×3 정규방정식을 크라메르 공식으로 직접 푼다. BLAS/LAPACK을 쓰지 않으므로
    numpy 백엔드 상태나 Jetson의 BLAS 빌드에 영향을 받지 않는다. 점이 64개뿐이라
    성능상으로도 이쪽이 낫다.
    """
    n = float(x.size)
    sx, sy, sz = float(x.sum()), float(y.sum()), float(z.sum())
    sxx, syy = float((x * x).sum()), float((y * y).sum())
    sxy = float((x * y).sum())
    sxz, syz = float((x * z).sum()), float((y * z).sum())

    m = ((sxx, sxy, sx), (sxy, syy, sy), (sx, sy, n))
    rhs = (sxz, syz, sz)

    det = _det3(m)
    if abs(det) < 1e-9:
        raise SurfaceUnreliable(
            "존들이 한 직선 위에 있어 평면을 결정할 수 없습니다."
        )

    return tuple(_det3(_replace_column(m, i, rhs)) / det for i in range(3))


def _det3(m) -> float:
    (a, b, c), (d, e, f), (g, h, i) = m
    return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)


def _replace_column(m, index: int, column) -> tuple:
    return tuple(
        tuple(column[r] if c == index else m[r][c] for c in range(3)) for r in range(3)
    )
