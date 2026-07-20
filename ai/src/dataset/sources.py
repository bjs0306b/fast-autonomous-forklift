"""공개 데이터셋 → 박스 단일 클래스 COCO 변환기.

지원 소스
- ``coco``    : 이미 COCO 포맷인 소스 (LOCO, Roboflow Universe 내보내기)
- ``sku110k`` : CSV 어노테이션 (image_name, x1, y1, x2, y2, class, width, height)
"""

from __future__ import annotations

import csv
import json
from pathlib import Path

from .coco import BuildStats, CocoBuilder


def convert_coco(
    builder: CocoBuilder,
    annotations: Path,
    prefix: str,
    keep_categories: list[str] | None = None,
) -> BuildStats:
    """COCO 포맷 소스를 읽어 박스 단일 클래스로 재매핑한다.

    ``keep_categories``가 비어 있으면 전체 카테고리를 박스로 본다
    (Roboflow cardboard box처럼 이미 단일 클래스인 경우).
    LOCO처럼 여러 클래스가 섞인 소스는 박스형 클래스만 골라 넘긴다.
    """
    stats = BuildStats()
    # Windows에서 내보낸 어노테이션에 BOM이 붙는 경우가 있어 utf-8-sig로 읽는다.
    with annotations.open(encoding="utf-8-sig") as f:
        raw = json.load(f)

    wanted = _resolve_categories(raw.get("categories", []), keep_categories, annotations)

    # 원본 image_id → 새 image_id
    id_map: dict[int, int] = {}
    for image in raw.get("images", []):
        new_id = builder.add_image(
            file_name=f"{prefix}/{image['file_name']}",
            width=int(image.get("width", 0)),
            height=int(image.get("height", 0)),
            stats=stats,
        )
        if new_id is not None:
            id_map[image["id"]] = new_id

    for ann in raw.get("annotations", []):
        if ann.get("category_id") not in wanted:
            stats.skip("annotation", "대상 외 카테고리")
            continue
        new_id = id_map.get(ann["image_id"])
        if new_id is None:
            stats.skip("annotation", "이미지 누락")
            continue
        builder.add_annotation(new_id, ann["bbox"], stats, iscrowd=int(ann.get("iscrowd", 0)))

    return stats


def convert_sku110k(builder: CocoBuilder, annotations: Path, prefix: str) -> BuildStats:
    """SKU-110K CSV를 COCO로 변환한다.

    CSV는 헤더가 없고 한 줄이 bbox 하나이며, 같은 이미지가 여러 줄에 걸쳐 나온다.
    좌표는 x1,y1,x2,y2(코너)라서 COCO의 x,y,w,h로 바꿔야 한다.
    """
    stats = BuildStats()
    image_ids: dict[str, int] = {}

    with annotations.open(encoding="utf-8-sig", newline="") as f:
        for row in csv.reader(f):
            if len(row) < 8:
                stats.skip("annotation", "CSV 열 부족")
                continue

            name, x1, y1, x2, y2, _cls, width, height = row[:8]
            image_id = image_ids.get(name)
            if image_id is None:
                image_id = builder.add_image(
                    file_name=f"{prefix}/{name}",
                    width=_to_int(width),
                    height=_to_int(height),
                    stats=stats,
                )
                if image_id is None:
                    continue
                image_ids[name] = image_id

            bbox = (_to_float(x1), _to_float(y1), _to_float(x2) - _to_float(x1),
                    _to_float(y2) - _to_float(y1))
            builder.add_annotation(image_id, bbox, stats)

    return stats


def _resolve_categories(
    categories: list[dict], keep: list[str] | None, source: Path
) -> set[int]:
    """이름으로 지정한 카테고리를 원본 category_id 집합으로 바꾼다."""
    if not keep:
        return {c["id"] for c in categories}

    by_name = {c["name"]: c["id"] for c in categories}
    missing = [name for name in keep if name not in by_name]
    if missing:
        raise ValueError(
            f"{source}에 없는 카테고리: {missing} (사용 가능: {sorted(by_name)})"
        )
    return {by_name[name] for name in keep}


def _to_int(value: str) -> int:
    try:
        return int(float(value))
    except ValueError:
        return 0


def _to_float(value: str) -> float:
    try:
        return float(value)
    except ValueError:
        return 0.0
