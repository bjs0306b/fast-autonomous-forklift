"""박스 위치에서 파렛트 bbox를 역산한다 — 가림 상황 파렛트 라벨 자동 생성.

가려진 파렛트는 모델도 고전 CV도 못 잡는다(실측: 모델 203/299, 고전 CV 7/40).
그런데 **박스는 0.9대로 정확히 잡히고, 박스는 파렛트 위에 얹혀 있다.** 리그가 고정이라
박스 기하와 파렛트 기하의 관계가 일정하므로, 잘 잡힌 프레임으로 그 관계를 회귀하고
못 잡은 프레임에 적용한다.

관계는 거리에 따라 전부 비례하므로 **박스 크기를 스케일 대리값**으로 쓴다:

    파렛트.y  = 최하단 박스의 아랫변 + a·s
    파렛트.h  =                        b·s
    파렛트.cx = 박스 더미 중심        + c·s
    파렛트.w  = 박스 더미 폭          + d·s      (s = 최하단 박스 높이)

교차검증으로 오차를 재고, 그 오차가 사람 검수로 감당할 수준인지 판단한다.

    python -m dataset.pallet_from_boxes --coco data/processed/occluded_prelabel.json \
        --out data/processed/occluded_autopallet.json
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

BOX_ID, PALLET_ID = 1, 2
# 온전한 파렛트 라벨로 볼 조건 — 이걸로 회귀를 학습한다
GOOD_WIDTH_RATIO = 0.40
GOOD_ASPECT = 5.0


def features(boxes: list[list[float]]) -> tuple[float, float, float, float] | None:
    """박스 목록 → (최하단 아랫변, 더미 중심x, 더미 폭, 스케일)."""
    if not boxes:
        return None
    lowest = max(boxes, key=lambda b: b[1] + b[3])
    bottom = lowest[1] + lowest[3]
    x0 = min(b[0] for b in boxes)
    x1 = max(b[0] + b[2] for b in boxes)
    scale = lowest[3]                      # 최하단 박스 높이 = 거리 대리값
    return bottom, (x0 + x1) / 2, x1 - x0, scale


def collect(coco: dict):
    by: dict[int, list[dict]] = {}
    for a in coco["annotations"]:
        by.setdefault(a["image_id"], []).append(a)

    train, need = [], []
    for img in coco["images"]:
        anns = by.get(img["id"], [])
        boxes = [a["bbox"] for a in anns if a["category_id"] == BOX_ID]
        pals = [a["bbox"] for a in anns if a["category_id"] == PALLET_ID]
        f = features(boxes)
        if f is None:
            continue
        pal = max(pals, key=lambda b: b[2] * b[3]) if pals else None
        good = (pal is not None and pal[2] >= img["width"] * GOOD_WIDTH_RATIO
                and pal[3] > 0 and pal[2] / pal[3] >= GOOD_ASPECT)
        (train if good else need).append((img, f, pal))
    return train, need


def fit(train) -> np.ndarray:
    """각 목표를 스케일에 대한 비례식으로 회귀 (절편 없음 — 전부 거리에 비례)."""
    S = np.array([[f[3]] for _, f, _ in train], dtype=float)      # (N,1)
    targets = []
    for _, f, pal in train:
        bottom, cx, bw, s = f
        targets.append([pal[1] - bottom,                 # 파렛트 윗변 - 박스 아랫변
                        pal[3],                          # 파렛트 높이
                        (pal[0] + pal[2] / 2) - cx,      # 중심 차이
                        pal[2] - bw])                    # 폭 차이
    T = np.array(targets, dtype=float)                            # (N,4)
    # 최소제곱: T ≈ S @ coef
    coef, *_ = np.linalg.lstsq(S, T, rcond=None)
    return coef                                                   # (1,4)


def predict(f, coef) -> list[float]:
    bottom, cx, bw, s = f
    dy, h, dcx, dw = (coef[0] * s)
    w = max(bw + dw, 1.0)
    return [round((cx + dcx) - w / 2, 1), round(bottom + dy, 1),
            round(w, 1), round(max(h, 1.0), 1)]


# 세로 띠 안에서 검은 파렛트의 가로 범위를 찾을 때 쓰는 값
DARK_THRESH = 90          # 파렛트는 검은 플라스틱, 카펫은 회색
MIN_DARK_RATIO = 0.35     # 한 열이 '파렛트'로 인정되려면 띠 높이의 이 비율 이상이 어두워야


def horizontal_extent(frame, y: float, h: float) -> tuple[float, float] | None:
    """예측된 세로 띠 안에서 검은 픽셀의 가로 범위를 찾는다.

    가로(x·w)는 회귀가 부정확하다 — 박스를 파렛트 위 어디에 놓든 자유라 박스 더미
    폭과 상관이 약하기 때문(실측 x 132px·w 203px 오차). 반면 **세로는 잘 맞으므로**
    (y 13.6px·h 24px), 그 띠 안만 보면 파렛트를 명도로 쉽게 분리할 수 있다.
    """
    H, W = frame.shape[:2]
    y0, y1 = int(max(0, y)), int(min(H, y + h))
    if y1 - y0 < 5:
        return None
    strip = frame[y0:y1]
    gray = strip if strip.ndim == 2 else np.asarray(strip).mean(axis=2)
    dark_ratio = (gray < DARK_THRESH).mean(axis=0)      # 열마다 어두운 비율
    cols = np.flatnonzero(dark_ratio >= MIN_DARK_RATIO)
    if len(cols) < 20:
        return None
    # 양 끝 잡음을 피해 1~99 퍼센타일로 자른다
    return float(np.percentile(cols, 1)), float(np.percentile(cols, 99))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="박스에서 파렛트 역산")
    parser.add_argument("--coco", type=Path, required=True)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--images", type=Path,
                        help="이미지 폴더 — 주면 가로 범위를 명도로 보정한다")
    args = parser.parse_args(argv)

    coco = json.loads(args.coco.read_text(encoding="utf-8"))
    train, need = collect(coco)
    print(f"학습(온전한 파렛트) {len(train)}장 / 생성 대상 {len(need)}장")
    if len(train) < 10:
        print("학습 표본이 너무 적다", flush=True)
        return 1

    # --- 교차검증: 오차가 검수 가능한 수준인가 ---
    idx = np.arange(len(train))
    rng = np.random.default_rng(0)
    rng.shuffle(idx)
    folds = np.array_split(idx, 5)
    errs = []
    for k in range(5):
        te = set(folds[k].tolist())
        tr = [train[i] for i in idx if i not in te]
        coef = fit(tr)
        for i in folds[k]:
            img, f, pal = train[i]
            p = predict(f, coef)
            errs.append([abs(p[j] - pal[j]) for j in range(4)])
    E = np.array(errs)
    names = ["x", "y", "w", "h"]
    print("\n=== 교차검증 오차(px) ===")
    for j, n in enumerate(names):
        print(f"  {n}: 평균 {E[:, j].mean():6.1f}  중앙 {np.median(E[:, j]):6.1f}  "
              f"90%tile {np.percentile(E[:, j], 90):6.1f}  최대 {E[:, j].max():6.1f}")

    # IoU로도 본다 — 라벨 품질의 실질 지표
    import cv2
    ious, rescued = [], 0
    coef_all = fit(train)
    for img, f, pal in train:
        p = predict(f, coef_all)
        frame = cv2.imread(str(args.images / img["file_name"]), cv2.IMREAD_GRAYSCALE) \
            if args.images else None
        if frame is not None:
            ext = horizontal_extent(frame, p[1], p[3])
            if ext:
                p = [round(ext[0], 1), p[1], round(ext[1] - ext[0], 1), p[3]]
                rescued += 1
        ax1, ay1, ax2, ay2 = p[0], p[1], p[0] + p[2], p[1] + p[3]
        bx1, by1, bx2, by2 = pal[0], pal[1], pal[0] + pal[2], pal[1] + pal[3]
        iw = max(0, min(ax2, bx2) - max(ax1, bx1))
        ih = max(0, min(ay2, by2) - max(ay1, by1))
        inter = iw * ih
        u = p[2] * p[3] + pal[2] * pal[3] - inter
        ious.append(inter / u if u > 0 else 0)
    ious = np.array(ious)
    print(f"\n  학습셋 재현 IoU: 평균 {ious.mean():.3f} / 중앙 {np.median(ious):.3f} / "
          f"0.7 이상 {(ious >= 0.7).mean():.0%}   (명도 보정 {rescued}/{len(train)}장)")

    if not args.out:
        return 0

    # --- 대상 프레임에 파렛트 생성 (기존 파렛트 라벨은 버리고 새로 넣는다) ---
    keep = [a for a in coco["annotations"]
            if not (a["category_id"] == PALLET_ID
                    and a["image_id"] in {img["id"] for img, _, _ in need})]
    next_id = max((a["id"] for a in coco["annotations"]), default=0) + 1
    added = 0
    for img, f, _ in need:
        b = predict(f, coef_all)
        if args.images:
            fr = cv2.imread(str(args.images / img["file_name"]), cv2.IMREAD_GRAYSCALE)
            if fr is not None:
                ext = horizontal_extent(fr, b[1], b[3])
                if ext:
                    b = [round(ext[0], 1), b[1], round(ext[1] - ext[0], 1), b[3]]
        keep.append({"id": next_id, "image_id": img["id"], "category_id": PALLET_ID,
                     "bbox": b, "area": round(b[2] * b[3], 1), "iscrowd": 0,
                     "source": "from_boxes"})
        next_id += 1
        added += 1
    coco["annotations"] = keep
    args.out.write_text(json.dumps(coco, ensure_ascii=False), encoding="utf-8")
    pal_n = sum(1 for a in keep if a["category_id"] == PALLET_ID)
    print(f"\n생성 {added}개 → 파렛트 총 {pal_n}개 / {len(coco['images'])}장  → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
