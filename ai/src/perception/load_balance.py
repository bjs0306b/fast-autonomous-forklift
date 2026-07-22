"""편하중(무게중심 치우침) 추정 (FR-104).

측정 스테이션에는 파렛트 위에 박스 하나가 올라온 상태로 들어온다. 카메라가 준
박스·파렛트 bbox의 중심을 비교해 화물이 한쪽으로 치우쳤는지(편하중) 판단한다.

무게를 직접 잴 수단이 없으므로(로드셀 미탑재) **밀도 균일을 가정**하고 박스 bbox의
기하 중심을 무게중심으로 본다. 박스가 여러 개면 **중심들의 단순 평균**을 쓴다
(2026-07-22 결정 — bbox는 정면 투영이라 깊이가 없어 부피 가중이 어차피 반쪽이고,
데모 스케일에선 단순 평균이 가정도 적다. FR-103 용적 의존 없음).

**치우침은 절대 거리(cm)가 아니라 박스 크기 대비 비율로 판정한다.** 그래야:

- 픽셀 좌표만으로 계산된다 → 픽셀→cm 캘리브레이션(FR-103-2)이 없어도 동작한다.
  offset도 픽셀, 박스 크기도 픽셀이라 나누면 스케일 상수가 약분돼 사라진다.
- 박스 크기와 무관하게 같은 임계값을 쓸 수 있다.

    치우침 비율 = (박스 중심 - 파렛트 중심) / (박스 한 변의 절반)

비율 1.0은 박스가 자기 반폭만큼 밀려난 상태다. 임계값(기본 0.3)을 넘으면 편하중으로
본다. (대안: 파렛트 여유 ``(파렛트폭-박스폭)/2``로 정규화하면 "박스 모서리가 파렛트
끝에 닿기까지 얼마나 남았나"가 되지만, 박스와 파렛트 크기가 비슷할 때 0으로 나눠질 수
있어 데모에서는 박스 크기 기준을 쓴다.)

이 모듈은 감지 모델과 분리돼 있다 — bbox 두 개만 받으므로 하드웨어·추론 없이
테스트된다 (tfnova.py의 파싱 코어와 같은 방침).
"""

from __future__ import annotations

import math
from dataclasses import dataclass

# 편하중 판정 임계값. 박스가 자기 반폭의 30%만큼 치우치면 경고.
DEFAULT_THRESHOLD = 0.3

# 감지 결과에서 이 점수 미만은 무시한다 (오탐 억제).
DEFAULT_MIN_SCORE = 0.5

# 감지 클래스 라벨 (configs/datasets.yaml의 classes: [box, pallet]와 일치).
LABEL_BOX = "box"
LABEL_PALLET = "pallet"


@dataclass(frozen=True)
class BBox:
    """축 정렬 경계 상자. COCO와 같은 (좌상단 x, y, 폭 w, 높이 h) 픽셀 좌표."""

    x: float
    y: float
    w: float
    h: float

    @property
    def center_x(self) -> float:
        return self.x + self.w / 2

    @property
    def center_y(self) -> float:
        return self.y + self.h / 2


@dataclass(frozen=True)
class LoadBalance:
    """편하중 판정 결과.

    비율은 부호가 있다. 이미지 좌표 기준으로 x는 오른쪽(+)/왼쪽(-), y는 아래(+)/위(-).
    카메라가 위에서 내려다보는 배치라면 x=좌우, y=앞뒤에 대응한다 (실제 축 대응은
    스테이션 카메라 방향에 따라 확정할 것).
    """

    ratio_x: float
    ratio_y: float
    threshold: float

    @property
    def magnitude(self) -> float:
        """두 축 치우침의 크기 (경고 정렬·표시용)."""
        return math.hypot(self.ratio_x, self.ratio_y)

    @property
    def eccentric(self) -> bool:
        """어느 한 축이라도 임계값을 넘으면 편하중이다."""
        return abs(self.ratio_x) > self.threshold or abs(self.ratio_y) > self.threshold

    @property
    def direction(self) -> str:
        """치우친 방향을 사람이 읽을 문구로. 정상이면 빈 문자열."""
        parts: list[str] = []
        if abs(self.ratio_x) > self.threshold:
            parts.append("오른쪽" if self.ratio_x > 0 else "왼쪽")
        if abs(self.ratio_y) > self.threshold:
            parts.append("아래" if self.ratio_y > 0 else "위")
        return "·".join(parts)

    def __str__(self) -> str:
        if not self.eccentric:
            return f"정상 (치우침 {self.magnitude:.2f} ≤ {self.threshold:g})"
        return f"⚠️ {self.direction} 편하중 (치우침 {self.magnitude:.2f} > {self.threshold:g})"

    def to_dict(self) -> dict:
        """표면에 독립적인 경고 페이로드. 소비자(대시보드·시리얼·로그)가 그대로 쓴다.

        출력처가 아직 미정이라(FR-104) 문자열이 아니라 구조화 값으로 낸다 — 화면은
        ``message``를, 제어 로직은 ``ratio_x/ratio_y``를, 집계는 ``eccentric``을 쓴다.
        """
        return {
            "eccentric": self.eccentric,
            "direction": self.direction,
            "ratio_x": round(self.ratio_x, 3),
            "ratio_y": round(self.ratio_y, 3),
            "magnitude": round(self.magnitude, 3),
            "threshold": self.threshold,
            "message": str(self),
        }


def assess(box: BBox, pallet: BBox, threshold: float = DEFAULT_THRESHOLD) -> LoadBalance:
    """박스 하나가 파렛트 위에서 치우쳤는지 판정한다. ``assess_load``의 특수 케이스."""
    return assess_load([box], pallet, threshold=threshold)


def assess_load(
    boxes: list[BBox], pallet: BBox, threshold: float = DEFAULT_THRESHOLD
) -> LoadBalance:
    """박스 1~N개의 화물 무게중심이 파렛트 위에서 치우쳤는지 판정한다.

    무게중심은 **박스 중심들의 단순 평균**이다 (2026-07-22 결정). 부피·면적 가중은
    쓰지 않는다 — bbox는 정면 투영이라 깊이가 없어 무게 대리값으로 불충분하고,
    데모 스케일에선 단순 평균이 가정도 적고 설명도 쉽다. 밀도·크기 차이가 큰
    화물 조합에서는 오차가 생길 수 있는 추정치다.

    정규화 분모는 **박스들을 모두 감싸는 외곽(hull)의 반폭/반높이** — 박스가
    하나면 그 박스 자신이라 단일 박스 판정과 정확히 같아지고, 여러 개여도
    화물 덩어리 크기 대비 비율이라 스케일 불변이 유지된다.

    빈 목록이거나 퇴화 bbox가 섞여 있으면 ``ValueError``.
    """
    if not boxes:
        raise ValueError("박스가 없다")
    for b in boxes:
        if b.w <= 0 or b.h <= 0:
            raise ValueError(f"박스 bbox 크기가 0 이하다: w={b.w}, h={b.h}")

    center_x = sum(b.center_x for b in boxes) / len(boxes)
    center_y = sum(b.center_y for b in boxes) / len(boxes)

    hull_half_w = (max(b.x + b.w for b in boxes) - min(b.x for b in boxes)) / 2
    hull_half_h = (max(b.y + b.h for b in boxes) - min(b.y for b in boxes)) / 2

    ratio_x = (center_x - pallet.center_x) / hull_half_w
    ratio_y = (center_y - pallet.center_y) / hull_half_h
    return LoadBalance(ratio_x=ratio_x, ratio_y=ratio_y, threshold=threshold)


# --- 감지 모델 연결 (FR-101 추론 → FR-104) ---------------------------------
#
# RTMDet 추론(FR-101-3/5)은 아직 없다. 그 출력이 나오면 클래스 라벨·bbox·점수를
# Detection 리스트로 감싸 assess_detections에 넘기면 된다 — 이 어댑터가 모델과
# 편하중 로직 사이의 유일한 접점이다. bbox 좌표만 다루므로 지금 하드웨어·모델 없이
# 테스트된다.


@dataclass(frozen=True)
class Detection:
    """감지 모델 출력 하나. label은 'box' 또는 'pallet' (datasets.yaml 기준)."""

    label: str
    box: BBox
    score: float


class NoTargets(Exception):
    """편하중을 판정할 박스·파렛트 쌍을 찾지 못했다."""


def _intersection_area(a: BBox, b: BBox) -> float:
    left = max(a.x, b.x)
    top = max(a.y, b.y)
    right = min(a.x + a.w, b.x + b.w)
    bottom = min(a.y + a.h, b.y + b.h)
    if right <= left or bottom <= top:
        return 0.0
    return (right - left) * (bottom - top)


def select_targets(
    detections: list[Detection], min_score: float = DEFAULT_MIN_SCORE
) -> tuple[BBox, BBox]:
    """감지 목록에서 측정할 (박스, 파렛트) 쌍을 고른다.

    스테이션에는 파렛트 하나 위에 박스 하나가 원칙이지만, 감지기는 배경의 다른
    박스·파렛트도 잡을 수 있다. 그래서:

    - **파렛트**: 점수가 가장 높은 것 (측정 대상 파렛트).
    - **박스**: 그 파렛트와 가장 많이 겹치는 것. 파렛트 위에 놓인 박스를 자연히
      고르고, 멀리 떨어진 배경 박스는 겹침이 0이라 배제된다.

    ``min_score`` 미만 검출은 먼저 버린다. 박스나 파렛트가 없으면 ``NoTargets``.
    """
    boxes = [d for d in detections if d.label == LABEL_BOX and d.score >= min_score]
    pallets = [d for d in detections if d.label == LABEL_PALLET and d.score >= min_score]
    if not pallets:
        raise NoTargets("파렛트 감지 없음 (점수 미달 포함)")
    if not boxes:
        raise NoTargets("박스 감지 없음 (점수 미달 포함)")

    pallet = max(pallets, key=lambda d: d.score).box
    # 파렛트와 겹치는 박스 우선, 동률이면 점수로. 겹침이 전부 0이면(스테이션에 실제로
    # 박스가 파렛트를 벗어나 놓인 경우) 점수 최고 박스로 떨어진다.
    box = max(boxes, key=lambda d: (_intersection_area(d.box, pallet), d.score)).box
    return box, pallet


def select_load(
    detections: list[Detection], min_score: float = DEFAULT_MIN_SCORE
) -> tuple[list[BBox], BBox]:
    """감지 목록에서 (파렛트 위 박스들, 파렛트)를 고른다 — 다중 박스 대응.

    파렛트는 점수 최고, 박스는 **그 파렛트와 겹치는 전부**. 배경 박스는 겹침이
    0이라 자연히 배제된다. 겹치는 박스가 하나도 없으면(박스가 파렛트를 벗어나
    놓인 경우) 점수 최고 박스 하나로 폴백한다.
    """
    boxes = [d for d in detections if d.label == LABEL_BOX and d.score >= min_score]
    pallets = [d for d in detections if d.label == LABEL_PALLET and d.score >= min_score]
    if not pallets:
        raise NoTargets("파렛트 감지 없음 (점수 미달 포함)")
    if not boxes:
        raise NoTargets("박스 감지 없음 (점수 미달 포함)")

    pallet = max(pallets, key=lambda d: d.score).box
    on_pallet = [d.box for d in boxes if _intersection_area(d.box, pallet) > 0]
    if not on_pallet:
        on_pallet = [max(boxes, key=lambda d: d.score).box]
    return on_pallet, pallet


def assess_detections(
    detections: list[Detection],
    threshold: float = DEFAULT_THRESHOLD,
    min_score: float = DEFAULT_MIN_SCORE,
) -> LoadBalance:
    """감지 목록 → 편하중 판정. 모델 추론과 FR-104를 잇는 진입점.

    파렛트 위 박스가 여러 개면 중심 평균으로 합산 무게중심을 판정한다
    (``assess_load`` 참고). 하나면 기존 단일 박스 판정과 동일.
    """
    boxes, pallet = select_load(detections, min_score=min_score)
    return assess_load(boxes, pallet, threshold=threshold)
