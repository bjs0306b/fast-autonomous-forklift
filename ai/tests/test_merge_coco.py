"""사람 라벨 + 프리라벨 병합 테스트 (S15P11A304-144)."""

from __future__ import annotations

from dataset.merge_coco import merge
from dataset.prelabel import in_ranges, parse_ranges

CLASSES = ["box", "pallet", "hole"]


def make(annotated: dict[str, list[str]], cats: list[str]) -> dict:
    """{파일명: [클래스명, ...]} + 카테고리 순서 → COCO."""
    cat_id = {n: i + 1 for i, n in enumerate(cats)}
    images, anns = [], []
    for i, (name, labels) in enumerate(annotated.items(), start=1):
        images.append({"id": i, "file_name": name, "width": 1280, "height": 800})
        for lab in labels:
            anns.append({"id": len(anns) + 1, "image_id": i,
                         "category_id": cat_id[lab], "bbox": [1, 2, 30, 8],
                         "area": 240, "iscrowd": 0})
    return {"images": images, "annotations": anns,
            "categories": [{"id": v, "name": k} for k, v in cat_id.items()]}


def test_사람_pallet_hole에_프리라벨_box를_얹는다() -> None:
    base = make({"a_0001.jpg": ["pallet", "hole", "hole"]}, ["pallet", "hole"])
    add = make({"a_0001.jpg": ["box"]}, ["box", "pallet"])

    out, stats, _ = merge(base, add, CLASSES, add_only={"box"})

    assert stats["per_class"] == {"pallet": 1, "hole": 2, "box": 1}
    assert stats["from_add"] == 1


def test_출력_category는_항상_box_pallet_hole_순서다() -> None:
    """base의 id가 pallet=1이어도 출력은 config와 맞는 순서여야 한다."""
    base = make({"a_0001.jpg": ["pallet"]}, ["pallet", "hole"])
    add = make({"a_0001.jpg": ["box"]}, ["box", "pallet"])

    out, _, _ = merge(base, add, CLASSES, add_only={"box"})

    assert out["categories"] == [{"id": 1, "name": "box"},
                                {"id": 2, "name": "pallet"},
                                {"id": 3, "name": "hole"}]
    by_id = {c["id"]: c["name"] for c in out["categories"]}
    assert [by_id[x["category_id"]] for x in out["annotations"]] == ["pallet", "box"]


def test_사람이_그린_클래스는_프리라벨이_덮지_않는다() -> None:
    base = make({"a_0001.jpg": ["pallet", "box"]}, CLASSES)
    add = make({"a_0001.jpg": ["box", "box"]}, ["box", "pallet"])

    out, stats, warn = merge(base, add, CLASSES, add_only={"box"})

    assert stats["per_class"]["box"] == 1          # 사람 것 하나만
    assert stats["conflicts"] == 2                 # 버린 프리라벨 어노 2개
    assert any("base 우선" in w for w in warn)


def test_base가_프레임_집합을_정한다() -> None:
    """프리라벨에만 있는 프레임은 버린다 — 사람이 안 본 프레임을 넣지 않는다."""
    base = make({"a_0001.jpg": ["pallet"]}, CLASSES)
    add = make({"a_0001.jpg": ["box"], "a_0099.jpg": ["box"]}, ["box", "pallet"])

    out, stats, warn = merge(base, add, CLASSES, add_only={"box"})

    assert stats["images"] == 1
    assert [im["file_name"] for im in out["images"]] == ["a_0001.jpg"]
    assert any("프리라벨에만 있는 프레임" in w for w in warn)


def test_add_only로_지정_안한_클래스는_안_들어온다() -> None:
    """프리라벨 pallet은 검출률 14%라 쓰지 않는다."""
    base = make({"a_0001.jpg": ["pallet"]}, CLASSES)
    add = make({"a_0001.jpg": ["box", "pallet"]}, ["box", "pallet"])

    _, stats, _ = merge(base, add, CLASSES, add_only={"box"})

    assert stats["per_class"] == {"pallet": 1, "box": 1}


def test_네거티브는_빈_프레임으로_남는다() -> None:
    base = make({"n_0317.jpg": []}, CLASSES)
    add = make({}, ["box", "pallet"])

    _, stats, _ = merge(base, add, CLASSES, add_only={"box"})

    assert stats["images"] == 1 and stats["annotations"] == 0
    assert stats["empty"] == 1


def test_id가_1부터_연속이다() -> None:
    base = make({"a_0001.jpg": ["pallet", "hole"], "a_0002.jpg": ["pallet"]}, CLASSES)
    add = make({"a_0001.jpg": ["box"], "a_0002.jpg": ["box"]}, ["box", "pallet"])

    out, _, _ = merge(base, add, CLASSES, add_only={"box"})

    assert [im["id"] for im in out["images"]] == [1, 2]
    assert [x["id"] for x in out["annotations"]] == [1, 2, 3, 4, 5]


# --- 프레임 범위 파싱 (prelabel --frames) ---

def test_프레임_범위_파싱() -> None:
    assert parse_ranges("1-316") == [(1, 316)]
    assert parse_ranges("1-100,200-316") == [(1, 100), (200, 316)]
    assert parse_ranges("42") == [(42, 42)]


def test_네거티브_구간이_범위에서_빠진다() -> None:
    r = parse_ranges("1-316")
    assert in_ranges(316, r)
    assert not in_ranges(317, r)      # 구간 8 시작
    assert not in_ranges(None, r)
