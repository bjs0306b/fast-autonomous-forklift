"""train/val/test 분할 CLI (FR-101-2).

**증강본이 여러 세트에 갈라지지 않도록 원본 단위로 묶어 분할한다.**

Roboflow는 원본 1장을 최대 113장까지 증강해 내보낸다. 이미지 단위로 무작위
분할하면 같은 사진의 뒤집기·회전본이 양쪽에 들어가, 모델이 val에서 사실상
'본 적 있는 사진'을 맞히게 되어 mAP가 실제보다 부풀려진다. 목표가 mAP 92%인
만큼 이 착시는 치명적이다.

``image_identity()``로 같은 원본을 묶고(실측 21,607장 → 11,208그룹),
그룹 단위로 나눈다. 소스별 비율도 유지해 특정 데이터셋에 쏠리지 않게 한다.

**test를 따로 두는 이유**: val은 학습(FR-101-3)·양자화(FR-101-4)에서 하이퍼
파라미터와 모델을 고르며 반복해 들여다보게 된다. 그러면 모델이 아니라 *우리의
선택*이 val에 과적합해 val 점수가 낙관적으로 편향된다. test는 마지막 보고
직전에 한 번만 열어 최종 수치로 쓴다.

⚠️ 이 test는 어디까지나 **공개 데이터셋 기준**이다. 실제 인식 대상은 미니어처
환경의 종이박스라 조명·배경·스케일·카메라가 모두 다르다. 최종 인수 판단은
우리 환경에서 직접 찍어 라벨링한 평가셋으로 한다 — **그 평가셋은 이미 있다:**

- 스테이션: 리그 평가셋(독립 라벨). mAP@0.5 0.9898 — ``docs/ai/rig-eval-map.md``
- 온보드: ``data/labels/onboard_eval.json`` 150장 · 어노테이션 326개(다른 날 촬영)

즉 **여기 test 점수로 인수를 판단하지 않는다.** 이 분할은 공개 데이터 학습 과정의
과적합 감시용이다. (종전에 "우리 평가셋은 아직 없다"고 적혀 있었는데, 이런 낡은
문장 하나가 이미 끝낸 일을 다시 하게 만든 전례가 있다 — 2026-08-03.)

사용법:
    python -m dataset.split --input data/processed/box_coco_clean.json \
        --val-ratio 0.15 --test-ratio 0.15
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
    parser = argparse.ArgumentParser(description="원본 단위 train/val/test 분할")
    parser.add_argument("--input", type=Path, required=True, help="병합된 COCO json")
    parser.add_argument("--out-dir", type=Path, help="출력 디렉터리 (기본: 입력과 같은 위치)")
    parser.add_argument("--val-ratio", type=float, default=0.15, help="val 비율 (기본 0.15)")
    parser.add_argument("--test-ratio", type=float, default=0.15,
                        help="test 비율 (기본 0.15). 0이면 test를 만들지 않는다")
    parser.add_argument("--seed", type=int, default=42, help="난수 시드 (기본 42)")
    args = parser.parse_args(argv)

    if not 0 < args.val_ratio < 1:
        parser.error("--val-ratio는 0과 1 사이여야 합니다")
    if not 0 <= args.test_ratio < 1:
        parser.error("--test-ratio는 0 이상 1 미만이어야 합니다")
    if args.val_ratio + args.test_ratio >= 1:
        parser.error("val과 test 비율의 합이 1 이상입니다 — train이 남지 않습니다")

    data = json.loads(args.input.read_text(encoding="utf-8"))
    out_dir = args.out_dir or args.input.parent
    print(f"입력: {args.input}")
    print(f"  이미지 {len(data['images']):,}장 / 박스 {len(data['annotations']):,}개")

    splits, report = split_by_identity(data, args.val_ratio, args.test_ratio, args.seed)
    _print_report(report)

    out_dir.mkdir(parents=True, exist_ok=True)
    for name, ids in splits.items():
        if not ids:
            continue
        subset = _subset(data, ids)
        path = out_dir / f"box_coco_{name}.json"
        path.write_text(json.dumps(subset, ensure_ascii=False), encoding="utf-8")
        print(f"\n저장 → {path}")
        print(f"  이미지 {len(subset['images']):,}장 / 박스 {len(subset['annotations']):,}개")

    if splits.get("test"):
        print("\n⚠️ test는 최종 보고 직전에 한 번만 연다. 학습·튜닝 중에는 val만 본다.")
    return 0


def split_by_identity(
    data: dict, val_ratio: float, test_ratio: float, seed: int
) -> tuple[dict[str, set[int]], dict]:
    """원본 정체성으로 묶어 train/val/test로 나눈다.

    소스별로 따로 섞어 배분하므로, 각 세트에 데이터셋이 같은 비율로 들어간다.
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
    keys_by_split: dict[str, list[str]] = {"train": [], "val": [], "test": []}
    for source in sorted(by_source):
        keys = sorted(by_source[source])  # 입력 순서에 흔들리지 않도록 정렬 후 섞는다
        rng.shuffle(keys)
        n_val = round(len(keys) * val_ratio)
        n_test = round(len(keys) * test_ratio)
        keys_by_split["val"].extend(keys[:n_val])
        keys_by_split["test"].extend(keys[n_val:n_val + n_test])
        keys_by_split["train"].extend(keys[n_val + n_test:])

    splits = {
        name: {im["id"] for k in keys for im in groups[k]}
        for name, keys in keys_by_split.items()
    }
    report = _build_report(data, groups, keys_by_split, splits)
    return splits, report


SPLIT_NAMES = ("train", "val", "test")


def _build_report(data, groups, keys_by_split, splits) -> dict:
    per_image = collections.Counter(a["image_id"] for a in data["annotations"])
    source_of = {i["id"]: i["file_name"].split("/")[0] for i in data["images"]}
    identity_of = {i["id"]: image_identity(i["file_name"]) for i in data["images"]}

    per_source: dict[str, dict[str, int]] = collections.defaultdict(
        lambda: dict.fromkeys(SPLIT_NAMES, 0)
    )
    for name in SPLIT_NAMES:
        for image_id in splits[name]:
            per_source[source_of[image_id]][name] += 1

    # 누수 검증: 두 세트에 걸친 정체성이 하나라도 있으면 안 된다
    identities = {
        name: {identity_of[i] for i in splits[name]} for name in SPLIT_NAMES
    }
    leaked = {}
    for i, a in enumerate(SPLIT_NAMES):
        for b in SPLIT_NAMES[i + 1:]:
            overlap = len(identities[a] & identities[b])
            if overlap:
                leaked[f"{a}∩{b}"] = overlap

    return {
        "groups": len(groups),
        "counts": {
            name: {
                "groups": len(keys_by_split[name]),
                "images": len(splits[name]),
                "boxes": sum(per_image[i] for i in splits[name]),
            }
            for name in SPLIT_NAMES
        },
        "per_source": dict(per_source),
        "leaked": leaked,
    }


def _print_report(r: dict) -> None:
    active = [n for n in SPLIT_NAMES if r["counts"][n]["images"]]
    print(f"\n원본 그룹 {r['groups']:,}개")
    print(f"\n{'':<8}{'그룹':>9}{'이미지':>10}{'박스':>12}")
    for name in active:
        c = r["counts"][name]
        print(f"  {name:<6}{c['groups']:>9,}{c['images']:>10,}{c['boxes']:>12,}")

    print(f"\n소스별 이미지 ({' / '.join(active)}):")
    for source, counts in sorted(r["per_source"].items()):
        total = sum(counts[n] for n in active)
        cells = " / ".join(f"{counts[n]:>6,}" for n in active)
        shares = " ".join(
            f"{n} {counts[n] / total * 100:.1f}%" for n in active if n != "train"
        ) if total else ""
        print(f"  {source:<28}{cells}   ({shares})")

    if r["leaked"]:
        for pair, n in r["leaked"].items():
            print(f"\n[경고] {pair}에 같은 원본이 {n:,}개 걸쳐 있습니다.", file=sys.stderr)
    else:
        print("\n[검증] 세트 간에 걸친 원본 0개 — 누수 없음")


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
