"""bbox 픽셀 크기 → 실제 치수 환산 (FR-102).

핀홀 카메라 모델:  실제크기 = 픽셀크기 × 거리 / 초점거리(px)

거리는 VL53L8CX ToF(FR-103)에서 받는다. 카메라만으로는 거리를 알 수 없으므로
ToF 값 없이는 치수를 낼 수 없다.

**한계**: 단일 시점에서는 정면에 보이는 두 변(가로·세로)만 관측된다. 깊이는
보이지 않으므로 등급 판정은 관측된 두 변으로만 한다 (size_grade 참고).
"""

from __future__ import annotations

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class PinholeCamera:
    """카메라 내부 파라미터.

    ``fx``/``fy``는 픽셀 단위 초점거리다. 캘리브레이션(체스보드) 결과를 넣는다.
    스펙시트의 초점거리(mm)와 센서 화소 피치로도 계산할 수 있다:
        fx = f_mm / pixel_pitch_mm
    """

    fx: float
    fy: float
    width: int
    height: int

    def __post_init__(self) -> None:
        if self.fx <= 0 or self.fy <= 0:
            raise ValueError(f"초점거리는 양수여야 합니다: fx={self.fx}, fy={self.fy}")

    @classmethod
    def from_fov(cls, width: int, height: int, hfov_deg: float) -> PinholeCamera:
        """수평 화각(FoV)으로부터 만든다. 캘리브레이션 전 임시로 쓴다.

        정확도가 떨어지므로 실제 운용 전에는 캘리브레이션 값으로 교체한다.
        """
        if not 0 < hfov_deg < 180:
            raise ValueError(f"화각은 0~180도 사이여야 합니다: {hfov_deg}")
        fx = (width / 2) / math.tan(math.radians(hfov_deg / 2))
        return cls(fx=fx, fy=fx, width=width, height=height)


@dataclass(frozen=True)
class ObservedFace:
    """관측된 박스 정면의 실제 치수 (cm). 깊이는 관측되지 않는다."""

    width_cm: float
    height_cm: float


def measure_face(
    bbox: tuple[float, float, float, float],
    distance_cm: float,
    camera: PinholeCamera,
) -> ObservedFace:
    """bbox(px, COCO x/y/w/h)와 거리로 정면 실치수를 구한다.

    거리는 카메라에서 박스 **정면까지**의 수직 거리다. ToF가 비스듬히 재면
    그만큼 과대추정되므로, 정면 정렬 상태에서 측정해야 한다.
    """
    _, _, w_px, h_px = bbox
    if w_px <= 0 or h_px <= 0:
        raise ValueError(f"bbox 크기가 0 이하입니다: {bbox}")
    if distance_cm <= 0:
        raise ValueError(f"거리는 양수여야 합니다: {distance_cm}")

    return ObservedFace(
        width_cm=w_px * distance_cm / camera.fx,
        height_cm=h_px * distance_cm / camera.fy,
    )
