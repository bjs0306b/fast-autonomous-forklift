"""화물 치수 측정과 미니어처 환산 (FR-102).

검출된 박스의 bbox와 거리계 값으로 **실제 치수를 재고**, 축척비로 나눠
미니어처 치수를 얻는다.

    카메라 bbox(px) + TF-Nova 거리  →  실물 치수(cm)  →  ÷ 축척비  →  미니어처 치수

**박스 크기를 미리 알고 있으면 안 된다.** 정해둔 값과 대조하는 건 인식이 아니라
조회다. 이 모듈에는 어떤 화물 치수 상수도 들어 있지 않다. 실측 정확도를 확인할
때만 사람이 자로 잰 값과 비교하며, 그 값은 검증 문서에만 둔다.

측정에는 오차가 따르므로 **불확실성을 함께 돌려준다.** 적재 배치는 이 값을 보고
안전 마진을 잡아야 한다. 치수만 넘기면 배치 쪽에서 마진을 추측하게 된다.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

# TF-Nova 사양: 0.1~4m 구간 ±3cm
DEFAULT_DISTANCE_ERROR_CM = 3.0
# bbox 경계가 흔들리는 정도(한 변당 픽셀). 검출기 성능에 따라 조정한다.
DEFAULT_EDGE_ERROR_PX = 2.0


@dataclass(frozen=True)
class PinholeCamera:
    """카메라 내부 파라미터.

    ``fx``/``fy``는 픽셀 단위 초점거리다. 체스보드 캘리브레이션 결과를 넣는다.
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
        """수평 화각으로부터 만든다. 캘리브레이션 전 임시로만 쓴다."""
        if not 0 < hfov_deg < 180:
            raise ValueError(f"화각은 0~180도 사이여야 합니다: {hfov_deg}")
        fx = (width / 2) / math.tan(math.radians(hfov_deg / 2))
        return cls(fx=fx, fy=fx, width=width, height=height)


@dataclass(frozen=True)
class Length:
    """측정한 길이와 그 불확실성 (cm)."""

    value_cm: float
    error_cm: float

    @property
    def relative_error(self) -> float:
        return self.error_cm / self.value_cm if self.value_cm else float("inf")

    def scaled(self, factor: float) -> Length:
        return Length(self.value_cm * factor, self.error_cm * factor)

    def __str__(self) -> str:
        return f"{self.value_cm:.2f}±{self.error_cm:.2f}cm"


@dataclass(frozen=True)
class BoxMeasurement:
    """관측된 박스 정면의 치수. 깊이는 단일 시점에서 관측되지 않는다."""

    width: Length
    height: Length
    distance_cm: float

    def to_miniature(self, scale: float) -> BoxMeasurement:
        """축척비로 나눠 미니어처 치수를 낸다 (scale=10이면 1/10)."""
        if scale <= 0:
            raise ValueError(f"축척비는 양수여야 합니다: {scale}")
        return BoxMeasurement(
            width=self.width.scaled(1 / scale),
            height=self.height.scaled(1 / scale),
            distance_cm=self.distance_cm,
        )

    def __str__(self) -> str:
        return f"{self.width} x {self.height} (거리 {self.distance_cm:.0f}cm)"


def measure_box(
    bbox: tuple[float, float, float, float],
    distance_cm: float,
    camera: PinholeCamera,
    distance_error_cm: float = DEFAULT_DISTANCE_ERROR_CM,
    edge_error_px: float = DEFAULT_EDGE_ERROR_PX,
) -> BoxMeasurement:
    """bbox(px, COCO x/y/w/h)와 거리로 정면 실치수를 잰다.

    핀홀 모델:  실제크기 = 픽셀크기 × 거리 / 초점거리(px)

    거리 없이는 크기를 알 수 없다. 같은 200px bbox라도 거리가 50cm면 11cm,
    200cm면 44cm다. 그래서 거리계 값이 반드시 필요하다.

    오차는 두 갈래로 들어온다. 거리 오차는 **비율 그대로** 크기 오차가 되고
    (±3cm를 50cm에서 재면 6%), bbox 경계 오차는 거리가 멀수록 커진다.
    독립이라 보고 제곱합으로 합친다.

    **기울기는 보정하지 않는다.** 비스듬히 본 면은 cos만큼 작게 보이는데,
    단일 점 거리계로는 기울기를 알 수 없다. 박스를 카메라에 정면으로 두는
    운용으로 대응한다.
    """
    _, _, w_px, h_px = bbox
    if w_px <= 0 or h_px <= 0:
        raise ValueError(f"bbox 크기가 0 이하입니다: {bbox}")
    if distance_cm <= 0:
        raise ValueError(f"거리는 양수여야 합니다: {distance_cm}")
    if distance_error_cm < 0 or edge_error_px < 0:
        raise ValueError("오차 항은 0 이상이어야 합니다")

    return BoxMeasurement(
        width=_project(w_px, distance_cm, camera.fx, distance_error_cm, edge_error_px),
        height=_project(h_px, distance_cm, camera.fy, distance_error_cm, edge_error_px),
        distance_cm=distance_cm,
    )


def _project(
    size_px: float,
    distance_cm: float,
    focal_px: float,
    distance_error_cm: float,
    edge_error_px: float,
) -> Length:
    size_cm = size_px * distance_cm / focal_px

    from_distance = size_cm * distance_error_cm / distance_cm
    from_edges = edge_error_px * distance_cm / focal_px

    return Length(
        value_cm=size_cm,
        error_cm=math.hypot(from_distance, from_edges),
    )
