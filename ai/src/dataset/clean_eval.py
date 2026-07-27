"""평가셋 프리라벨 정리 — 배경 오탐을 걷어내고 파렛트 위 화물만 남긴다.

평가셋은 채점표라 **정답에 없는 박스가 라벨로 들어가면 mAP가 왜곡된다**(모델이
정확히 맞춰도 미탐으로 계산됨). 실측(2026-07-27, eval_20260727 112장)에서 확인된
오탐 패턴은 배경의 모니터·캐비닛이었고, 점수 분포가 깨끗하게 갈렸다:

    파렛트 위 화물 : 0.9대 (505개 중 422개가 0.8 이상)
    배경 오탐      : 0.3~0.5

그래서 두 단계로 거른다.

1. **점수 임계** — 박스 0.7 / 파렛트 0.5. 파렛트를 낮게 두는 이유는 분포가 다르기
   때문이다(파렛트는 0.7~0.9에 몰려 있어 0.8을 적용하면 116개 중 71개가 날아간다).
   하단이 프레임에 잘린 파렛트가 점수를 잃는 현상은 실험6에서도 관찰됐다.
2. **파렛트 위 판정** — 배경 물체는 파렛트와 x가 겹쳐도 **공중에 떠 있다**. 화물은
   파렛트 상판에 닿거나 아래 박스에 얹혀 있으므로, 파렛트 상단에서 시작해 위로
   연쇄로 이어지는 덩어리만 남긴다.

    python -m dataset.clean_eval --coco data/processed/eval_20260727_prelabel.json \
        --out data/processed/eval_20260727_clean.json
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

BOX_ID, PALLET_ID = 1, 2
BOX_SCORE = 0.7
# 파렛트는 점수가 아니라 **기하**로 거른다. 화물에 가려지면 점수가 0.35까지 떨어지지만
# (실측: 0032 0.48 / 0037 0.35 — 전부 진짜 파렛트), 리그에서 파렛트는 항상 바닥에
# 있고 가로로 길어서 위치·형태가 배경 오탐과 확실히 구분된다.
PALLET_SCORE = 0.3
PALLET_MIN_TOP_RATIO = 0.65   # bbox 상단이 프레임 높이의 이 비율보다 아래에 있어야 한다
PALLET_MIN_WIDTH_RATIO = 0.35  # 프레임 폭 대비 최소 폭
PALLET_MIN_ASPECT = 3.0        # 가로/세로 — 파렛트는 납작하고 길다
# 박스 하단이 파렛트 상단(또는 아래 박스 상단)보다 이만큼까지 위에 있어도 "얹힘"으로 본다.
# 적층 시 bbox가 살짝 뜨거나 겹치는 것을 흡수한다.
STACK_TOLERANCE_PX = 60


def _bounds(ann: dict) -> tuple[float, float, float, float]:
    x, y, w, h = ann["bbox"]
    return x, y, x + w, y + h          # left, top, right, bottom


def _x_overlaps(a: dict, b: dict) -> bool:
    al, _, ar, _ = _bounds(a)
    bl, _, br, _ = _bounds(b)
    return min(ar, br) - max(al, bl) > 0


def looks_like_pallet(ann: dict, img_w: int, img_h: int) -> bool:
    """리그 파렛트의 기하 조건 — 바닥에 있고 가로로 길다."""
    x, y, w, h = ann["bbox"]
    if h <= 0:
        return False
    return (y >= img_h * PALLET_MIN_TOP_RATIO
            and w >= img_w * PALLET_MIN_WIDTH_RATIO
            and w / h >= PALLET_MIN_ASPECT)


def keep_stacked_on_pallet(boxes: list[dict], pallet: dict | None) -> list[dict]:
    """파렛트에서 위로 연쇄로 이어지는 박스만 남긴다.

    파렛트가 없으면(빈 장면·파렛트 미검출) 판정 근거가 없으므로 박스를 그대로 둔다 —
    지우면 정답을 잃고, 남기면 사람이 검수에서 걸러낼 수 있다.
    """
    if pallet is None or not boxes:
        return boxes

    _, pallet_top, _, _ = _bounds(pallet)
    kept: list[dict] = []
    # 아래에 있는 것부터(=bottom이 큰 것부터) 훑으며 지지면을 위로 올린다
    support_top = pallet_top
    for box in sorted(boxes, key=lambda a: -_bounds(a)[3]):
        _, _, _, bottom = _bounds(box)
        if not _x_overlaps(box, pallet):
            continue                                    # 파렛트 좌우 밖 = 배경
        if bottom < support_top - STACK_TOLERANCE_PX:
            continue                                    # 공중에 뜸 = 배경
        kept.append(box)
        support_top = min(support_top, _bounds(box)[1])  # 이 박스 상단이 새 지지면
    return kept


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="평가셋 프리라벨 정리")
    parser.add_argument("--coco", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--box-score", type=float, default=BOX_SCORE)
    parser.add_argument("--pallet-score", type=float, default=PALLET_SCORE)
    parser.add_argument(
        "--keep-off-pallet", action="store_true",
        help="파렛트 위 판정을 건너뛴다 — 화면에 보이는 박스를 전부 라벨로 남길 때. "
             "스테이션 운용은 파렛트 위만 재지만, 검출 평가셋은 보이는 것을 다 센다.")
    args = parser.parse_args(argv)

    coco = json.loads(args.coco.read_text(encoding="utf-8"))
    by_img: dict[int, list[dict]] = {}
    for ann in coco["annotations"]:
        by_img.setdefault(ann["image_id"], []).append(ann)

    result: list[dict] = []
    cut_score = cut_offpallet = 0
    for img in coco["images"]:
        anns = by_img.get(img["id"], [])
        boxes = [a for a in anns if a["category_id"] == BOX_ID]
        pallets = [a for a in anns if a["category_id"] == PALLET_ID]

        kept_boxes = [a for a in boxes if a.get("score", 1.0) >= args.box_score]
        kept_pallets = [a for a in pallets
                        if a.get("score", 1.0) >= args.pallet_score
                        and looks_like_pallet(a, img["width"], img["height"])]
        cut_score += (len(boxes) - len(kept_boxes)) + (len(pallets) - len(kept_pallets))

        best_pallet = max(kept_pallets, key=lambda a: a.get("score", 0)) if kept_pallets else None
        on_pallet = (kept_boxes if args.keep_off_pallet
                     else keep_stacked_on_pallet(kept_boxes, best_pallet))
        cut_offpallet += len(kept_boxes) - len(on_pallet)

        result.extend(on_pallet)
        result.extend(kept_pallets)

    for i, ann in enumerate(sorted(result, key=lambda a: (a["image_id"], a["id"])), start=1):
        ann["id"] = i
    coco["annotations"] = result

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(coco, ensure_ascii=False), encoding="utf-8")

    box_n = sum(1 for a in result if a["category_id"] == BOX_ID)
    pal_n = sum(1 for a in result if a["category_id"] == PALLET_ID)
    print(f"이미지 {len(coco['images'])}장")
    print(f"  점수 미달 컷      : {cut_score}개 "
          f"(box<{args.box_score:g}, pallet<{args.pallet_score:g})")
    print(f"  파렛트 밖/공중 컷 : {cut_offpallet}개")
    print(f"  남은 라벨          : box {box_n} / pallet {pal_n}  → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
