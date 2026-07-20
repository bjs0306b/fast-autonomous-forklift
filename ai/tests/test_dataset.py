"""데이터셋 변환 로직 테스트 (FR-101-1)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from dataset.coco import BuildStats, CocoBuilder, image_identity
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
    builder.add_annotation(image_id, (90, 90, 50, 50), stats, "box")

    bbox = builder.to_dict()["annotations"][0]["bbox"]
    assert bbox == [90, 90, 10, 10]


def test_이미지_밖의_bbox는_버려진다(builder: CocoBuilder) -> None:
    stats = BuildStats()
    image_id = builder.add_image("a/1.jpg", 100, 100, stats)

    assert builder.add_annotation(image_id, (150, 150, 20, 20), stats, "box") is False
    assert builder.add_annotation(image_id, (10, 10, 0, 20), stats, "box") is False
    assert stats.skipped_annotations == 2
    assert builder.num_annotations == 0


def test_어노테이션_없는_이미지는_제거된다(builder: CocoBuilder) -> None:
    stats = BuildStats()
    keep = builder.add_image("a/keep.jpg", 100, 100, stats)
    builder.add_image("a/empty.jpg", 100, 100, stats)
    builder.add_annotation(keep, (10, 10, 20, 20), stats, "box")

    assert builder.drop_empty_images() == 1
    assert [img["file_name"] for img in builder.to_dict()["images"]] == ["a/keep.jpg"]


@pytest.mark.parametrize(
    ("file_name", "expected"),
    [
        # LOCO: 디렉터리를 떼고 구분자를 통일
        ("loco/subset-1/1564563638.9526272.jpg", "1564563638-9526272"),
        ("loco/subset-5/2020-03-05_1/RealSense/color/0/1583416214257,48.jpg", "1583416214257-48"),
        # Roboflow: 증강 접미사를 떼면 같은 원본으로 묶인다
        ("logistics/1564563638-9526272_jpg.rf.8685fc40f5efaa04.jpg", "1564563638-9526272"),
        ("roboflow_cardboard/cardboard-1308_jpg.rf.000778936f4e4529.jpg", "cardboard-1308"),
        ("roboflow_cardboard/cardboard-1308_jpg.rf.ffff778936f4e452.jpg", "cardboard-1308"),
    ],
)
def test_정체성_키는_소스별_파일명_차이를_흡수한다(file_name: str, expected: str) -> None:
    assert image_identity(file_name) == expected


def test_다른_데이터셋의_같은_원본은_먼저_온_쪽을_남긴다(builder: CocoBuilder) -> None:
    """LOCO 3,830장이 Logistics에 재수록돼 있어 실제로 발생한 상황."""
    stats = BuildStats()
    kept = builder.add_image(
        "loco/subset-1/1564563638.9526272.jpg", 1920, 1080, stats, group="loco"
    )
    dropped = builder.add_image(
        "logistics/1564563638-9526272_jpg.rf.8685fc40f5efaa04.jpg", 640, 640, stats,
        group="logistics",
    )

    assert kept == 1
    assert dropped is None
    assert stats.reasons["loco와 동일 원본"] == 1
    assert builder.num_images == 1


def test_같은_데이터셋의_증강본은_중복이_아니다(builder: CocoBuilder) -> None:
    """증강본은 원본을 공유하지만 의도된 학습 데이터라 살려야 한다."""
    stats = BuildStats()
    first = builder.add_image(
        "roboflow_cardboard/cardboard-1308_jpg.rf.aaaa778936f4e452.jpg", 640, 640, stats,
        group="roboflow_cardboard",
    )
    second = builder.add_image(
        "roboflow_cardboard/cardboard-1308_jpg.rf.bbbb778936f4e452.jpg", 640, 640, stats,
        group="roboflow_cardboard",
    )

    assert (first, second) == (1, 2)
    assert builder.num_images == 2


def test_group을_안_주면_소스마다_독립으로_본다(builder: CocoBuilder) -> None:
    """group 미지정(None)끼리는 같은 group이므로 중복 제거가 걸리지 않는다."""
    stats = BuildStats()
    assert builder.add_image("a/img.jpg", 100, 100, stats) == 1
    assert builder.add_image("b/img.jpg", 100, 100, stats) == 2


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

    stats = convert_coco(builder, source, prefix="loco", class_map={"box": ["small_load_carrier"]})

    assert stats.annotations == 1
    assert stats.reasons["대상 외 카테고리"] == 1
    result = builder.to_dict()
    assert result["images"][0]["file_name"] == "loco/img.jpg"
    assert result["annotations"][0]["category_id"] == 1      # classes[0] = box


def _loco_like(tmp_path) -> Path:
    """LOCO 카테고리 구성을 본뜬 소스. 박스형 2종 + 파렛트 + 장비 2종."""
    source = tmp_path / "loco.json"
    source.write_text(
        json.dumps(
            {
                "categories": [
                    {"id": 3, "name": "small_load_carrier"},
                    {"id": 5, "name": "forklift"},
                    {"id": 7, "name": "pallet"},
                    {"id": 10, "name": "stillage"},
                    {"id": 11, "name": "pallet_truck"},
                ],
                "images": [{"id": 1, "file_name": "a.jpg", "width": 500, "height": 500}],
                "annotations": [
                    {"id": 1, "image_id": 1, "category_id": 3, "bbox": [10, 10, 30, 30]},
                    {"id": 2, "image_id": 1, "category_id": 10, "bbox": [50, 10, 30, 30]},
                    {"id": 3, "image_id": 1, "category_id": 7, "bbox": [90, 10, 40, 40]},
                    {"id": 4, "image_id": 1, "category_id": 5, "bbox": [10, 90, 40, 40]},
                    {"id": 5, "image_id": 1, "category_id": 11, "bbox": [90, 90, 40, 40]},
                ],
            }
        ),
        encoding="utf-8",
    )
    return source


def test_박스와_파렛트를_다른_클래스로_낸다(tmp_path, builder: CocoBuilder) -> None:
    """측정 스테이션에는 파렛트 위에 박스가 올라오므로 구분이 필요하다."""
    stats = convert_coco(
        builder,
        _loco_like(tmp_path),
        prefix="loco",
        class_map={"box": ["small_load_carrier", "stillage"], "pallet": ["pallet"]},
    )

    assert stats.per_class == {"box": 2, "pallet": 1}
    assert builder.class_counts() == {"box": 2, "pallet": 1}

    categories = {c["id"]: c["name"] for c in builder.to_dict()["categories"]}
    assert categories == {1: "box", 2: "pallet"}


def test_나열되지_않은_카테고리는_제외된다(tmp_path, builder: CocoBuilder) -> None:
    """forklift·pallet_truck은 장비라 어느 클래스에도 넣지 않는다."""
    stats = convert_coco(
        builder,
        _loco_like(tmp_path),
        prefix="loco",
        class_map={"box": ["small_load_carrier", "stillage"], "pallet": ["pallet"]},
    )

    assert stats.reasons["대상 외 카테고리"] == 2      # forklift, pallet_truck


def test_와일드카드는_나머지_전부를_맡는다(tmp_path, builder: CocoBuilder) -> None:
    """Carboard Box처럼 모든 카테고리가 같은 대상인 소스용."""
    stats = convert_coco(
        builder,
        _loco_like(tmp_path),
        prefix="loco",
        class_map={"pallet": ["pallet"], "box": "*"},
    )

    # pallet만 명시됐고 나머지 4종이 box로 간다
    assert stats.per_class == {"box": 4, "pallet": 1}


def test_와일드카드를_두_클래스에_쓰면_거부한다(tmp_path, builder: CocoBuilder) -> None:
    with pytest.raises(ValueError, match=r"'\*'는 한 클래스에만"):
        convert_coco(
            builder, _loco_like(tmp_path), prefix="loco",
            class_map={"box": "*", "pallet": "*"},
        )


def test_한_카테고리를_두_클래스에_지정하면_거부한다(tmp_path, builder: CocoBuilder) -> None:
    with pytest.raises(ValueError, match="양쪽에 지정"):
        convert_coco(
            builder, _loco_like(tmp_path), prefix="loco",
            class_map={"box": ["pallet"], "pallet": ["pallet"]},
        )


def test_정의되지_않은_클래스는_거부한다(builder: CocoBuilder) -> None:
    stats = BuildStats()
    image_id = builder.add_image("a/1.jpg", 100, 100, stats)

    with pytest.raises(ValueError, match="정의되지 않은 클래스"):
        builder.add_annotation(image_id, (10, 10, 20, 20), stats, "forklift")


def test_클래스_이름이_중복되면_거부한다() -> None:
    with pytest.raises(ValueError, match="중복"):
        CocoBuilder(classes=["box", "box"])


def test_path_key로_디렉터리까지_보존한다(tmp_path, builder: CocoBuilder) -> None:
    """LOCO는 file_name이 타임스탬프 basename이라 subset 간 충돌한다.

    실제 LOCO 레코드 형태를 그대로 본떠, path를 쓰면 디렉터리가 남고
    공통 접두사 '/dataset/'만 떨어지는지 확인한다.
    """
    source = tmp_path / "loco.json"
    source.write_text(
        json.dumps(
            {
                "categories": [{"id": 3, "name": "small_load_carrier"}],
                "images": [
                    {
                        "id": 1,
                        "path": "/dataset/subset-5/2020-03-05_1/Kinect/color/1613832,4601.jpg",
                        "file_name": "1613832,4601.jpg",
                        "width": 1920,
                        "height": 1080,
                    },
                    {
                        "id": 2,
                        "path": "/dataset/subset-2/2020-03-05_1/Kinect/color/1613832,4601.jpg",
                        "file_name": "1613832,4601.jpg",
                        "width": 1920,
                        "height": 1080,
                    },
                ],
                "annotations": [
                    {"id": 1, "image_id": 1, "category_id": 3, "bbox": [10, 10, 30, 30]},
                    {"id": 2, "image_id": 2, "category_id": 3, "bbox": [10, 10, 30, 30]},
                ],
            }
        ),
        encoding="utf-8",
    )

    convert_coco(
        builder,
        source,
        prefix="loco",
        class_map={"box": ["small_load_carrier"]},
        path_key="path",
        strip_path_prefix="/dataset/",
    )

    file_names = [img["file_name"] for img in builder.to_dict()["images"]]
    assert file_names == [
        "loco/subset-5/2020-03-05_1/Kinect/color/1613832,4601.jpg",
        "loco/subset-2/2020-03-05_1/Kinect/color/1613832,4601.jpg",
    ]


def test_path_key_없이는_basename이_충돌한다(tmp_path, builder: CocoBuilder) -> None:
    """path_key를 안 쓰면 서로 다른 이미지가 하나로 합쳐지는 것을 명시한다."""
    source = tmp_path / "loco.json"
    source.write_text(
        json.dumps(
            {
                "categories": [{"id": 3, "name": "small_load_carrier"}],
                "images": [
                    {"id": 1, "file_name": "1613832.jpg", "width": 100, "height": 100},
                    {"id": 2, "file_name": "1613832.jpg", "width": 100, "height": 100},
                ],
                "annotations": [],
            }
        ),
        encoding="utf-8",
    )

    stats = convert_coco(builder, source, prefix="loco", class_map={"box": "*"})

    assert builder.num_images == 1
    assert stats.reasons["중복 file_name"] == 1


def test_없는_카테고리를_지정하면_에러(tmp_path, builder: CocoBuilder) -> None:
    source = tmp_path / "loco.json"
    source.write_text(
        json.dumps({"categories": [{"id": 1, "name": "pallet"}], "images": [], "annotations": []}),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="없는 카테고리"):
        convert_coco(builder, source, prefix="loco", class_map={"box": ["typo_name"]})


def test_sku110k_csv는_코너좌표를_wh로_바꾼다(tmp_path, builder: CocoBuilder) -> None:
    source = tmp_path / "ann.csv"
    source.write_text(
        "img1.jpg,10,20,60,80,object,640,480\nimg1.jpg,100,100,150,200,object,640,480\n",
        encoding="utf-8",
    )

    stats = convert_sku110k(builder, source, prefix="sku110k")

    assert (stats.images, stats.annotations) == (1, 2)
    assert builder.to_dict()["annotations"][0]["bbox"] == [10, 20, 50, 60]
