"""라벨 검수·분할 테스트 (FR-101-2)."""

from __future__ import annotations

import pytest

from dataset.review import review
from dataset.split import split_by_identity


def make_data(images: list[tuple[int, str, int, int]],
              anns: list[tuple[int, int, list[float]]]) -> dict:
    """(id, file_name, w, h) 목록과 (id, image_id, bbox) 목록으로 COCO dict를 만든다."""
    return {
        "categories": [{"id": 1, "name": "box"}],
        "images": [
            {"id": i, "file_name": fn, "width": w, "height": h} for i, fn, w, h in images
        ],
        "annotations": [
            {"id": aid, "image_id": iid, "category_id": 1, "bbox": bbox,
             "area": bbox[2] * bbox[3], "iscrowd": 0}
            for aid, iid, bbox in anns
        ],
    }


# --- 검수 ---

def test_초소형_박스를_제거한다() -> None:
    data = make_data(
        [(1, "loco/a.jpg", 100, 100)],
        [(1, 1, [10, 10, 2, 2]), (2, 1, [10, 10, 30, 30])],  # 면적 4 / 900
    )
    result = review(data, min_area=16, max_aspect=20, dup_iou=0.9)

    assert result.removed["면적 16px 미만"] == 1
    assert len(data["annotations"]) == 1


def test_극단적인_종횡비를_제거한다() -> None:
    data = make_data(
        [(1, "loco/a.jpg", 1000, 1000)],
        [(1, 1, [0, 0, 500, 10]), (2, 1, [0, 0, 100, 100])],  # 50:1 / 1:1
    )
    result = review(data, min_area=16, max_aspect=20, dup_iou=0.9)

    assert result.removed["종횡비 20:1 초과"] == 1
    assert len(data["annotations"]) == 1


def test_같은_이미지_안의_중복_박스는_하나만_남긴다() -> None:
    data = make_data(
        [(1, "loco/a.jpg", 100, 100)],
        [(1, 1, [10, 10, 40, 40]), (2, 1, [10, 10, 41, 40]), (3, 1, [60, 60, 30, 30])],
    )
    result = review(data, min_area=16, max_aspect=20, dup_iou=0.9)

    assert result.removed["IoU 0.9 이상 중복"] == 1
    assert len(data["annotations"]) == 2


def test_다른_이미지의_같은_위치_박스는_중복이_아니다() -> None:
    data = make_data(
        [(1, "loco/a.jpg", 100, 100), (2, "loco/b.jpg", 100, 100)],
        [(1, 1, [10, 10, 40, 40]), (2, 2, [10, 10, 40, 40])],
    )
    review(data, min_area=16, max_aspect=20, dup_iou=0.9)

    assert len(data["annotations"]) == 2


def test_정상_라벨은_건드리지_않는다() -> None:
    """실측상 불량은 0.07%뿐이라, 기본값이 멀쩡한 라벨을 지우면 안 된다."""
    data = make_data(
        [(1, "loco/a.jpg", 640, 640)],
        [(1, 1, [0, 0, 640, 640]),      # 전체 프레임 — 클로즈업이면 정상
         (2, 1, [10, 10, 20, 8]),       # 작지만 유효
         (3, 1, [100, 100, 200, 30])],  # 6.7:1 — 납작하지만 유효
    )
    result = review(data, min_area=16, max_aspect=20, dup_iou=0.9)

    assert result.total_removed == 0
    assert len(data["annotations"]) == 3


# --- 분할 ---

def test_증강본은_어느_세트에도_갈라지지_않는다() -> None:
    """핵심 요구사항. 실측상 순진한 분할은 val의 54.8%가 오염됐다."""
    images = [
        (i, f"roboflow_cardboard/orig{i // 5}_jpg.rf.{i:032x}.jpg", 640, 640)
        for i in range(100)  # 원본 20개 × 증강 5장
    ]
    data = make_data(images, [(i, i, [10, 10, 50, 50]) for i in range(100)])

    splits, report = split_by_identity(data, val_ratio=0.15, test_ratio=0.15, seed=42)

    assert report["leaked"] == {}
    assert report["groups"] == 20
    # 그룹 단위라 각 세트의 이미지 수는 5의 배수로 떨어진다
    for ids in splits.values():
        assert len(ids) % 5 == 0
    assert splits["train"] & splits["val"] == set()
    assert splits["train"] & splits["test"] == set()
    assert splits["val"] & splits["test"] == set()


def test_세_세트가_전체를_빠짐없이_덮는다() -> None:
    images = [(i, f"loco/a{i}.jpg", 640, 640) for i in range(100)]
    data = make_data(images, [(i, i, [10, 10, 50, 50]) for i in range(100)])

    splits, _ = split_by_identity(data, val_ratio=0.15, test_ratio=0.15, seed=5)

    assert splits["train"] | splits["val"] | splits["test"] == set(range(100))


def test_test_비율_0이면_test가_비어_있다() -> None:
    images = [(i, f"loco/a{i}.jpg", 640, 640) for i in range(100)]
    data = make_data(images, [(i, i, [10, 10, 50, 50]) for i in range(100)])

    splits, _ = split_by_identity(data, val_ratio=0.2, test_ratio=0.0, seed=5)

    assert splits["test"] == set()
    assert len(splits["val"]) == 20
    assert len(splits["train"]) == 80


def test_소스별_비율이_유지된다() -> None:
    images = (
        [(i, f"loco/a{i}.jpg", 640, 640) for i in range(100)]
        + [(100 + i, f"logistics/b{i}.jpg", 640, 640) for i in range(20)]
    )
    data = make_data(images, [(i, i, [10, 10, 50, 50]) for i in range(120)])

    _, report = split_by_identity(data, val_ratio=0.15, test_ratio=0.15, seed=1)

    assert report["per_source"]["loco"] == {"train": 70, "val": 15, "test": 15}
    assert report["per_source"]["logistics"] == {"train": 14, "val": 3, "test": 3}


def test_같은_시드는_같은_분할을_준다() -> None:
    images = [(i, f"loco/a{i}.jpg", 640, 640) for i in range(50)]
    data = make_data(images, [(i, i, [10, 10, 50, 50]) for i in range(50)])

    first, _ = split_by_identity(data, 0.15, 0.15, seed=7)
    second, _ = split_by_identity(data, 0.15, 0.15, seed=7)
    other, _ = split_by_identity(data, 0.15, 0.15, seed=8)

    assert first == second
    assert first != other


@pytest.mark.parametrize("ratio", [0.1, 0.2, 0.5])
def test_val_비율이_대체로_지켜진다(ratio: float) -> None:
    images = [(i, f"loco/a{i}.jpg", 640, 640) for i in range(200)]
    data = make_data(images, [(i, i, [10, 10, 50, 50]) for i in range(200)])

    splits, _ = split_by_identity(data, val_ratio=ratio, test_ratio=0.0, seed=3)

    assert len(splits["val"]) == round(200 * ratio)
