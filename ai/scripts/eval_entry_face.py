"""진입면 선택 규칙 채점 — 라벨의 다면 프레임으로 후보를 비교한다 (S15P11A304-143).

`fork_align.choose_entry_face`가 왜 "인접 쌍 · 거리×**최소**폭"인지는 근거가 있어야
바꿀 수 있다. 이 스크립트가 그 근거를 재생산한다 — 추론이 필요 없다(라벨만 본다).

    # ai/ 에서
    python scripts/eval_entry_face.py \
        --labels data/labels/onboard_cvat_pallet_hole.json data/labels/onboard_eval.json

채점 대상은 **구멍이 3개 이상인 프레임**(두 면이 동시에 보이는 프레임)뿐이다. 2개짜리는
고를 것이 없어 어떤 규칙이든 맞는다.

⚠️ **정답은 사람이 그린 것이 아니라 "폭 상위 2개"라는 대리 기준이다.** 라벨에 "어느
면으로 진입하라"는 필드가 없기 때문이다(§3-⑥이 런타임 판단으로 미룬 이유이기도 하다).
45° 부근에서는 두 면의 개구가 실제로 비슷해 이 대리 기준이 임의로 갈린다 — 그래서
정답률만 보지 말고 **1등/2등 여유(마진)** 를 같이 본다. 규칙이 흔들리는 구간을
정답률은 못 보여주고 마진은 보여준다.
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from itertools import combinations
from pathlib import Path
from statistics import median

BBox = tuple[float, float, float, float]


def center_x(b: BBox) -> float:
    return b[0] + b[2] / 2


def pair_score(a: BBox, b: BBox, width: str) -> float:
    span = abs(center_x(a) - center_x(b))
    w = min(a[2], b[2]) if width == "min" else (a[2] + b[2]) / 2
    return span * w


def candidates(holes: list[BBox], contiguous: bool):
    ordered = sorted(holes, key=center_x)
    if contiguous:
        return list(zip(ordered, ordered[1:]))
    return list(combinations(ordered, 2))


def ranked(holes: list[BBox], contiguous: bool, width: str):
    pairs = candidates(holes, contiguous)
    return sorted(pairs, key=lambda p: -pair_score(*p, width))


def reference(holes: list[BBox]) -> set[float]:
    """대리 정답 — 가장 넓게 보이는 개구 2개(= 가장 정면인 면)."""
    return {center_x(b) for b in sorted(holes, key=lambda b: -b[2])[:2]}


RULES = [
    ("전체 쌍 · 거리만", False, "span-only"),
    ("전체 쌍 · 거리×평균폭", False, "mean"),
    ("인접 쌍 · 거리×평균폭", True, "mean"),
    ("인접 쌍 · 거리×최소폭 (채택)", True, "min"),
]


def load(path: Path) -> dict[str, list[BBox]]:
    coco = json.loads(path.read_text(encoding="utf-8"))
    cats = {c["id"]: c["name"] for c in coco["categories"]}
    names = {im["id"]: im["file_name"] for im in coco["images"]}
    holes: dict[str, list[BBox]] = defaultdict(list)
    for a in coco["annotations"]:
        if cats.get(a["category_id"]) == "hole":
            holes[names[a["image_id"]]].append(tuple(a["bbox"]))
    return holes


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="진입면 선택 규칙 채점")
    ap.add_argument("--labels", type=Path, nargs="+", required=True,
                    help="COCO 라벨 JSON (여러 개 가능)")
    a = ap.parse_args(argv)

    for path in a.labels:
        holes = load(path)
        multi = {k: v for k, v in holes.items() if len(v) >= 3}
        print(f"\n=== {path.name} — 다면 프레임 {len(multi)} / 구멍 있는 프레임 {len(holes)} ===")
        if not multi:
            print("  다면 프레임이 없어 채점할 것이 없다.")
            continue

        for label, contiguous, width in RULES:
            if width == "span-only":
                pick = lambda hs: max(candidates(hs, contiguous),  # noqa: E731
                                      key=lambda p: abs(center_x(p[0]) - center_x(p[1])))
                margins = []
            else:
                pick = lambda hs: ranked(hs, contiguous, width)[0]  # noqa: E731
                margins = []
            ok = 0
            for hs in multi.values():
                chosen = pick(hs)
                if {center_x(chosen[0]), center_x(chosen[1])} == reference(hs):
                    ok += 1
                if width != "span-only":
                    r = ranked(hs, contiguous, width)
                    if len(r) > 1 and pair_score(*r[1], width) > 0:
                        margins.append(pair_score(*r[0], width) / pair_score(*r[1], width))
            m = (f"  마진 중앙값 {median(margins):.2f}배 · 최솟값 {min(margins):.2f}배"
                 if margins else "")
            print(f"  {label:28s} 대리정답 일치 {ok:3d}/{len(multi)}{m}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
