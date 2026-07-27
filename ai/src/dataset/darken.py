"""astraws 어둡게 변환 — 파란 플라스틱 파렛트를 검은 계열로 미는 광도 증강.

astraws는 기하(플라스틱 파렛트·바닥 로우앵글)는 리그와 맞는데 색(파랑)이 다르다.
채도를 죽이고 명도를 낮추면 무채색 어두운 파렛트가 되어 리그의 검은 파렛트에
근접한다 (2026-07-24, Jira 156 실험7 재료). bbox는 기하 불변이라 그대로 복사한다.

    python -m dataset.darken --src data/raw/astraws_pallet --out data/processed/astraws_dark

split(train/valid/test) 구조를 그대로 미러링해 datasets.yaml에 같은 방식으로 얹는다.
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

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}

# 채도·명도 배율 범위. 채도를 거의 죽여 무채색으로, 명도는 절반쯤으로.
SAT_RANGE = (0.08, 0.25)
VAL_RANGE = (0.45, 0.70)


def darken(frame: np.ndarray, rng: random.Random) -> np.ndarray:
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV).astype(np.float32)
    hsv[..., 1] *= rng.uniform(*SAT_RANGE)
    hsv[..., 2] *= rng.uniform(*VAL_RANGE)
    return cv2.cvtColor(hsv.clip(0, 255).astype(np.uint8), cv2.COLOR_HSV2BGR)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="astraws 어둡게 변환 (실험7 재료)")
    parser.add_argument("--src", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)

    rng = random.Random(args.seed)
    total = 0
    for ann_path in sorted(args.src.glob("*/_annotations.coco.json")):
        split = ann_path.parent.name
        out_dir = args.out / split
        out_dir.mkdir(parents=True, exist_ok=True)

        coco = json.loads(ann_path.read_text(encoding="utf-8"))
        kept = []
        for info in coco["images"]:
            src_img = ann_path.parent / info["file_name"]
            if src_img.suffix.lower() not in IMAGE_SUFFIXES:
                continue
            frame = cv2.imread(str(src_img))
            if frame is None:
                continue
            new_name = f"dark_{info['file_name']}"
            ok, buf = cv2.imencode(".jpg", darken(frame, rng),
                                   [cv2.IMWRITE_JPEG_QUALITY, 90])
            if not ok:
                continue
            buf.tofile(str(out_dir / new_name))
            info["file_name"] = new_name
            kept.append(info)
            total += 1
        coco["images"] = kept
        (out_dir / "_annotations.coco.json").write_text(
            json.dumps(coco, ensure_ascii=False), encoding="utf-8")
        print(f"  {split}: {len(kept)}장")

    print(f"총 {total}장 → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
