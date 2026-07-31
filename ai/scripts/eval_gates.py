"""온보드 -s 승격 게이트 채점 (G1~G3) — 체크포인트로 eval셋 추론·판정.

승격 게이트(docs/ai/onboard-finetune-runbook.md §0):
  G1 hole 재현율 ≥95% · G2 pallet 재현율 ≥98% · G3 네거티브 hole 오탐 ≤2%.
매칭은 IoU≥0.5, score 임계 0.4. **GPU 서버(rtmdet env)에서 실행한다** — mmdet 필요.

    # ai/ 에서
    python scripts/eval_gates.py \
        --ckpt work_dirs/onboard_s_2class/epoch_116.pth \
        --labels data/labels/onboard_eval.json \
        --img-dir data/raw/onboard/eval_20260730 \
        --neg-dir data/raw/onboard/eval_neg_20260730

**프레임 3분류**:
  - positive: pallet·hole 라벨이 있는 프레임 → G1·G2 재현율
  - 네거티브: 파렛트 없는 빈 프레임(라벨 폴더의 빈 프레임 + --neg-dir 전체) → G3 오탐
  - 스킵: 블러·잘림·손 침입 전환 프레임(--skip). 채점에서 완전 제외 — 네거티브로 쓰면
    파렛트가 있는데 안 그린 프레임을 오탐 모수에 넣어 G3를 오염시킨다.

⚠️ **네거티브 표본이 작으면 G3가 불안정하다.** 33장이면 1오탐=3%. --neg-dir로
파렛트 없는 배경(hole 닮은 어두운 틈 포함)을 100장+ 확보하면 견고해진다(2026-07-30 실측).

⚠️ eval 라벨은 train과 **같은 규약**이어야 채점이 공정하다. 특히 세로 슬릿(종횡비<1.0)
제거를 train과 맞출 것 — 안 맞으면 모델이 안 배운 걸 정답으로 둬 재현율이 억울하게 깎인다.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path
import json

from mmdet.apis import init_detector, inference_detector

DEFAULT_CONFIG = "configs/rtmdet_s_640_onboard_forklift.py"
SCORE = 0.4
IOU = 0.5

# config classes=('pallet','hole') → 예측 label 0=pallet, 1=hole
PRED_PALLET, PRED_HOLE = 0, 1
# 라벨 category_id: box=1, pallet=2, hole=3 (box는 데이터에 0개)
LBL_PALLET, LBL_HOLE = 2, 3


def frame_no(name: str) -> int:
    return int(Path(name).stem.rsplit("_", 1)[-1])


def parse_skip(spec: str | None) -> set[int]:
    """'91-93,103-108,115,121,122' → {91,92,93,...}."""
    out: set[int] = set()
    if not spec:
        return out
    for part in spec.split(","):
        part = part.strip()
        if "-" in part:
            lo, hi = part.split("-", 1)
            out.update(range(int(lo), int(hi) + 1))
        elif part:
            out.add(int(part))
    return out


def iou(a, b) -> float:
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    ix = max(0, min(ax + aw, bx + bw) - max(ax, bx))
    iy = max(0, min(ay + ah, by + bh) - max(ay, by))
    inter = ix * iy
    u = aw * ah + bw * bh - inter
    return inter / u if u > 0 else 0.0


def recall(gts, preds) -> tuple[int, int]:
    """탐욕 매칭 IoU≥0.5 → (TP, 전체 GT). 예측 하나는 GT 하나에만."""
    matched = [False] * len(preds)
    tp = 0
    for g in gts:
        best_j, best_iou = -1, IOU
        for j, (pb, _) in enumerate(preds):
            if matched[j]:
                continue
            v = iou(g, pb)
            if v >= best_iou:
                best_iou, best_j = v, j
        if best_j >= 0:
            matched[best_j] = True
            tp += 1
    return tp, len(gts)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="온보드 -s 승격 게이트 채점")
    ap.add_argument("--ckpt", required=True, help="mmdet 체크포인트 .pth")
    ap.add_argument("--labels", type=Path, required=True, help="eval COCO 라벨")
    ap.add_argument("--img-dir", type=Path, required=True, help="eval 이미지 폴더")
    ap.add_argument("--neg-dir", type=Path, default=None,
                    help="추가 네거티브 폴더(라벨 없음, 전부 파렛트 없는 프레임)")
    ap.add_argument("--config", default=DEFAULT_CONFIG)
    ap.add_argument("--skip", default="",
                    help="채점 제외 프레임(블러·잘림). 예: 91-93,103-108,115,121,122")
    ap.add_argument("--device", default="cuda:0")
    a = ap.parse_args(argv)

    skip = parse_skip(a.skip)
    coco = json.loads(a.labels.read_text(encoding="utf-8"))
    name_of = {im["id"]: im["file_name"] for im in coco["images"]}
    gt = defaultdict(lambda: {"pallet": [], "hole": []})
    for x in coco["annotations"]:
        f = frame_no(name_of[x["image_id"]])
        if x["category_id"] == LBL_PALLET:
            gt[f]["pallet"].append(x["bbox"])
        elif x["category_id"] == LBL_HOLE:
            gt[f]["hole"].append(x["bbox"])

    model = init_detector(a.config, a.ckpt, device=a.device)

    def predict(p: Path):
        r = inference_detector(model, str(p)).pred_instances
        b = r.bboxes.cpu().numpy()
        s = r.scores.cpu().numpy()
        lab = r.labels.cpu().numpy()
        out = {"pallet": [], "hole": []}
        for i in range(len(s)):
            if s[i] < SCORE:
                continue
            x1, y1, x2, y2 = b[i]
            bb = [float(x1), float(y1), float(x2 - x1), float(y2 - y1)]
            if lab[i] == PRED_PALLET:
                out["pallet"].append((bb, float(s[i])))
            elif lab[i] == PRED_HOLE:
                out["hole"].append((bb, float(s[i])))
        return out

    files = sorted(a.img_dir.glob("*.jpg"), key=lambda p: frame_no(p.name))
    pos_frames, neg_frames = [], []
    for p in files:
        n = frame_no(p.name)
        if n in skip:
            continue
        if gt[n]["pallet"] or gt[n]["hole"]:
            pos_frames.append(p)
        else:
            neg_frames.append(p)
    extra_neg = 0
    if a.neg_dir:
        extra = sorted(a.neg_dir.glob("*.jpg"))
        neg_frames.extend(extra)
        extra_neg = len(extra)

    h_tp = h_tot = p_tp = p_tot = 0
    for p in pos_frames:
        n = frame_no(p.name)
        pr = predict(p)
        t, tot = recall(gt[n]["hole"], pr["hole"]); h_tp += t; h_tot += tot
        t, tot = recall(gt[n]["pallet"], pr["pallet"]); p_tp += t; p_tot += tot

    neg_hole_fp = neg_frames_with_fp = 0
    fp_detail = []
    for p in neg_frames:
        pr = predict(p)
        k = len(pr["hole"])
        neg_hole_fp += k
        if k:
            neg_frames_with_fp += 1
            fp_detail.append((frame_no(p.name), [round(s, 2) for _, s in pr["hole"]]))

    h_rec = h_tp / h_tot if h_tot else 0
    p_rec = p_tp / p_tot if p_tot else 0
    g3 = neg_frames_with_fp / len(neg_frames) if neg_frames else 0

    print(f"체크포인트: {a.ckpt}")
    print(f"positive {len(pos_frames)} / 네거티브 {len(neg_frames)}"
          f"(라벨폴더 {len(neg_frames)-extra_neg} + 추가 {extra_neg}) / 스킵 {len(skip)}\n")
    print(f"G1 hole 재현율   : {h_rec*100:.1f}%  ({h_tp}/{h_tot})   기준 ≥95%  "
          f"{'PASS' if h_rec >= 0.95 else 'FAIL'}")
    print(f"G2 pallet 재현율 : {p_rec*100:.1f}%  ({p_tp}/{p_tot})   기준 ≥98%  "
          f"{'PASS' if p_rec >= 0.98 else 'FAIL'}")
    print(f"G3 네거티브 오탐 : {g3*100:.1f}%  ({neg_frames_with_fp}/{len(neg_frames)} "
          f"프레임, hole {neg_hole_fp}개)   기준 ≤2%  {'PASS' if g3 <= 0.02 else 'FAIL'}")
    if fp_detail:
        print(f"   오탐 프레임: {fp_detail}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
