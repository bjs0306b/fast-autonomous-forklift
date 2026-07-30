"""프리라벨 생성 — 촬영본을 현 모델로 미리 라벨링해 수작업을 줄인다.

촬영(``station/shoot.py``) → **프리라벨(여기)** → CVAT에서 사람 수정 → COCO 내보내기
→ 학습셋 합류. 현 모델은 박스를 0.9대로 잡으므로 박스는 대부분 그대로 쓰고,
**파렛트는 사람이 그려 넣는다** (2026-07-24 실측: 검은 플라스틱 파렛트 미검출).

    python -m dataset.prelabel --images data/raw/rig/20260724 --out data/processed/rig_prelabel.json

카테고리 id는 학습셋과 같은 계보를 쓴다 — ``box``=1, ``pallet``=2
(``configs/datasets.yaml``의 classes 순서). 이게 어긋나면 병합 시 클래스가 뒤섞인다.

임계값은 추론 기본(0.5)보다 낮게 잡는다. 프리라벨은 **사람이 지우는 게 그리는 것보다
싸므로** 재현율 쪽으로 치우치는 편이 낫다.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402

from dataset.coco import DEFAULT_CLASSES, frame_number  # noqa: E402
from station.config import StationConfig  # noqa: E402
from station.detector import OnnxDetector  # noqa: E402

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}


def parse_ranges(spec: str) -> list[tuple[int, int]]:
    """``"1-316"`` 또는 ``"1-100,200-316"`` → [(1,316)] / [(1,100),(200,316)]."""
    out = []
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            lo, hi = part.split("-", 1)
            out.append((int(lo), int(hi)))
        else:
            n = int(part)
            out.append((n, n))
    return out


def in_ranges(n: int | None, ranges: list[tuple[int, int]]) -> bool:
    return n is not None and any(lo <= n <= hi for lo, hi in ranges)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="촬영본 프리라벨 (CVAT 반입용 COCO)")
    parser.add_argument("--images", type=Path, required=True, help="촬영 폴더")
    parser.add_argument("--out", type=Path, required=True, help="COCO json 저장 경로")
    parser.add_argument("--score", type=float, default=0.3,
                        help="프리라벨 임계값 (기본 0.3 — 추론 0.5보다 낮게)")
    parser.add_argument("--model", type=Path, help="ONNX 경로 (기본: config)")
    parser.add_argument("--only", help="남길 클래스 (쉼표 구분, 예: box). 나머지는 버린다")
    parser.add_argument("--frames", help="프레임 번호 범위 (예: 1-316). 밖은 추론도 안 한다")
    args = parser.parse_args(argv)

    keep = {c.strip() for c in args.only.split(",")} if args.only else None
    if keep and not keep <= set(DEFAULT_CLASSES):
        print(f"--only에 모르는 클래스가 있다: {keep - set(DEFAULT_CLASSES)} "
              f"(가능: {', '.join(DEFAULT_CLASSES)})", file=sys.stderr)
        return 1
    ranges = parse_ranges(args.frames) if args.frames else None

    cfg = StationConfig()
    all_files = sorted(p for p in args.images.iterdir()
                       if p.suffix.lower() in IMAGE_SUFFIXES)
    if ranges:
        files = [p for p in all_files if in_ranges(frame_number(p.name), ranges)]
        print(f"프레임 범위 {args.frames} → {len(files)}/{len(all_files)}장 "
              f"({len(all_files) - len(files)}장 제외)")
    else:
        files = all_files
    if keep:
        print(f"남길 클래스: {', '.join(sorted(keep))}")
    if not files:
        print(f"이미지가 없습니다: {args.images}", file=sys.stderr)
        return 1

    detector = OnnxDetector(
        args.model or cfg.model_path, cfg.input_size, args.score,
        cfg.class_names, cfg.norm_mean, cfg.norm_std,
    )

    images, annotations = [], []
    per_class = {name: 0 for name in DEFAULT_CLASSES}
    empty = 0

    for i, path in enumerate(files, start=1):
        frame = cv2.imread(str(path))
        if frame is None:
            print(f"  !! 읽기 실패: {path.name}", file=sys.stderr)
            continue
        h, w = frame.shape[:2]
        images.append({"id": i, "file_name": path.name, "width": w, "height": h})

        detections = detector.detect(frame)
        if keep is not None:
            detections = [d for d in detections if d.label in keep]
        if not detections:
            empty += 1
        for det in detections:
            b = det.box
            per_class[det.label] = per_class.get(det.label, 0) + 1
            annotations.append({
                "id": len(annotations) + 1,
                "image_id": i,
                "category_id": DEFAULT_CLASSES.index(det.label) + 1,
                "bbox": [round(b.x, 1), round(b.y, 1), round(b.w, 1), round(b.h, 1)],
                "area": round(b.w * b.h, 1),
                "iscrowd": 0,
                "score": round(det.score, 3),   # CVAT은 무시, 검수 정렬용으로 남긴다
            })
        if i % 25 == 0 or i == len(files):
            print(f"  {i}/{len(files)}")

    coco = {
        "images": images,
        "annotations": annotations,
        "categories": [{"id": idx + 1, "name": name}
                       for idx, name in enumerate(DEFAULT_CLASSES)],
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(coco, ensure_ascii=False), encoding="utf-8")

    print(f"\n이미지 {len(images)}장 / 어노테이션 {len(annotations)}개 → {args.out}")
    for name, count in per_class.items():
        if keep is None or name in keep:
            print(f"  {name}: {count}  (장당 {count / len(images):.2f})")
    print(f"  감지 0건인 이미지: {empty}장")
    # --only로 파렛트를 걸러낸 경우엔 아래 경고가 의미 없다(애초에 안 쓸 클래스다).
    if (keep is None or "pallet" in keep) and per_class.get("pallet", 0) < len(images) * 0.5:
        print("\n⚠️ 파렛트 프리라벨이 이미지 수의 절반에 못 미친다 — "
              "파렛트는 사람이 그려 넣어야 한다(예상된 결과).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
