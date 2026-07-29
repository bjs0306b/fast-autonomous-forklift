"""가림 증강 — 파렛트 위에 박스를 합성해 '화물이 파렛트를 덮은' 장면을 늘린다.

S15P11A304-159의 도메인은 **화물이 파렛트를 가리는 상황**인데 실촬영이 283장뿐이다.
mmdet 학습 파이프라인이 하는 증강(리사이즈·크롭·플립·색상)은 **가림 배치 자체를 새로
만들지는 못한다** — 그건 오프라인 copy-paste만 할 수 있다. 그래서 이 도구를 쓴다.

핵심: **파렛트 라벨은 amodal(가려진 부분 포함 전체 범위)이라 박스를 얹어도 그대로
유효하다.** 그래서 라벨 오염 없이 가림만 키울 수 있다.

    python -m dataset.occlude_aug --coco data/processed/occluded_labeled.json \
        --images data/raw/rig/20260728_occluded --out data/processed/occlude_aug --count 400

⚠️ 기존 박스 라벨이 합성 박스에 많이 가려지면 그 라벨은 버린다 — 안 그러면 "안 보이는
박스"를 정답으로 가르쳐 오탐을 유발한다(박스 라벨은 modal, 파렛트만 amodal).
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402
import numpy as np  # noqa: E402

BOX_ID, PALLET_ID = 1, 2
# 기존 박스 라벨이 이 비율 이상 가려지면 버린다 (박스는 modal 라벨)
MAX_COVERED = 0.55
# 합성 박스를 파렛트 폭의 몇 %로 놓을지
BOX_W_RANGE = (0.22, 0.42)


def crop_bank(coco: dict, images: Path) -> list[np.ndarray]:
    """박스 라벨에서 실제 박스 이미지를 오려 모은다 — 진짜 질감을 쓴다."""
    by: dict[int, list[dict]] = {}
    for a in coco["annotations"]:
        by.setdefault(a["image_id"], []).append(a)
    bank = []
    for img in coco["images"]:
        boxes = [a for a in by.get(img["id"], []) if a["category_id"] == BOX_ID]
        if not boxes:
            continue
        frame = cv2.imdecode(np.fromfile(str(images / img["file_name"]), dtype="uint8"),
                             cv2.IMREAD_COLOR)
        if frame is None:
            continue
        H, W = frame.shape[:2]
        for a in boxes:
            x, y, w, h = (int(round(v)) for v in a["bbox"])
            x, y = max(0, x), max(0, y)
            w, h = min(w, W - x), min(h, H - y)
            if w <= 60 or h <= 60:
                continue
            patch = frame[y:y + h, x:x + w]
            # 어두운 크롭은 버린다 — 박스가 아니라 그림자·검은 파렛트가 섞인 것이라
            # 붙이면 화면에 검은 사각형이 남는다(실측에서 확인).
            if patch.mean() < 90:
                continue
            bank.append(patch.copy())
    return bank


def covered(inner: list[float], outers: list[tuple[int, int, int, int]]) -> float:
    """inner bbox가 outers에 덮인 면적 비율(격자 근사)."""
    x, y, w, h = inner
    if w <= 0 or h <= 0:
        return 1.0
    hit, N = 0, 12
    for i in range(N):
        for j in range(N):
            px, py = x + w * (i + .5) / N, y + h * (j + .5) / N
            if any(ox <= px <= ox + ow and oy <= py <= oy + oh
                   for ox, oy, ow, oh in outers):
                hit += 1
    return hit / (N * N)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="파렛트 가림 증강 (copy-paste)")
    parser.add_argument("--coco", type=Path, required=True)
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--count", type=int, default=400)
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args(argv)

    rng = random.Random(args.seed)
    coco = json.loads(args.coco.read_text(encoding="utf-8"))
    by: dict[int, list[dict]] = {}
    for a in coco["annotations"]:
        by.setdefault(a["image_id"], []).append(a)

    bases = [img for img in coco["images"]
             if any(a["category_id"] == PALLET_ID for a in by.get(img["id"], []))]
    print(f"베이스 {len(bases)}장에서 박스 크롭 수집 중...")
    bank = crop_bank(coco, args.images)
    print(f"박스 크롭 {len(bank)}개")
    if not bank or not bases:
        print("재료 부족", file=sys.stderr)
        return 1

    args.out.mkdir(parents=True, exist_ok=True)
    out_images, out_anns = [], []
    ann_id = 1

    for n in range(args.count):
        base = rng.choice(bases)
        anns = by[base["id"]]
        pal = max((a["bbox"] for a in anns if a["category_id"] == PALLET_ID),
                  key=lambda b: b[2] * b[3])
        frame = cv2.imdecode(np.fromfile(str(args.images / base["file_name"]),
                                         dtype="uint8"), cv2.IMREAD_COLOR)
        if frame is None:
            continue
        H, W = frame.shape[:2]
        px, py, pw, ph = pal

        pasted: list[tuple[int, int, int, int]] = []
        # 파렛트 상단에서 시작해 한 줄씩 위로 쌓는다 (실제 적재처럼)
        rows = rng.randint(1, 3)
        row_base = py + ph * 0.15          # 파렛트 상판 근처
        for _ in range(rows):
            cursor = px + rng.uniform(0.0, 0.20) * pw
            row_end = px + pw * rng.uniform(0.80, 1.0)   # 파렛트 밖으로 안 나가게
            row_h = 0
            while cursor < row_end:
                crop = rng.choice(bank)
                bw = int(pw * rng.uniform(*BOX_W_RANGE))
                bh = int(bw * crop.shape[0] / max(crop.shape[1], 1))
                if bw < 30 or bh < 30 or bh > H * 0.6:
                    break
                x0, y0 = int(cursor), int(row_base - bh)
                # 파렛트 오른쪽 끝을 넘기면 그 줄은 끝낸다
                if x0 + bw > min(W, px + pw + 0.05 * pw):
                    break
                if x0 < 0 or y0 < 0 or y0 + bh > H:
                    cursor += bw
                    continue
                patch = cv2.resize(crop, (bw, bh))
                frame[y0:y0 + bh, x0:x0 + bw] = patch
                pasted.append((x0, y0, bw, bh))
                row_h = max(row_h, bh)
                cursor += bw * rng.uniform(0.95, 1.05)
            if row_h == 0:
                break
            row_base -= row_h              # 다음 줄은 그 위에

        if not pasted:
            continue

        name = f"aug_{n:04d}.jpg"
        ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 92])
        if not ok:
            continue
        buf.tofile(str(args.out / name))

        img_id = 900000 + n
        out_images.append({"id": img_id, "file_name": name, "width": W, "height": H})
        # 파렛트: amodal이라 그대로 유효
        out_anns.append({"id": ann_id, "image_id": img_id, "category_id": PALLET_ID,
                         "bbox": [round(v, 1) for v in pal],
                         "area": round(pw * ph, 1), "iscrowd": 0, "source": "aug"})
        ann_id += 1
        # 합성한 박스
        for (bx, by_, bw, bh) in pasted:
            out_anns.append({"id": ann_id, "image_id": img_id, "category_id": BOX_ID,
                             "bbox": [bx, by_, bw, bh], "area": bw * bh,
                             "iscrowd": 0, "source": "aug"})
            ann_id += 1
        # 기존 박스: 많이 가려졌으면 버린다 (박스 라벨은 modal)
        for a in anns:
            if a["category_id"] != BOX_ID:
                continue
            if covered(a["bbox"], pasted) <= MAX_COVERED:
                out_anns.append({"id": ann_id, "image_id": img_id,
                                 "category_id": BOX_ID,
                                 "bbox": [round(v, 1) for v in a["bbox"]],
                                 "area": a.get("area", 0), "iscrowd": 0,
                                 "source": "aug_keep"})
                ann_id += 1

    out_json = args.out / "annotations.json"
    out_json.write_text(json.dumps(
        {"images": out_images, "annotations": out_anns,
         "categories": coco["categories"]}, ensure_ascii=False), encoding="utf-8")
    nb = sum(1 for a in out_anns if a["category_id"] == BOX_ID)
    npl = sum(1 for a in out_anns if a["category_id"] == PALLET_ID)
    print(f"\n생성 {len(out_images)}장 / box {nb} / pallet {npl}")
    print(f"  이미지 → {args.out}\n  라벨   → {out_json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
