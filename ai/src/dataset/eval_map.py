"""평가셋 mAP@0.5 측정 — 스테이션 박스 인식 KPI 판정 (FR-101, S15P11A304-148).

COCO 방식(all-point interpolation)으로 클래스별 AP를 낸다. pycocotools는 Windows
설치가 번거로워 직접 구현했다 — mAP@0.5 하나만 필요하고, 계산이 투명해야 KPI 근거로
쓸 수 있기 때문이다.

    python -m dataset.eval_map --gt data/processed/eval_20260727_clean.json \
        --images data/raw/rig/eval_20260727

⚠️ **평가셋 편향 주의**: 정답 라벨을 같은 모델의 프리라벨로 만들었다면 그 모델의
점수는 낙관적으로 나온다(자기 예측을 정답으로 채점). `--independent-ids`로 사람이
처음부터 그린 서브셋만 따로 평가해 편향 크기를 가늠할 수 있다.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

BOX_ID, PALLET_ID = 1, 2
NAMES = {BOX_ID: "box", PALLET_ID: "pallet"}
IOU_THRESHOLD = 0.5


def iou_matrix(pred: np.ndarray, gt: np.ndarray) -> np.ndarray:
    """pred (N,4), gt (M,4) — 둘 다 xywh. 반환 (N,M)."""
    if len(pred) == 0 or len(gt) == 0:
        return np.zeros((len(pred), len(gt)))
    p = pred[:, None, :]
    g = gt[None, :, :]
    px1, py1, px2, py2 = p[..., 0], p[..., 1], p[..., 0] + p[..., 2], p[..., 1] + p[..., 3]
    gx1, gy1, gx2, gy2 = g[..., 0], g[..., 1], g[..., 0] + g[..., 2], g[..., 1] + g[..., 3]
    iw = np.clip(np.minimum(px2, gx2) - np.maximum(px1, gx1), 0, None)
    ih = np.clip(np.minimum(py2, gy2) - np.maximum(py1, gy1), 0, None)
    inter = iw * ih
    union = p[..., 2] * p[..., 3] + g[..., 2] * g[..., 3] - inter
    return np.where(union > 0, inter / union, 0.0)


def average_precision(matches: list[tuple[float, bool]], n_gt: int) -> float:
    """(score, is_tp) 목록 → AP. COCO와 같은 all-point interpolation."""
    if n_gt == 0:
        return float("nan")
    if not matches:
        return 0.0
    matches.sort(key=lambda t: -t[0])
    tp = np.array([1 if m[1] else 0 for m in matches])
    fp = 1 - tp
    tp_cum, fp_cum = np.cumsum(tp), np.cumsum(fp)
    recall = tp_cum / n_gt
    precision = tp_cum / np.maximum(tp_cum + fp_cum, 1e-9)
    # precision을 오른쪽부터 단조 감소하도록 감싼 뒤 recall 증가분으로 적분
    precision = np.maximum.accumulate(precision[::-1])[::-1]
    recall = np.concatenate([[0.0], recall])
    precision = np.concatenate([[precision[0]], precision])
    return float(np.sum(np.diff(recall) * precision[1:]))


def evaluate(gt_coco: dict, predictions: dict[int, list[dict]],
             image_ids: set[int] | None = None) -> dict:
    """이미지별 예측을 정답과 대조해 클래스별 AP를 낸다."""
    gt_by_img: dict[int, list[dict]] = {}
    for ann in gt_coco["annotations"]:
        if image_ids is None or ann["image_id"] in image_ids:
            gt_by_img.setdefault(ann["image_id"], []).append(ann)

    targets = [i["id"] for i in gt_coco["images"]
               if image_ids is None or i["id"] in image_ids]

    result = {}
    for cls in (BOX_ID, PALLET_ID):
        matches: list[tuple[float, bool]] = []
        n_gt = 0
        for img_id in targets:
            gts = [a for a in gt_by_img.get(img_id, []) if a["category_id"] == cls]
            preds = sorted([p for p in predictions.get(img_id, []) if p["category_id"] == cls],
                           key=lambda p: -p["score"])
            n_gt += len(gts)
            if not preds:
                continue
            gt_boxes = np.array([a["bbox"] for a in gts], dtype=float) if gts else np.zeros((0, 4))
            pred_boxes = np.array([p["bbox"] for p in preds], dtype=float)
            ious = iou_matrix(pred_boxes, gt_boxes)
            used = set()
            for i, pred in enumerate(preds):
                best_j, best_iou = -1, IOU_THRESHOLD
                for j in range(len(gts)):
                    if j in used or ious[i, j] < best_iou:
                        continue
                    best_j, best_iou = j, ious[i, j]
                if best_j >= 0:
                    used.add(best_j)
                    matches.append((pred["score"], True))
                else:
                    matches.append((pred["score"], False))
        ap = average_precision(matches, n_gt)
        tp = sum(1 for _, ok in matches if ok)
        result[cls] = {
            "ap": ap, "n_gt": n_gt, "n_pred": len(matches),
            "tp": tp, "fp": len(matches) - tp, "fn": n_gt - tp,
        }
    return result


def main(argv: list[str] | None = None) -> int:
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    import cv2
    from station.config import StationConfig
    from station.detector import OnnxDetector

    parser = argparse.ArgumentParser(description="평가셋 mAP@0.5 측정")
    parser.add_argument("--gt", type=Path, required=True)
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--model", type=Path, help="ONNX 경로 (기본: config)")
    parser.add_argument("--score", type=float, default=0.05,
                        help="추론 임계 — AP 곡선을 위해 낮게 둔다 (기본 0.05)")
    parser.add_argument("--independent-ids",
                        help="사람이 처음부터 그린 서브셋의 파일번호(쉼표). "
                             "지정하면 전체와 서브셋을 나눠 보여준다")
    args = parser.parse_args(argv)

    gt = json.loads(args.gt.read_text(encoding="utf-8"))
    cfg = StationConfig()
    detector = OnnxDetector(args.model or cfg.model_path, cfg.input_size, args.score,
                            cfg.class_names, cfg.norm_mean, cfg.norm_std)

    preds: dict[int, list[dict]] = {}
    for n, info in enumerate(gt["images"], start=1):
        frame = cv2.imread(str(args.images / info["file_name"]))
        if frame is None:
            continue
        dets = detector.detect(frame)
        preds[info["id"]] = [{
            "category_id": BOX_ID if d.label == "box" else PALLET_ID,
            "bbox": [d.box.x, d.box.y, d.box.w, d.box.h],
            "score": d.score,
        } for d in dets]
        if n % 25 == 0 or n == len(gt["images"]):
            print(f"  추론 {n}/{len(gt['images'])}")

    def report(title: str, ids: set[int] | None) -> None:
        res = evaluate(gt, preds, ids)
        aps = [v["ap"] for v in res.values() if not np.isnan(v["ap"])]
        print(f"\n=== {title} ===")
        print(f"{'class':8s} {'AP@0.5':>8s} {'GT':>6s} {'TP':>6s} {'FP':>6s} {'FN':>6s}")
        for cls, v in res.items():
            print(f"{NAMES[cls]:8s} {v['ap']:8.4f} {v['n_gt']:6d} {v['tp']:6d} "
                  f"{v['fp']:6d} {v['fn']:6d}")
        print(f"{'mAP':8s} {np.mean(aps):8.4f}")

    report(f"전체 {len(gt['images'])}장", None)

    if args.independent_ids:
        wanted = {v.strip() for v in args.independent_ids.split(",") if v.strip()}
        ids = {i["id"] for i in gt["images"]
               if i["file_name"].rsplit("_", 1)[-1].split(".")[0] in wanted}
        report(f"독립 라벨 서브셋 {len(ids)}장", ids)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
