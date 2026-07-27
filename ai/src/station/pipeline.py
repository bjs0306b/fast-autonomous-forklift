"""측정 파이프라인 — 감지·거리에서 판정 JSON(v1.0)까지의 순수 로직.

하드웨어·모델을 직접 만지지 않고 (Detection 리스트, 거리 측정값)만 받는다.
그래서 카메라·센서·ONNX 없이 테스트된다 (tfnova.FrameParser와 같은 방침).
실제 배선은 serve.py가 한다.

출력 규격: S15P11A304-91 코멘트의 측정 결과 페이로드 v1.0.

**편하중은 좌우(x축)만 판정한다.** 스테이션 카메라가 정면(광축 수평)이라
이미지 y축은 수직(높이) 방향이다 — 파렛트 위 앞뒤 치우침은 정면 뷰에서
관측할 수 없다 (박스 중심이 파렛트 중심보다 위에 있는 건 당연한 것이지
편하중이 아니다). 그래서 ratio_y는 항상 null로 낸다.
"""

from __future__ import annotations

import datetime as _dt
import itertools

from perception.load_balance import BBox, Detection, assess_load
from perception.tfnova import Measurement

from station import measure, tilt
from station.config import StationConfig

SCHEMA_VERSION = "1.0"

_counter = itertools.count(1)


def hull(boxes: list[BBox]) -> BBox:
    """박스들을 모두 감싸는 외곽 — 적재물 전체 영역 (FR-103-2b: 전체 영역 기준)."""
    left = min(b.x for b in boxes)
    top = min(b.y for b in boxes)
    right = max(b.x + b.w for b in boxes)
    bottom = max(b.y + b.h for b in boxes)
    return BBox(x=left, y=top, w=right - left, h=bottom - top)


def build_payload(
    detections: list[Detection],
    distance: Measurement | None,
    cfg: StationConfig,
    now: _dt.datetime | None = None,
    tilt_deg: float | None = None,
) -> dict:
    """감지·거리 → 측정 결과 페이로드.

    status 값 (규격 v1.0 + dimensions_only):
    - ``ok``: 박스+파렛트+거리 모두 있음 → 치수·편하중 다 채움
    - ``dimensions_only``: **파렛트가 없음** → 치수만 내고 load_balance는 null.
      3D 프린트 파렛트 전 임시 검증·치수 KPI(FR-103-4) 확인용.
    - ``no_detection``: 박스가 없음 → 잴 게 없음
    - ``unreliable``: 거리 취득 실패 → 치수 계산 불가

    편하중은 파렛트를 기준점으로 요구하므로, 파렛트가 없으면 치수만 가능하다.
    """
    now = now or _dt.datetime.now().astimezone()
    base = {
        "schema_version": SCHEMA_VERSION,
        "measurement_id": f"{cfg.station_id}-{now:%Y%m%d-%H%M%S}-{next(_counter):04d}",
        "station_id": cfg.station_id,
        "measured_at": now.isoformat(timespec="seconds"),
    }

    boxes = [d.box for d in detections
             if d.label == "box" and d.score >= cfg.score_threshold]
    pallets = [d for d in detections
               if d.label == "pallet" and d.score >= cfg.score_threshold]
    pallet = max(pallets, key=lambda d: d.score).box if pallets else None
    detection_block = _detection_block(detections, pallet)

    if not boxes:
        return {**base, "status": "no_detection", "detection": detection_block,
                "distance": _distance_block(distance),
                "dimensions": None, "load_balance": None}
    if distance is None:
        return {**base, "status": "unreliable", "detection": detection_block,
                "distance": None, "dimensions": None, "load_balance": None}

    load = hull(boxes)
    # 카메라 롤 보정 — bbox는 축 정렬이라 기울면 부풀려진다. 파렛트 상판을 수평
    # 기준면으로 각도를 재 역산한다(station.tilt). 각도가 없으면 원래 크기 그대로.
    load_w, load_h = load.w, load.h
    if tilt_deg:
        load_w, load_h = tilt.deskew_size(load.w, load.h, tilt_deg)
    height = measure.height_cm(load_h, distance.distance_cm, cfg.calib.fy)
    width = measure.width_cm(load_w, distance.distance_cm, cfg.calib.fx)
    dimensions = {
        "height_cm": round(height, 1),
        "width_cm": round(width, 1),
        "depth_cm": None,   # 정면 카메라로 측정 불가 — 항상 null (규격 v1.0)
        "miniature_scale": cfg.miniature_scale,
        "miniature_height_mm": round(height * 10 / cfg.miniature_scale, 1),
        "miniature_width_mm": round(width * 10 / cfg.miniature_scale, 1),
        # 적용된 롤 보정각(도). null이면 보정 안 함(파렛트 없음·추정 실패·범위 초과).
        "tilt_deg": round(tilt_deg, 2) if tilt_deg else None,
    }

    if pallet is None:   # 파렛트 없음 → 치수만, 편하중은 판정 불가
        return {**base, "status": "dimensions_only", "detection": detection_block,
                "distance": _distance_block(distance),
                "dimensions": dimensions, "load_balance": None}

    # 편하중: assess_load(중심 단순 평균 + hull 정규화) 재사용하되 좌우(x)만 채택
    # (모듈 도크스트링 참고). 부호 → 방향 코드.
    ratio_x = assess_load(boxes, pallet, threshold=cfg.eccentric_threshold).ratio_x
    eccentric = abs(ratio_x) > cfg.eccentric_threshold
    direction = (["right"] if ratio_x > 0 else ["left"]) if eccentric else []

    return {
        **base,
        "status": "ok",
        "detection": detection_block,
        "distance": _distance_block(distance),
        "dimensions": dimensions,
        "load_balance": {
            "eccentric": eccentric,
            "direction": direction,
            "ratio_x": round(ratio_x, 3),
            "ratio_y": None,    # 정면 뷰에서 y축은 수직 — 편하중 축이 아님
            "magnitude": round(abs(ratio_x), 3),
            "threshold": cfg.eccentric_threshold,
            "message": (f"{'오른쪽' if ratio_x > 0 else '왼쪽'} 편하중 "
                        f"(치우침 {abs(ratio_x):.2f} > {cfg.eccentric_threshold:g})"
                        if eccentric else
                        f"정상 (치우침 {abs(ratio_x):.2f} ≤ {cfg.eccentric_threshold:g})"),
        },
    }


def _detection_block(detections: list[Detection], pallet: BBox | None) -> dict:
    boxes = [d for d in detections if d.label == "box"]
    pallets = [d for d in detections if d.label == "pallet"]
    best_pallet = max(pallets, key=lambda d: d.score) if pallets else None
    return {
        "box_count": len(boxes),
        "boxes": [{"bbox_px": _px(d.box), "score": round(d.score, 2)} for d in boxes],
        "pallet": ({"bbox_px": _px(best_pallet.box), "score": round(best_pallet.score, 2)}
                   if best_pallet else None),
    }


def _distance_block(distance: Measurement | None) -> dict | None:
    if distance is None:
        return None
    return {
        "front_cm": round(distance.distance_cm, 1),
        "std_cm": round(distance.std_cm, 2),
        "frames_used": distance.frames_used,
    }


def _px(b: BBox) -> list[int]:
    return [int(round(v)) for v in (b.x, b.y, b.w, b.h)]
