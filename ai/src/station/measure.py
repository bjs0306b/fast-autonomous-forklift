"""치수 산출 — 전지웅의 FR-103-3 공식 구현.

출처: ``docs/ai/FR-103-3-height-estimation-formula.md`` (실측 검증 98.24%).

    치수(cm) = 픽셀 길이(px) × 거리(cm) / 초점거리(px)

세로(높이)는 fy, 가로(폭)는 fx를 쓴다. **깊이(앞뒤)는 측정하지 않는다** —
정면 단일 카메라로 불가능하며, 적재 단위가 파렛트(T-11 고정 규격)라 불필요
(2026-07-22 결정, 명세 §2.2 용어 정리).

핀홀 모델이므로 거리는 "카메라 → 대상 면"의 거리다. TF-Nova가 박스 앞면을
겨냥하도록 장착돼 있어(명세 §2.3) 그 값을 그대로 쓴다.
"""

from __future__ import annotations


def height_cm(pixel_height: float, distance_cm: float, fy: float) -> float:
    """bbox 픽셀 높이 → 실제 높이(cm). 문서 예시: 600px @100cm → 29.43cm."""
    return pixel_height * distance_cm / fy


def width_cm(pixel_width: float, distance_cm: float, fx: float) -> float:
    """bbox 픽셀 폭 → 실제 폭(cm). 높이와 같은 공식, 가로 초점거리만 다름."""
    return pixel_width * distance_cm / fx
