"""파렛트 임계를 올릴 때의 대가를 실측한다 (G2 재현율 vs 네거티브 오탐).

CLAUDE.md 원칙: "임계를 바꾸려면 대가를 먼저 측정할 것."
eval positive 105장(G2)과 네거티브 120장(오탐)에서 임계별로 잰다.
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path

from mmdet.apis import init_detector, inference_detector

CONFIG = "configs/rtmdet_s_640_onboard_forklift.py"
LABELS = "data/labels/onboard_eval.json"
IMG_DIR = Path("data/raw/onboard/eval_20260730")
NEG_DIR = Path("data/raw/onboard/eval_neg_20260730")
SKIP = {91, 92, 93, 103, 104, 105, 106, 107, 108, 115, 121, 122}
IOU = 0.5
PRED_PALLET = 0
LBL_PALLET = 2
THRESHOLDS = [0.4, 0.5, 0.6, 0.7, 0.8]


def frame_no(name):
    return int(Path(name).stem.rsplit("_", 1)[-1])


def iou(a, b):
    ax, ay, aw, ah = a; bx, by, bw, bh = b
    ix = max(0, min(ax + aw, bx + bw) - max(ax, bx))
    iy = max(0, min(ay + ah, by + bh) - max(ay, by))
    inter = ix * iy
    u = aw * ah + bw * bh - inter
    return inter / u if u > 0 else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    a = ap.parse_args()

    coco = json.loads(Path(LABELS).read_text(encoding="utf-8"))
    name_of = {im["id"]: im["file_name"] for im in coco["images"]}
    gt = defaultdict(list)
    for x in coco["annotations"]:
        if x["category_id"] == LBL_PALLET:
            gt[frame_no(name_of[x["image_id"]])].append(x["bbox"])

    model = init_detector(CONFIG, a.ckpt, device="cuda:0")

    def raw(p):
        r = inference_detector(model, str(p)).pred_instances
        b = r.bboxes.cpu().numpy(); s = r.scores.cpu().numpy(); l = r.labels.cpu().numpy()
        out = []
        for i in range(len(s)):
            if l[i] != PRED_PALLET:
                continue
            x1, y1, x2, y2 = b[i]
            out.append(([float(x1), float(y1), float(x2-x1), float(y2-y1)], float(s[i])))
        return out

    pos = [p for p in sorted(IMG_DIR.glob("*.jpg"), key=lambda q: frame_no(q.name))
           if frame_no(p.name) not in SKIP and gt[frame_no(p.name)]]
    neg = sorted(NEG_DIR.glob("*.jpg"))
    print(f"positive {len(pos)}장 / 네거티브 {len(neg)}장 추론 중...")

    pos_pred = {frame_no(p.name): raw(p) for p in pos}
    neg_pred = [raw(p) for p in neg]

    print(f"\n임계 | G2 재현율      | 네거티브 pallet 오탐 | 판정")
    for t in THRESHOLDS:
        tp = tot = 0
        for n, gts in ((frame_no(p.name), gt[frame_no(p.name)]) for p in pos):
            preds = [bb for bb, s in pos_pred[n] if s >= t]
            used = [False]*len(preds)
            for g in gts:
                best, bj = IOU, -1
                for j, pb in enumerate(preds):
                    if used[j]:
                        continue
                    v = iou(g, pb)
                    if v >= best:
                        best, bj = v, j
                if bj >= 0:
                    used[bj] = True; tp += 1
            tot += len(gts)
        fp_frames = sum(1 for pr in neg_pred if any(s >= t for _, s in pr))
        rec = tp/tot if tot else 0
        verdict = "PASS" if rec >= 0.98 else "FAIL(G2 미달)"
        print(f"{t:.1f}  | {rec*100:5.1f}% ({tp}/{tot}) | "
              f"{fp_frames:3d}/{len(neg)} 프레임 ({fp_frames/len(neg)*100:.1f}%) | {verdict}")


if __name__ == "__main__":
    main()
