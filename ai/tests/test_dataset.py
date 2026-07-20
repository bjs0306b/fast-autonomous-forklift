"""데이터셋 변환 로직 테스트 (FR-101-1)."""

from __future__ import annotations

import json

import pytest

from dataset.coco import BOX_CATEGORY_ID, BuildStats, CocoBuilder
from dataset.sources import convert_coco, convert_sku110k


@pytest.fixture
def builder() -> CocoBuilder:
    return CocoBuilder()


def test_이미지_id는_1부터_새로_발급된다(builder: CocoBuilder) -> None:
    stats = BuildStats()
    assert builder.add_image("a/1.jpg", 640, 480, stats) == 1
    assert builder.add_image("a/2.jpg", 640, 480, stats) == 2
    assert stats.images == 2


def test_같은_파일명은_한_번만_등록된다(builder: CocoBuilder) -> None:
    stats = BuildStats()
    builder.add_image("a/1.jpg", 640, 480, stats)
    assert builder.add_image("a/1.jpg", 640, 480, stats) is None
    assert stats.skipped_images == 1
    assert builder.num_images == 1


def test_경계를_넘는_bbox는_이미지_안으로_잘린다(builder: CocoBuilder) -> None:
    stats = BuildStats()
    image_id = builder.add_image("a/1.jpg", 100, 100, stats)
    builder.add_annotation(image_id, (90, 90, 50, 50), stats)

    bbox = builder.to_dict()["annotations"][0]["bbox"]
    assert bbox == [90, 90, 10, 10]


def test_이미지_밖의_bbox는_버려진다(builder: CocoBuilder) -> None:
    stats = BuildStats()
    image_id = builder.add_image("a/1.jpg", 100, 100, stats)

    assert builder.add_annotation(image_id, (150, 150, 20, 20), stats) is False
    assert builder.add_annotation(image_id, (10, 10, 0, 20), stats) is False
    assert stats.skipped_annotations == 2
    assert builder.num_annotations == 0


def test_어노테이션_없는_이미지는_제거된다(builder: CocoBuilder) -> None:
    stats = BuildStats()
    keep = builder.add_image("a/keep.jpg", 100, 100, stats)
    builder.add_image("a/empty.jpg", 100, 100, stats)
    builder.add_annotation(keep, (10, 10, 20, 20), stats)

    assert builder.drop_empty_images() == 1
    assert [img["file_name"] for img in builder.to_dict()["images"]] == ["a/keep.jpg"]


def test_coco_소스는_지정한_카테고리만_박스로_변환한다(tmp_path, builder: CocoBuilder) -> None:
    source = tmp_path / "loco.json"
    source.write_text(
        json.dumps(
            {
                "categories": [
                    {"id": 7, "name": "small_load_carrier"},
                    {"id": 9, "name": "forklift"},
                ],
                "images": [{"id": 100, "file_name": "img.jpg", "width": 200, "height": 200}],
                "annotations": [
                    {"id": 1, "image_id": 100, "category_id": 7, "bbox": [10, 10, 30, 30]},
                    {"id": 2, "image_id": 100, "category_id": 9, "bbox": [50, 50, 40, 40]},
                ],
            }
        ),
        encoding="utf-8",
    )

    stats = convert_coco(builder, source, prefix="loco", keep_categories=["small_load_carrier"])

    assert stats.annotations == 1
    assert stats.reasons["대상 외 카테고리"] == 1
    result = builder.to_dict()
    assert result["images"][0]["file_name"] == "loco/img.jpg"
    assert result["annotations"][0]["category_id"] == BOX_CATEGORY_ID


def test_없는_카테고리를_지정하면_에러(tmp_path, builder: CocoBuilder) -> None:
    source = tmp_path / "loco.json"
    source.write_text(
        json.dumps({"categories": [{"id": 1, "name": "pallet"}], "images": [], "annotations": []}),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="없는 카테고리"):
        convert_coco(builder, source, prefix="loco", keep_categories=["typo_name"])


def test_sku110k_csv는_코너좌표를_wh로_바꾼다(tmp_path, builder: CocoBuilder) -> None:
    source = tmp_path / "ann.csv"
    source.write_text(
        "img1.jpg,10,20,60,80,object,640,480\nimg1.jpg,100,100,150,200,object,640,480\n",
        encoding="utf-8",
    )

    stats = convert_sku110k(builder, source, prefix="sku110k")

    assert (stats.images, stats.annotations) == (1, 2)
    assert builder.to_dict()["annotations"][0]["bbox"] == [10, 20, 50, 60]
