"""라벨 검수 CLI (FR-101-2).

병합된 COCO를 훑어 품질 지표를 내고, 명백히 못 쓸 어노테이션만 걸러낸다.

실측(2026-07-20, 박스 221,777개) 결과 라벨 품질은 전반적으로 양호했다.
전체 프레임을 덮는 박스 1,133개는 96.5%가 '박스 클로즈업 한 장' 이라 정상이고,
초소형·극단 종횡비·중복도 각각 0.5% 미만이었다. 그래서 기본값은 **명백한
쓰레기만** 제거하도록 보수적으로 잡았다. 더 엄격하게 보려면 옵션을 조인다.

사용법:
    python -m dataset.review --input data/processed/box_coco.json
    python -m dataset.review --input data/processed/box_coco.json \
        --output data/processed/box_coco_clean.json
"""

from __future__ import annotations

import argparse
import collections
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

# 기본 임계값 — 실측 기준 '명백한 쓰레기'만 걸린다.
DEFAULT_MIN_AREA = 16.0      # 4x4px 미만. 실측 6개
DEFAULT_MAX_ASPECT = 20.0    # 20:1 초과. 실측 45개
DEFAULT_DUP_IOU = 0.9        # 사실상 같은 박스. 실측 109개


@dataclass
class ReviewResult:
    removed: collections.Counter = field(default_factory=collections.Counter)
    stats: dict = field(default_factory=dict)

    @property
    def total_removed(self) -> int:
        return sum(self.removed.values())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="라벨 검수 및 명백한 불량 제거")
    parser.add_argument("--input", type=Path, required=True, help="병합된 COCO json")
    parser.add_argument("--output", type=Path, help="지정하면 정제 결과를 저장한다")
    parser.add_argument("--min-area", type=float, default=DEFAULT_MIN_AREA,
                        help=f"이 면적(px) 미만 제거 (기본 {DEFAULT_MIN_AREA:g})")
    parser.add_argument("--max-aspect", type=float, default=DEFAULT_MAX_ASPECT,
                        help=f"종횡비가 이 값을 넘으면 제거 (기본 {DEFAULT_MAX_ASPECT:g})")
    parser.add_argument("--dup-iou", type=float, default=DEFAULT_DUP_IOU,
                        help=f"IoU가 이 값 이상인 중복 박스는 하나만 남긴다 (기본 {DEFAULT_DUP_IOU})")
    args = parser.parse_args(argv)

    data = json.loads(args.input.read_text(encoding="utf-8"))
    print(f"입력: {args.input}")
    print(f"  이미지 {len(data['images']):,}장 / 박스 {len(data['annotations']):,}개\n")

    _print_report(data)

    result = review(data, args.min_area, args.max_aspect, args.dup_iou)
    print("\n=== 제거 대상 ===")
    if not result.total_removed:
        print("  없음")
    for reason, n in result.removed.most_common():
        print(f"  {reason}: {n:,}")
    print(f"  합계 {result.total_removed:,} "
          f"({result.total_removed / max(result.stats['before_annotations'], 1) * 100:.2f}%)")

    if not args.output:
        print("\n--output 미지정 — 저장하지 않았습니다.")
        return 0

    empty = _drop_empty_images(data)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    print(f"\n어노테이션이 없어진 이미지 {empty:,}장 제거")
    print(f"저장 → {args.output}")
    print(f"  이미지 {len(data['images']):,}장 / 박스 {len(data['annotations']):,}개")
    return 0


def review(data: dict, min_area: float, max_aspect: float, dup_iou: float) -> ReviewResult:
    """불량 어노테이션을 data에서 직접 제거하고 제거 사유를 집계한다."""
    result = ReviewResult()
    result.stats["before_annotations"] = len(data["annotations"])

    sizes = {i["id"]: (i["width"], i["height"]) for i in data["images"]}
    kept: list[dict] = []
    by_image: dict[int, list[dict]] = collections.defaultdict(list)

    for ann in data["annotations"]:
        _, _, w, h = ann["bbox"]
        if w <= 0 or h <= 0 or ann["area"] < min_area:
            result.removed[f"면적 {min_area:g}px 미만"] += 1
            continue
        if max(w / h, h / w) > max_aspect:
            result.removed[f"종횡비 {max_aspect:g}:1 초과"] += 1
            continue
        if ann["image_id"] not in sizes:
            result.removed["이미지 없음"] += 1
            continue

        # 같은 이미지 안에서 사실상 동일한 박스는 하나만 남긴다
        duplicate = any(
            _iou(ann["bbox"], other["bbox"]) >= dup_iou for other in by_image[ann["image_id"]]
        )
        if duplicate:
            result.removed[f"IoU {dup_iou} 이상 중복"] += 1
            continue

        by_image[ann["image_id"]].append(ann)
        kept.append(ann)

    data["annotations"] = kept
    return result


def _print_report(data: dict) -> None:
    """필터와 무관하게, 데이터 성격을 파악하기 위한 지표."""
    anns = data["annotations"]
    if not anns:
        return
    sizes = {i["id"]: (i["width"], i["height"]) for i in data["images"]}

    areas = sorted(a["area"] for a in anns)
    print("=== 박스 면적 분위수 (px) ===")
    print("  " + "  ".join(
        f"p{q}={areas[min(int(len(areas) * q / 100), len(areas) - 1)]:,.0f}"
        for q in (1, 25, 50, 75, 99)
    ))

    per_image = collections.Counter(a["image_id"] for a in anns)
    counts = sorted(per_image.values())
    print("\n=== 이미지당 박스 수 ===")
    print("  " + "  ".join(
        f"p{q}={counts[min(int(len(counts) * q / 100), len(counts) - 1)]}"
        for q in (50, 90, 99)
    ) + f"  최대={counts[-1]}")

    full = sum(
        1 for a in anns
        if a["image_id"] in sizes
        and a["area"] / (sizes[a["image_id"]][0] * sizes[a["image_id"]][1]) > 0.95
    )
    solo = sum(
        1 for a in anns
        if a["image_id"] in sizes and per_image[a["image_id"]] == 1
        and a["area"] / (sizes[a["image_id"]][0] * sizes[a["image_id"]][1]) > 0.95
    )
    print(f"\n전체 프레임(95% 이상) 박스: {full:,}"
          f"  이 중 이미지에 박스가 하나뿐: {solo:,} (클로즈업이면 정상)")

    by_source = collections.defaultdict(int)
    img_source = {i["id"]: i["file_name"].split("/")[0] for i in data["images"]}
    for a in anns:
        by_source[img_source.get(a["image_id"], "?")] += 1
    print("\n=== 소스별 박스 ===")
    for s, n in sorted(by_source.items(), key=lambda kv: -kv[1]):
        print(f"  {s:<28}{n:>9,}")


def _drop_empty_images(data: dict) -> int:
    annotated = {a["image_id"] for a in data["annotations"]}
    before = len(data["images"])
    data["images"] = [i for i in data["images"] if i["id"] in annotated]
    return before - len(data["images"])


def _iou(a: list[float], b: list[float]) -> float:
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    x1, y1 = max(ax, bx), max(ay, by)
    x2, y2 = min(ax + aw, bx + bw), min(ay + ah, by + bh)
    iw, ih = max(0.0, x2 - x1), max(0.0, y2 - y1)
    inter = iw * ih
    union = aw * ah + bw * bh - inter
    return inter / union if union > 0 else 0.0


if __name__ == "__main__":
    sys.exit(main())
