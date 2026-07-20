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
    class_map: dict[str, list[str] | str],
    path_key: str = "file_name",
    strip_path_prefix: str = "",
    group: str | None = None,
) -> BuildStats:
    """COCO 포맷 소스를 읽어 우리 클래스 체계로 재매핑한다.

    ``class_map``은 ``{우리 클래스: [원본 카테고리명, ...]}`` 형태다.
    값에 ``"*"``를 주면 다른 클래스가 가져가지 않은 나머지 전부를 뜻한다
    (Roboflow Carboard Box처럼 모든 카테고리가 같은 대상인 경우).

        class_map:
          box:    [small_load_carrier, stillage]
          pallet: [pallet]

    나열되지 않은 카테고리는 제외된다 — LOCO의 forklift·pallet_truck처럼
    장비에 해당하는 것들이다.

    ``path_key``는 이미지 경로로 쓸 필드다. LOCO는 ``file_name``이
    ``1583416214257,48.jpg`` 같은 타임스탬프 basename이라 subset을 합치면
    충돌하므로, 디렉터리까지 담긴 ``path``를 써야 한다.
    """
    stats = BuildStats()
    # Windows에서 내보낸 어노테이션에 BOM이 붙는 경우가 있어 utf-8-sig로 읽는다.
    with annotations.open(encoding="utf-8-sig") as f:
        raw = json.load(f)

    class_of = _resolve_class_map(raw.get("categories", []), class_map, annotations)

    # 원본 image_id → 새 image_id
    id_map: dict[int, int] = {}
    for image in raw.get("images", []):
        rel = _relative_path(image, path_key, strip_path_prefix)
        if rel is None:
            stats.skip("image", f"{path_key} 필드 없음")
            continue

        new_id = builder.add_image(
            file_name=f"{prefix}/{rel}",
            width=int(image.get("width", 0)),
            height=int(image.get("height", 0)),
            stats=stats,
            group=group,
        )
        if new_id is not None:
            id_map[image["id"]] = new_id

    for ann in raw.get("annotations", []):
        class_name = class_of.get(ann.get("category_id"))
        if class_name is None:
            stats.skip("annotation", "대상 외 카테고리")
            continue
        new_id = id_map.get(ann["image_id"])
        if new_id is None:
            stats.skip("annotation", "이미지 누락")
            continue
        builder.add_annotation(
            new_id, ann["bbox"], stats, class_name, iscrowd=int(ann.get("iscrowd", 0))
        )

    return stats


def convert_sku110k(
    builder: CocoBuilder,
    annotations: Path,
    prefix: str,
    class_name: str = "box",
    group: str | None = None,
) -> BuildStats:
    """SKU-110K CSV를 COCO로 변환한다.

    CSV는 헤더가 없고 한 줄이 bbox 하나이며, 같은 이미지가 여러 줄에 걸쳐 나온다.
    좌표는 x1,y1,x2,y2(코너)라서 COCO의 x,y,w,h로 바꿔야 한다.
    클래스 구분이 없는 소스라 전부 ``class_name``으로 넣는다.
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
                    group=group,
                )
                if image_id is None:
                    continue
                image_ids[name] = image_id

            bbox = (_to_float(x1), _to_float(y1), _to_float(x2) - _to_float(x1),
                    _to_float(y2) - _to_float(y1))
            builder.add_annotation(image_id, bbox, stats, class_name)

    return stats


def _relative_path(image: dict, path_key: str, strip_prefix: str) -> str | None:
    """이미지 레코드에서 이미지 루트 기준 상대경로를 뽑는다."""
    raw = image.get(path_key)
    if not raw:
        return None

    rel = str(raw).replace("\\", "/")
    if strip_prefix and rel.startswith(strip_prefix):
        rel = rel[len(strip_prefix) :]
    return rel.lstrip("/")


WILDCARD = "*"


def _resolve_class_map(
    categories: list[dict], class_map: dict[str, list[str] | str], source: Path
) -> dict[int, str]:
    """{우리 클래스: [원본 카테고리명]}을 {원본 category_id: 우리 클래스}로 바꾼다.

    값이 ``"*"``면 다른 클래스가 가져가지 않은 나머지 카테고리를 전부 맡는다.
    나열되지 않은 카테고리는 결과에 없으므로 변환 시 제외된다.
    """
    if not class_map:
        raise ValueError(f"{source}: class_map이 비어 있습니다")

    by_name = {c["name"]: c["id"] for c in categories}
    resolved: dict[int, str] = {}
    wildcard_class: str | None = None

    for class_name, wanted in class_map.items():
        if wanted == WILDCARD:
            if wildcard_class is not None:
                raise ValueError(
                    f"{source}: '*'는 한 클래스에만 쓸 수 있습니다 "
                    f"('{wildcard_class}'와 '{class_name}'에 중복)"
                )
            wildcard_class = class_name
            continue

        missing = [name for name in wanted if name not in by_name]
        if missing:
            raise ValueError(
                f"{source}에 없는 카테고리: {missing} (사용 가능: {sorted(by_name)})"
            )
        for name in wanted:
            category_id = by_name[name]
            if category_id in resolved:
                raise ValueError(
                    f"{source}: 카테고리 '{name}'이 '{resolved[category_id]}'와 "
                    f"'{class_name}' 양쪽에 지정됐습니다"
                )
            resolved[category_id] = class_name

    if wildcard_class is not None:
        for category_id in by_name.values():
            resolved.setdefault(category_id, wildcard_class)

    return resolved


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
