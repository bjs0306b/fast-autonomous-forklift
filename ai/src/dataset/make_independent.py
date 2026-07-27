"""독립 평가 서브셋 생성 — 라벨을 **비운 채로** 30장을 뽑는다.

기존 평가셋(eval_20260727_clean.json)은 프리라벨을 exp7이 만들었다. 그 정답으로 exp7을
채점하면 mAP가 부풀려진다(실측 99.5%). 그래서 사람이 맨눈으로 처음부터 그린 라벨이
필요하고, 이 스크립트는 그 작업의 빈 캔버스를 만든다.

**annotations를 비우는 게 핵심**이다. 프리라벨을 조금이라도 넣으면 오염이 되돌아온다.

image_id·file_name은 원본과 같게 유지한다 — 채점할 때 같은 프레임끼리 맞춰야 하고,
나중에 "모델 프리라벨 vs 사람 독립 라벨"을 직접 비교할 수도 있어야 하기 때문.

박스 개수 계층(0 / 1~2 / 3~4 / 5개+)별로 등간격 추출한다. 무작위로 뽑으면 연속 촬영된
같은 배치에 쏠린다.

    python -m dataset.make_independent --coco data/processed/eval_20260727_clean.json \
        --out data/processed/eval_20260727_independent.json
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

BOX_ID = 1

# 계층별 뽑을 장수 — 전체 분포에 대체로 비례하되 네거티브(0개)는 소수만.
# 네거티브는 정답이 "없음"이라 채점 기여가 오탐 확인뿐이어서 많이 넣을 이유가 없다.
STRATA_TARGET = {0: 3, 1: 3, 3: 12, 5: 12}
STRATA_LABEL = {0: "0개", 1: "1~2개", 3: "3~4개", 5: "5개+"}


def stratum(n_boxes: int) -> int:
    if n_boxes == 0:
        return 0
    if n_boxes <= 2:
        return 1
    if n_boxes <= 4:
        return 3
    return 5


def frame_no(file_name: str) -> str:
    return file_name.rsplit("_", 1)[-1].split(".")[0]


def pick(coco: dict) -> list[dict]:
    counts: dict[int, int] = defaultdict(int)
    for a in coco["annotations"]:
        if a["category_id"] == BOX_ID:
            counts[a["image_id"]] += 1

    buckets: dict[int, list[dict]] = defaultdict(list)
    for img in coco["images"]:
        buckets[stratum(counts.get(img["id"], 0))].append(img)

    picked: list[dict] = []
    for key in sorted(buckets):
        pool = sorted(buckets[key], key=lambda im: im["file_name"])
        want = min(STRATA_TARGET.get(key, 0), len(pool))
        if want == 0:
            continue
        step = max(1, len(pool) // want)
        chosen = pool[::step][:want]
        picked += chosen
        nums = " ".join(frame_no(im["file_name"]) for im in chosen)
        print(f"  {STRATA_LABEL[key]:6s} 풀 {len(pool):3d}장 → {want:2d}장: {nums}")
    return sorted(picked, key=lambda im: im["file_name"])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="독립 평가 서브셋(빈 라벨) 생성")
    parser.add_argument("--coco", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)

    coco = json.loads(args.coco.read_text(encoding="utf-8"))
    print(f"원본 {len(coco['images'])}장 / 라벨 {len(coco['annotations'])}개")
    images = pick(coco)

    out = {
        "info": {
            "description": "리그 평가셋 독립 라벨 서브셋 — 사람이 맨눈으로 그린 정답",
            "note": "프리라벨 없음(exp7 오염 차단). 라벨은 review_labels.py로 직접 그린다.",
        },
        "images": images,
        "annotations": [],
        "categories": coco["categories"],
    }
    args.out.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    print(f"\n{len(images)}장 / 라벨 0개 → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
