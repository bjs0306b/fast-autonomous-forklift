"""copy-paste 합성 — 리그 파렛트를 오려 배경에 붙여 위치·스케일 다양성을 만든다.

리그 촬영분은 파렛트가 거의 같은 자리·같은 스케일이라(한 세션) 모델이 "그 자리의
파렛트"만 외울 위험이 있다. 자동 라벨의 명도 마스크로 파렛트를 오려 네거티브 프레임
(빈 바닥) 위에 랜덤 위치·스케일·플립으로 붙이면, 진짜 카펫·진짜 파렛트 질감을
유지한 채 배치 다양성을 공짜로 번다 (2026-07-24, Jira 156 실험7 재료).

    python -m dataset.composite --coco data/processed/rig_labeled.json \
        --images data/raw/rig/20260724 --out data/processed/rig_composites --count 300

라벨은 붙인 좌표에서 자동 생성되므로 정확하다. 박스 0~2개를 파렛트 위에 겹쳐
가림(occlusion) 샘플도 만든다. 출력 COCO는 box=1, pallet=2 (기존 계보).
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402
import numpy as np  # noqa: E402

DARK_THRESHOLD = 95      # 파렛트(어두움) 분리 임계 — autolabel과 같은 원리
NEGATIVE_RANGE = range(121, 129)   # 빈 바닥 프레임 번호 (0121~0128)


@dataclass
class Cutout:
    rgb: np.ndarray      # bbox 크기의 BGR 패치
    alpha: np.ndarray    # 0~1 float 마스크 (구멍은 0 — 새 배경이 비친다)


def pallet_cutout(frame: np.ndarray, bbox: list[float]) -> Cutout | None:
    x, y, w, h = (int(round(v)) for v in bbox)
    patch = frame[y:y + h, x:x + w]
    if patch.size == 0:
        return None
    gray = cv2.cvtColor(patch, cv2.COLOR_BGR2GRAY)
    mask = (gray < DARK_THRESHOLD).astype(np.uint8) * 255
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE,
                            cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9)))
    if (mask > 0).mean() < 0.3:      # 마스크가 성기면 조명이 튄 프레임 — 버린다
        return None
    alpha = cv2.GaussianBlur(mask.astype(np.float32) / 255.0, (5, 5), 0)
    return Cutout(rgb=patch, alpha=alpha)


def box_cutout(frame: np.ndarray, bbox: list[float]) -> Cutout | None:
    x, y, w, h = (int(round(v)) for v in bbox)
    patch = frame[y:y + h, x:x + w]
    if patch.size == 0:
        return None
    alpha = np.ones(patch.shape[:2], dtype=np.float32)   # 박스는 통짜 사각형
    alpha[:2, :] = alpha[-2:, :] = alpha[:, :2] = alpha[:, -2:] = 0.5  # 가장자리만 살짝
    return Cutout(rgb=patch, alpha=alpha)


def paste(canvas: np.ndarray, cut: Cutout, x: int, y: int,
          scale: float, flip: bool) -> tuple[int, int, int, int] | None:
    rgb, alpha = cut.rgb, cut.alpha
    if flip:
        rgb, alpha = cv2.flip(rgb, 1), cv2.flip(alpha, 1)
    w = int(rgb.shape[1] * scale)
    h = int(rgb.shape[0] * scale)
    if w < 10 or h < 10:
        return None
    rgb = cv2.resize(rgb, (w, h))
    alpha = cv2.resize(alpha, (w, h))

    H, W = canvas.shape[:2]
    x = max(0, min(x, W - w))
    y = max(0, min(y, H - h))
    if x + w > W or y + h > H:
        return None
    region = canvas[y:y + h, x:x + w].astype(np.float32)
    a = alpha[..., None]
    canvas[y:y + h, x:x + w] = (rgb.astype(np.float32) * a
                                + region * (1 - a)).astype(np.uint8)
    return x, y, w, h


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="리그 copy-paste 합성 (실험7 재료)")
    parser.add_argument("--coco", type=Path, required=True, help="rig_labeled.json")
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--count", type=int, default=300)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)

    rng = random.Random(args.seed)
    coco = json.loads(args.coco.read_text(encoding="utf-8"))
    imgs = {i["id"]: i for i in coco["images"]}

    # 재료 수집: 자동 라벨 파렛트(마스크 fit이 좋은 것) + 모델 프리라벨 박스
    pallet_cuts: list[Cutout] = []
    box_cuts: list[Cutout] = []
    backgrounds: list[np.ndarray] = []
    frame_cache: dict[int, np.ndarray] = {}

    def load(image_id: int) -> np.ndarray | None:
        if image_id not in frame_cache:
            info = imgs.get(image_id)
            frame_cache[image_id] = (cv2.imread(str(args.images / info["file_name"]))
                                     if info else None)
        return frame_cache[image_id]

    for ann in coco["annotations"]:
        frame = load(ann["image_id"])
        if frame is None:
            continue
        if ann["category_id"] == 2 and ann.get("source") != "manual":
            cut = pallet_cutout(frame, ann["bbox"])
            if cut is not None and len(pallet_cuts) < 60:
                pallet_cuts.append(cut)
        elif ann["category_id"] == 1 and len(box_cuts) < 60:
            cut = box_cutout(frame, ann["bbox"])
            if cut is not None:
                box_cuts.append(cut)

    for info in coco["images"]:
        num = info["file_name"].rsplit("_", 1)[-1].split(".")[0]
        if num.isdigit() and int(num) in NEGATIVE_RANGE:
            frame = load(info["id"])
            if frame is not None:
                backgrounds.append(frame)

    if not pallet_cuts or not backgrounds:
        print(f"재료 부족: pallet {len(pallet_cuts)}, bg {len(backgrounds)}", file=sys.stderr)
        return 1
    print(f"재료: 파렛트 {len(pallet_cuts)} / 박스 {len(box_cuts)} / 배경 {len(backgrounds)}")

    args.out.mkdir(parents=True, exist_ok=True)
    out_images, out_anns = [], []
    next_ann = 1
    for i in range(1, args.count + 1):
        canvas = rng.choice(backgrounds).copy()
        # 전역 광도 흔들기 — 배경 8장의 단조로움 완화
        gain = rng.uniform(0.75, 1.15)
        canvas = np.clip(canvas.astype(np.float32) * gain, 0, 255).astype(np.uint8)
        H, W = canvas.shape[:2]

        cut = rng.choice(pallet_cuts)
        scale = rng.uniform(0.55, 1.05)
        w = int(cut.rgb.shape[1] * scale)
        x = rng.randint(0, max(1, W - w))
        # 바닥 원근에 맞춰 하단 띠에 배치 (작을수록=멀수록 위쪽)
        y_bottom = int(np.interp(scale, [0.55, 1.05], [H * 0.72, H * 0.98]))
        y = y_bottom - int(cut.rgb.shape[0] * scale)
        placed = paste(canvas, cut, x, y, scale, rng.random() < 0.5)
        if placed is None:
            continue
        px, py, pw, ph = placed
        out_anns.append({"id": next_ann, "image_id": i, "category_id": 2,
                         "bbox": [px, py, pw, ph], "area": pw * ph,
                         "iscrowd": 0, "source": "composite"})
        next_ann += 1

        for _ in range(rng.choice([0, 1, 1, 2])):
            if not box_cuts:
                break
            bcut = rng.choice(box_cuts)
            bscale = scale * rng.uniform(0.8, 1.1)
            bw = int(bcut.rgb.shape[1] * bscale)
            bx = rng.randint(px, max(px + 1, px + pw - bw))
            by = py - int(bcut.rgb.shape[0] * bscale) + rng.randint(-5, 15)
            bplaced = paste(canvas, bcut, bx, by, bscale, rng.random() < 0.5)
            if bplaced is None:
                continue
            qx, qy, qw, qh = bplaced
            out_anns.append({"id": next_ann, "image_id": i, "category_id": 1,
                             "bbox": [qx, qy, qw, qh], "area": qw * qh,
                             "iscrowd": 0, "source": "composite"})
            next_ann += 1

        name = f"comp_{i:04d}.jpg"
        ok, buf = cv2.imencode(".jpg", canvas, [cv2.IMWRITE_JPEG_QUALITY, 88])
        if not ok:
            continue
        buf.tofile(str(args.out / name))
        out_images.append({"id": i, "file_name": name, "width": W, "height": H})

    result = {
        "images": out_images,
        "annotations": out_anns,
        "categories": [{"id": 1, "name": "box"}, {"id": 2, "name": "pallet"}],
    }
    (args.out / "_annotations.coco.json").write_text(
        json.dumps(result, ensure_ascii=False), encoding="utf-8")
    pallets = sum(1 for a in out_anns if a["category_id"] == 2)
    boxes = sum(1 for a in out_anns if a["category_id"] == 1)
    print(f"합성 {len(out_images)}장 / 파렛트 {pallets} / 박스 {boxes} → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
