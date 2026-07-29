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

from station import measure, tilt, tipping
from station.config import StationConfig

# 1.1 (2026-07-29): tipping 블록 추가. 실제로는 07-28(MR !75)에 payload에 들어갔는데
# 버전을 올리지 않아 백엔드가 규격 변경을 감지하지 못했고, Spring Boot가 모르는 필드를
# 무시하는 탓에 tipping이 에러 없이 버려졌다. **필드를 추가하면 버전을 올린다.**
# 변경 이력은 docs/ai/station-measurement-handoff.md.
SCHEMA_VERSION = "1.1"

_counter = itertools.count(1)


# 파렛트에 직접 얹힌 판정: 박스 하단이 파렛트 bbox 상단보다 이만큼 위까지는 허용.
# 파렛트 bbox 상단은 **뒤쪽 데크 모서리**이고 화물은 그보다 앞(=이미지에서 아래)에
# 놓이므로, 실제로 얹힌 박스의 하단은 파렛트 상단보다 아래에 온다(실측 +86px).
# 배경 물체는 반대로 위에 뜬다(실측 −42px). 그 사이를 가르는 여유값.
ON_PALLET_TOLERANCE_PX = 20
# 적층 판정: 위 박스 하단과 아래 박스 상단의 간격 허용치 (실측 15~17px).
STACK_TOLERANCE_PX = 60


def _x_overlap(a: BBox, b: BBox) -> float:
    return min(a.x + a.w, b.x + b.w) - max(a.x, b.x)


def on_pallet(boxes: list[BBox], pallet: BBox | None) -> list[BBox]:
    """파렛트에서 위로 **연쇄로 지지되는** 박스만 남긴다 — 배경 물체 제거.

    실측(2026-07-28 치수 평가)에서 **벽에 있는 물체가 score 0.5~0.75로 박스로 잡혀**
    hull과 박스별 측정을 오염시켰다. 점수를 올려 거르면 진짜 박스 재현율이 같이
    떨어지므로(0.5→0.7에서 95.5%→91.1%), **기하로 거른다**: 화물은 파렛트에 닿거나
    아래 박스에 얹혀 있고, 배경 물체는 공중에 떠 있다.

    지지 연결을 전파한다 — 파렛트에 직접 닿은 박스에서 시작해, 그 위에 얹힌 박스를
    반복해서 추가한다. (단순히 "가장 높은 지지면보다 아래"로 보면 위쪽 박스가 지지면을
    올려버려 그 아래 배경 물체까지 통과한다 — 실측에서 확인된 함정.)

    파렛트가 없으면 판정 근거가 없어 그대로 둔다(치수만 내는 경로).
    """
    if pallet is None or not boxes:
        return boxes

    # 1) 파렛트에 직접 얹힌 것
    kept = [b for b in boxes
            if _x_overlap(b, pallet) > 0
            and b.y + b.h >= pallet.y - ON_PALLET_TOLERANCE_PX]

    # 2) 그 위에 얹힌 것을 더 이상 늘지 않을 때까지 전파
    rest = [b for b in boxes if b not in kept]
    changed = True
    while changed:
        changed = False
        for b in list(rest):
            for k in kept:
                if (_x_overlap(b, k) > 0
                        and abs((b.y + b.h) - k.y) <= STACK_TOLERANCE_PX):
                    kept.append(b)
                    rest.remove(b)
                    changed = True
                    break
    return kept


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
             if d.label == "box" and d.score >= cfg.threshold_for("box")]
    pallets = [d for d in detections
               if d.label == "pallet" and d.score >= cfg.threshold_for("pallet")]
    pallet = max(pallets, key=lambda d: d.score).box if pallets else None
    # 파렛트 위 화물만 남긴다 — 배경 물체가 치수·편하중을 오염시키지 않게(on_pallet).
    boxes = on_pallet(boxes, pallet)
    detection_block = _detection_block(detections, pallet)

    # 키는 status와 무관하게 **항상 있게** 한다 — 소비자가 키 유무를 분기하지 않도록.
    # 값이 없을 때는 null 또는 assessable=false로 이유를 담아 낸다.
    unassessable = {"assessable": False, "reason": "화물 또는 파렛트 없음"}

    if not boxes:
        return {**base, "status": "no_detection", "detection": detection_block,
                "distance": _distance_block(distance),
                "dimensions": None, "box_measurements": [],
                "tipping": unassessable, "load_balance": None}
    if distance is None:
        return {**base, "status": "unreliable", "detection": detection_block,
                "distance": None, "dimensions": None, "box_measurements": [],
                "tipping": {"assessable": False, "reason": "거리 측정 실패"},
                "load_balance": None}

    load = hull(boxes)
    # 카메라 롤 보정 — bbox는 축 정렬이라 기울면 부풀려진다. 파렛트 상판을 수평
    # 기준면으로 각도를 재 역산한다(station.tilt). 각도가 없으면 원래 크기 그대로.
    load_w, load_h = load.w, load.h
    if tilt_deg:
        load_w, load_h = tilt.deskew_size(load.w, load.h, tilt_deg)
    height = measure.height_cm(load_h, distance.distance_cm, cfg.calib.fy)
    width = measure.width_cm(load_w, distance.distance_cm, cfg.calib.fx)
    # 화물은 항상 파렛트 위에 실려 운반되므로, 적재 위치 산출(FR-202)이 쓸 값은
    # **파렛트를 포함한 총높이**다. 화물만의 높이(height_cm)도 같이 남긴다 —
    # 소비자가 무엇을 쓰는지 분명하도록 둘을 구분해 낸다.
    total_height = height + cfg.pallet_height_cm
    dimensions = {
        "height_cm": round(height, 1),          # 화물만
        "total_height_cm": round(total_height, 1),   # 화물 + 파렛트 (적재 판단용)
        "pallet_height_cm": cfg.pallet_height_cm,
        "width_cm": round(width, 1),
        "depth_cm": None,   # 정면 카메라로 측정 불가 — 항상 null (규격 v1.0)
        "miniature_scale": cfg.miniature_scale,
        "miniature_height_mm": round(height * 10 / cfg.miniature_scale, 1),
        "miniature_total_height_mm": round(total_height * 10 / cfg.miniature_scale, 1),
        "miniature_width_mm": round(width * 10 / cfg.miniature_scale, 1),
        # 적용된 롤 보정각(도). null이면 보정 안 함(파렛트 없음·추정 실패·범위 초과).
        "tilt_deg": round(tilt_deg, 2) if tilt_deg else None,
    }

    # 박스별 개별 치수 (다중 박스 동시 측정 — 각 박스를 따로 배치할 때 씀).
    # `dimensions`는 전체 적재물 외곽(hull) 하나지만, 데모는 박스를 개별로 옮기므로
    # 박스마다 치수를 낸다. ⚠️ 거리는 TF-Nova 단일값이라 **모든 박스가 카메라에서
    # 비슷한 거리(같은 앞면)** 여야 정확하다 — 앞뒤로 벌어지면 그 박스는 오차가 커진다.
    # 점수는 감지 목록에서 되찾는다(on_pallet은 BBox만 다룸)
    score_of = {(_px(d.box)[0], _px(d.box)[1], _px(d.box)[2], _px(d.box)[3]): d.score
                for d in detections if d.label == "box"}
    box_measurements = []
    for b in sorted(boxes, key=lambda x: x.y):   # 위에서 아래로
        bw, bh = b.w, b.h
        if tilt_deg:
            bw, bh = tilt.deskew_size(bw, bh, tilt_deg)
        h_cm = measure.height_cm(bh, distance.distance_cm, cfg.calib.fy)
        w_cm = measure.width_cm(bw, distance.distance_cm, cfg.calib.fx)
        box_measurements.append({
            "bbox_px": _px(b),
            "score": round(score_of.get(tuple(_px(b)), 0.0), 2),
            "height_cm": round(h_cm, 1),
            "width_cm": round(w_cm, 1),
            "miniature_height_mm": round(h_cm * 10 / cfg.miniature_scale, 1),
            "miniature_width_mm": round(w_cm * 10 / cfg.miniature_scale, 1),
        })

    if pallet is None:   # 파렛트 없음 → 치수만, 편하중은 판정 불가
        return {**base, "status": "dimensions_only", "detection": detection_block,
                "distance": _distance_block(distance),
                "dimensions": dimensions, "box_measurements": box_measurements,
                "tipping": {"assessable": False, "reason": "파렛트 미검출"},
                "load_balance": None}

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
        "box_measurements": box_measurements,
        # 전복 위험 — 편하중과 별개 질문(무게중심이 지지면을 벗어나는가). 롤 보정까지
        # 끝난 실측 치수로 종횡비를 본다(픽셀 종횡비는 원근 때문에 실제와 다르다).
        "tipping": tipping.assess_tipping(
            boxes, pallet,
            height_cm=dimensions["height_cm"], width_cm=dimensions["width_cm"]),
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
