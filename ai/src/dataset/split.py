"""train/val 분할 CLI (FR-101-2).

**증강본이 train과 val에 갈라지지 않도록 원본 단위로 묶어 분할한다.**

Roboflow는 원본 1장을 최대 113장까지 증강해 내보낸다. 이미지 단위로 무작위
분할하면 같은 사진의 뒤집기·회전본이 양쪽에 들어가, 모델이 val에서 사실상
'본 적 있는 사진'을 맞히게 되어 mAP가 실제보다 부풀려진다. 목표가 mAP 92%인
만큼 이 착시는 치명적이다.

``image_identity()``로 같은 원본을 묶고(실측 21,609장 → 11,210그룹),
그룹 단위로 나눈다. 소스별 비율도 유지해 val이 특정 데이터셋에 쏠리지 않게 한다.

사용법:
    python -m dataset.split --input data/processed/box_coco.json --val-ratio 0.2
"""

from __future__ import annotations

import argparse
import collections
import json
import random
import sys
from pathlib import Path

from .coco import image_identity


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="원본 단위 train/val 분할")
    parser.add_argument("--input", type=Path, required=True, help="병합된 COCO json")
    parser.add_argument("--out-dir", type=Path, help="출력 디렉터리 (기본: 입력과 같은 위치)")
    parser.add_argument("--val-ratio", type=float, default=0.2, help="val 비율 (기본 0.2)")
    parser.add_argument("--seed", type=int, default=42, help="난수 시드 (기본 42)")
    args = parser.parse_args(argv)

    if not 0 < args.val_ratio < 1:
        parser.error("--val-ratio는 0과 1 사이여야 합니다")

    data = json.loads(args.input.read_text(encoding="utf-8"))
    out_dir = args.out_dir or args.input.parent
    print(f"입력: {args.input}")
    print(f"  이미지 {len(data['images']):,}장 / 박스 {len(data['annotations']):,}개")

    train_ids, val_ids, report = split_by_identity(data, args.val_ratio, args.seed)
    _print_report(report)

    out_dir.mkdir(parents=True, exist_ok=True)
    for name, ids in (("train", train_ids), ("val", val_ids)):
        subset = _subset(data, ids)
        path = out_dir / f"box_coco_{name}.json"
        path.write_text(json.dumps(subset, ensure_ascii=False), encoding="utf-8")
        print(f"\n저장 → {path}")
        print(f"  이미지 {len(subset['images']):,}장 / 박스 {len(subset['annotations']):,}개")

    return 0


def split_by_identity(
    data: dict, val_ratio: float, seed: int
) -> tuple[set[int], set[int], dict]:
    """원본 정체성으로 묶어 train/val로 나눈다.

    소스별로 따로 섞어 배분하므로, val에도 각 데이터셋이 같은 비율로 들어간다.
    한 그룹이 여러 소스에 걸치는 일은 변환 단계에서 이미 제거돼 발생하지 않지만,
    혹시 생기면 그룹의 첫 이미지 소스를 기준으로 삼는다.
    """
    groups: dict[str, list[dict]] = collections.defaultdict(list)
    for image in data["images"]:
        groups[image_identity(image["file_name"])].append(image)

    by_source: dict[str, list[str]] = collections.defaultdict(list)
    for key, images in groups.items():
        by_source[images[0]["file_name"].split("/")[0]].append(key)

    rng = random.Random(seed)
    train_keys: list[str] = []
    val_keys: list[str] = []
    for source in sorted(by_source):
        keys = sorted(by_source[source])  # 입력 순서에 흔들리지 않도록 정렬 후 섞는다
        rng.shuffle(keys)
        n_val = round(len(keys) * val_ratio)
        val_keys.extend(keys[:n_val])
        train_keys.extend(keys[n_val:])

    train_ids = {im["id"] for k in train_keys for im in groups[k]}
    val_ids = {im["id"] for k in val_keys for im in groups[k]}

    report = _build_report(data, groups, train_keys, val_keys, train_ids, val_ids)
    return train_ids, val_ids, report


def _build_report(data, groups, train_keys, val_keys, train_ids, val_ids) -> dict:
    per_image = collections.Counter(a["image_id"] for a in data["annotations"])
    source_of = {i["id"]: i["file_name"].split("/")[0] for i in data["images"]}

    per_source: dict[str, dict[str, int]] = collections.defaultdict(
        lambda: {"train": 0, "val": 0}
    )
    for image_id in train_ids:
        per_source[source_of[image_id]]["train"] += 1
    for image_id in val_ids:
        per_source[source_of[image_id]]["val"] += 1

    return {
        "groups": len(groups),
        "train_groups": len(train_keys),
        "val_groups": len(val_keys),
        "train_images": len(train_ids),
        "val_images": len(val_ids),
        "train_boxes": sum(per_image[i] for i in train_ids),
        "val_boxes": sum(per_image[i] for i in val_ids),
        "per_source": dict(per_source),
        # 누수 검증: 양쪽에 걸친 정체성이 있으면 안 된다
        "leaked": len({image_identity(i["file_name"]) for i in data["images"]
                       if i["id"] in train_ids}
                      & {image_identity(i["file_name"]) for i in data["images"]
                         if i["id"] in val_ids}),
    }


def _print_report(r: dict) -> None:
    print(f"\n원본 그룹 {r['groups']:,}개 → train {r['train_groups']:,} / val {r['val_groups']:,}")
    print(f"이미지  train {r['train_images']:,} / val {r['val_images']:,}")
    print(f"박스    train {r['train_boxes']:,} / val {r['val_boxes']:,}")

    print("\n소스별 이미지 (train / val):")
    for source, counts in sorted(r["per_source"].items()):
        total = counts["train"] + counts["val"]
        share = counts["val"] / total * 100 if total else 0
        print(f"  {source:<28}{counts['train']:>7,} / {counts['val']:>6,}  (val {share:.1f}%)")

    if r["leaked"]:
        print(f"\n[경고] train과 val에 같은 원본이 {r['leaked']:,}개 걸쳐 있습니다.",
              file=sys.stderr)
    else:
        print("\n[검증] train/val에 걸친 원본 0개 — 누수 없음")


def _subset(data: dict, image_ids: set[int]) -> dict:
    return {
        "info": data.get("info", {}),
        "licenses": data.get("licenses", []),
        "categories": data["categories"],
        "images": [i for i in data["images"] if i["id"] in image_ids],
        "annotations": [a for a in data["annotations"] if a["image_id"] in image_ids],
    }


if __name__ == "__main__":
    sys.exit(main())
