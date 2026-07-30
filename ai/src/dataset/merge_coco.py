"""두 COCO를 파일명 기준으로 합친다 — 사람이 그린 라벨 + 자동 프리라벨.

온보드 144에서 쓰는 배분: **사람이 `pallet`·`hole`을, exp8 프리라벨이 `box`를** 맡는다.
게이트(G1~G3)가 걸린 클래스에 사람 손을 쓰고, 안 걸린 `box`는 자동으로 채운다.

    python -m dataset.merge_coco \
        --base data/labels/onboard_cvat_pallet_hole.json \
        --add data/processed/onboard_box_prelabel.json \
        --out data/labels/onboard_cvat_pass1.json

**base가 프레임 집합을 정한다.** 사람이 라벨한 장수가 정답이고, 프리라벨에만 있는
프레임은 버린다 — 반대로 하면 사람이 안 본 프레임이 학습셋에 섞인다.

**category는 id가 아니라 이름으로 맞춘다.** CVAT은 태스크에 정의된 라벨 순서로
id를 매기므로, box를 그리지 않아도 라벨 목록에 남겨둬야 id가 어긋나지 않는다.
그 전제에 기대지 않으려고 이름으로 다시 매핑하고, 출력은 항상 ``--classes`` 순서
(기본 box=1·pallet=2·hole=3)로 낸다.

**충돌은 base가 이긴다.** 어떤 프레임에 사람이 이미 `box`를 그렸다면 그 프레임의
프리라벨 box는 버린다. 사람 판단이 자동보다 우선이고, 겹쳐 넣으면 같은 화물에
bbox가 두 개 생긴다.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

DEFAULT_CLASSES = ["box", "pallet", "hole"]


def _by_name(coco: dict) -> dict[int, str]:
    return {c["id"]: c["name"] for c in coco["categories"]}


def merge(base: dict, add: dict, classes: list[str],
          add_only: set[str] | None = None) -> tuple[dict, dict, list[str]]:
    """base에 add의 어노테이션을 얹는다. 반환: (COCO, 통계, 경고)."""
    warn: list[str] = []
    cat_id = {name: i + 1 for i, name in enumerate(classes)}
    base_cat, add_cat = _by_name(base), _by_name(add)

    for src, table in (("base", base_cat), ("add", add_cat)):
        unknown = set(table.values()) - set(classes)
        if unknown:
            warn.append(f"{src}에 모르는 클래스가 있어 버린다: {sorted(unknown)}")

    base_anns = defaultdict(list)
    for x in base["annotations"]:
        base_anns[x["image_id"]].append(x)
    add_anns = defaultdict(list)
    for x in add["annotations"]:
        add_anns[x["image_id"]].append(x)

    add_img_by_name = {im["file_name"]: im for im in add["images"]}
    extra = set(add_img_by_name) - {im["file_name"] for im in base["images"]}
    if extra:
        warn.append(f"프리라벨에만 있는 프레임 {len(extra)}장은 버린다 "
                    f"(base가 프레임 집합을 정한다)")

    out_images, out_anns = [], []
    next_img = next_ann = 1
    added = 0
    conflicts: list[str] = []

    for im in base["images"]:
        out_images.append({"id": next_img, "file_name": im["file_name"],
                           "width": im["width"], "height": im["height"]})

        kept_names = set()
        for x in base_anns.get(im["id"], []):
            name = base_cat.get(x["category_id"])
            if name not in cat_id:
                continue
            y = dict(x)
            y["id"], y["image_id"], y["category_id"] = next_ann, next_img, cat_id[name]
            out_anns.append(y)
            kept_names.add(name)
            next_ann += 1

        src = add_img_by_name.get(im["file_name"])
        if src is not None:
            for x in add_anns.get(src["id"], []):
                name = add_cat.get(x["category_id"])
                if name not in cat_id:
                    continue
                if add_only is not None and name not in add_only:
                    continue
                if name in kept_names:
                    # 사람이 이미 그린 클래스다 — 자동 라벨을 얹지 않는다.
                    conflicts.append(f"{im['file_name']}:{name}")
                    continue
                y = dict(x)
                y["id"], y["image_id"], y["category_id"] = next_ann, next_img, cat_id[name]
                # prelabel의 ``score``는 남겨둔다 — box 검수 때 낮은 것부터 보면 된다.
                out_anns.append(y)
                added += 1
                next_ann += 1
        next_img += 1

    if conflicts:
        uniq = sorted({c.split(":")[1] for c in conflicts})
        frames = len({c.split(":")[0] for c in conflicts})
        warn.append(f"사람 라벨과 겹쳐 버린 프리라벨 어노 {len(conflicts)}개 "
                    f"({frames}장, 클래스: {', '.join(uniq)}) — base 우선")

    per_class: dict[str, int] = defaultdict(int)
    id_name = {v: k for k, v in cat_id.items()}
    for x in out_anns:
        per_class[id_name[x["category_id"]]] += 1
    with_ann = {x["image_id"] for x in out_anns}

    out = {
        "images": out_images,
        "annotations": out_anns,
        "categories": [{"id": cat_id[n], "name": n} for n in classes],
    }
    stats = {
        "images": len(out_images),
        "annotations": len(out_anns),
        "from_add": added,
        "conflicts": len(conflicts),
        "empty": sum(1 for im in out_images if im["id"] not in with_ann),
        "per_class": dict(per_class),
    }
    return out, stats, warn


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="사람 라벨 + 프리라벨 병합")
    ap.add_argument("--base", type=Path, required=True, help="사람이 그린 COCO (기준)")
    ap.add_argument("--add", type=Path, required=True, help="얹을 프리라벨 COCO")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--classes", default=",".join(DEFAULT_CLASSES),
                    help="출력 클래스 순서 (기본 box,pallet,hole)")
    ap.add_argument("--add-only", help="프리라벨에서 가져올 클래스만 (예: box)")
    a = ap.parse_args(argv)

    classes = [c.strip() for c in a.classes.split(",")]
    add_only = {c.strip() for c in a.add_only.split(",")} if a.add_only else None

    base = json.loads(a.base.read_text(encoding="utf-8"))
    add = json.loads(a.add.read_text(encoding="utf-8"))
    out, stats, warn = merge(base, add, classes, add_only)

    for w in warn:
        print("⚠️ " + w)

    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")

    print(f"이미지 {stats['images']}장 / 어노 {stats['annotations']}개 → {a.out}")
    print(f"  프리라벨에서 가져온 어노 {stats['from_add']}개")
    for c in classes:
        n = stats["per_class"].get(c, 0)
        print(f"  {c}: {n}  (장당 {n / max(stats['images'], 1):.2f})")
    print(f"  라벨 없는 프레임 {stats['empty']}장")
    if not stats["per_class"].get("hole"):
        print("⚠️ hole이 0개다 — 사람 라벨(base)이 맞는 파일인지 확인할 것.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
