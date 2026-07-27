"""카메라 롤 보정 — 파렛트 상판을 수평 기준면으로 삼는다 (FR-103).

**왜 필요한가**: 감지 bbox는 축 정렬(AABB)이라 물체가 θ만큼 기울어 보이면 실제보다
크게 잡힌다. 카메라를 손으로 수평 맞춰도 1~2°는 남고, 그게 치수 오차로 그대로 온다.

    AABB_w = w·cosθ + h·sinθ
    AABB_h = w·sinθ + h·cosθ

**어떻게 푸는가**: 파렛트는 바닥에 놓이므로 상판 윗면이 **항상 실제 수평**이다. 그
라인이 이미지에서 기울어 보이면 그게 곧 카메라 롤이고, 위 식을 역산하면 실제 w,h가
나온다. 파렛트가 측정 장면에 늘 있다는 점을 공짜 기준면으로 쓰는 셈이다.

**실측 검증 (2026-07-27, 리그)**: 롤 −1.89° 검출 → 50×30cm 박스에서
높이 오차 +1.8cm → **+0.2cm**, 최악 오차 1.8mm → 0.6mm (미니어처 환산).

파렛트가 검은색이고 바닥(카펫)이 밝아 명도 분리로 상판 경계가 선명하게 잡힌다
(dataset.pallet_autolabel과 같은 원리).
"""

from __future__ import annotations

import math

import cv2
import numpy as np

from perception.load_balance import BBox

# 이 각도를 넘으면 셋업이 잘못된 것이지 미세 롤이 아니다 — 보정하면 오히려 악화된다.
MAX_ABS_ANGLE_DEG = 5.0
MIN_EDGE_SAMPLES = 20      # 직선 적합에 최소한 필요한 열 샘플 수
COLUMN_STEP = 4            # 몇 픽셀 간격으로 열을 훑을지


def estimate_roll_deg(
    frame: np.ndarray,
    pallet: BBox,
    occluders: list[BBox] | None = None,
) -> float | None:
    """파렛트 상판 윗면 에지에서 카메라 롤 각도(도)를 추정한다.

    ``occluders``(보통 박스 bbox들)의 x 구간은 탐색에서 제외한다. 박스가 상판을
    가리면 그 열에서는 상판 대신 박스 경계가 잡혀 각도가 크게 틀어지기 때문이다
    (2026-07-27 실측: 편심 배치에서 −1.9° → +2.6°로 부호까지 뒤집혔다).

    반환 부호는 이미지 좌표계 기준 — 양수면 오른쪽이 아래로 내려간 상태다.
    추정이 불안정하거나 각도가 ``MAX_ABS_ANGLE_DEG``를 넘으면 ``None``(보정 안 함).
    """
    h_img, w_img = frame.shape[:2]
    x0 = max(0, int(pallet.x))
    x1 = min(w_img, int(pallet.x + pallet.w))
    # 상판 경계는 bbox 상단 근처에 있다. 살짝 위까지 포함해 잘림을 방지한다.
    y0 = max(0, int(pallet.y) - 12)
    y1 = min(h_img, y0 + max(30, int(pallet.h * 0.45)))
    if x1 - x0 < 50 or y1 - y0 < 10:
        return None

    roi = frame[y0:y1, x0:x1]
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    # 파렛트(어두움) vs 배경(밝음) 분리. 고정 임계 대신 분위수 — 조명이 바뀌어도 버틴다.
    threshold = float(np.percentile(gray, 45))
    dark = gray < threshold

    # 가려진 x 구간 — ROI 좌표계로 변환해 둔다 (여유 8px는 박스 경계 그림자 때문).
    blocked: list[tuple[float, float]] = []
    for occ in occluders or []:
        blocked.append((occ.x - x0 - 8, occ.x + occ.w - x0 + 8))

    xs: list[float] = []
    ys: list[float] = []
    for col in range(0, roi.shape[1], COLUMN_STEP):
        if any(lo <= col <= hi for lo, hi in blocked):
            continue
        rows = np.flatnonzero(dark[:, col])
        if rows.size < 5:
            continue
        top = int(rows[0])                      # 위에서 처음 어두워지는 행 = 상판 윗면
        if 0 < top < roi.shape[0] - 1:          # ROI 경계에 붙은 값은 잘린 것이라 버린다
            xs.append(float(col))
            ys.append(float(top))

    if len(xs) < MIN_EDGE_SAMPLES:
        return None

    xa, ya = np.array(xs), np.array(ys)
    # 박스가 상판을 가리는 구간이 있으므로, 적합 → 잔차 큰 점 제거를 반복해 걷어낸다.
    slope = intercept = 0.0
    for _ in range(3):
        slope, intercept = np.polyfit(xa, ya, 1)
        resid = np.abs(ya - (slope * xa + intercept))
        keep = resid < max(3.0, 2.0 * float(resid.std()))
        if keep.sum() < MIN_EDGE_SAMPLES - 5:
            break
        xa, ya = xa[keep], ya[keep]
    slope, intercept = np.polyfit(xa, ya, 1)

    angle = math.degrees(math.atan(float(slope)))
    if abs(angle) > MAX_ABS_ANGLE_DEG:
        return None
    return angle


def deskew_size(width_px: float, height_px: float, angle_deg: float) -> tuple[float, float]:
    """기울어진 AABB 크기 → 실제 크기(픽셀). 모듈 상단 연립식의 해.

    ``angle_deg``가 0이거나 해가 불안정하면(θ→45°) 입력을 그대로 돌려준다.
    """
    theta = math.radians(abs(angle_deg))
    c, s = math.cos(theta), math.sin(theta)
    det = c * c - s * s                      # = cos(2θ)
    if det < 1e-3:                           # θ가 45°에 가까우면 역산이 발산한다
        return width_px, height_px
    w = (width_px * c - height_px * s) / det
    h = (height_px * c - width_px * s) / det
    if w <= 0 or h <= 0:                     # 물리적으로 불가능한 해 — 보정 포기
        return width_px, height_px
    return w, h
