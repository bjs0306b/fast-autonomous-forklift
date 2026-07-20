"""COCO 포맷 빌더 — 박스 단일 클래스(FR-101) 기준.

여러 공개 데이터셋을 하나의 COCO 어노테이션으로 합치기 위한 최소 구현.
pycocotools 없이 동작하며, 학습 프레임워크(MMDetection/RTMDet)가 읽는
detection 필드만 채운다.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

# 박스 단일 클래스이므로 카테고리는 항상 1개(id=1)로 고정한다.
BOX_CATEGORY_ID = 1

# Roboflow 증강본 접미사: '<원본>_jpg.rf.<해시>.jpg'
_ROBOFLOW_SUFFIX = re.compile(r"^(.*?)_(?:jpg|jpeg|png)\.rf\.[0-9a-f]+\.", re.IGNORECASE)


def image_identity(file_name: str) -> str:
    """서로 다른 소스에서 온 '같은 원본 사진'을 알아보기 위한 키.

    공개 데이터셋은 서로를 재수록한다. 실제로 LOCO 5,097장 중 3,830장이
    Roboflow Logistics에 다시 들어 있었는데, 파일명이 각각
    ``subset-1/1564563638.9526272.jpg`` 와
    ``1564563638-9526272_jpg.rf.<해시>.jpg`` 라서 그냥은 걸러지지 않는다.

    디렉터리·증강 접미사를 떼고 구분자를 통일해 원본 이름만 남긴다.
    """
    stem = Path(file_name).name
    match = _ROBOFLOW_SUFFIX.match(stem)
    stem = match.group(1) if match else Path(stem).stem
    return stem.replace(".", "-").replace(",", "-").lower()


@dataclass
class BuildStats:
    """소스별 변환 결과 집계 — 변환 후 눈으로 검증하기 위한 값."""

    images: int = 0
    annotations: int = 0
    skipped_images: int = 0
    skipped_annotations: int = 0
    reasons: dict[str, int] = field(default_factory=dict)

    def skip(self, kind: str, reason: str) -> None:
        if kind == "image":
            self.skipped_images += 1
        else:
            self.skipped_annotations += 1
        self.reasons[reason] = self.reasons.get(reason, 0) + 1


class CocoBuilder:
    """이미지/어노테이션을 누적해 COCO dict를 만든다.

    - id는 소스별로 겹치므로 전부 새로 발급한다.
    - file_name은 최종 이미지 루트 기준 상대경로로 저장한다.
    - 같은 file_name이 두 번 들어오면 뒤엣것을 버린다.
    - ``group``(데이터셋)이 다른데 같은 원본 사진이면 먼저 온 쪽을 남긴다.
      증강본은 같은 원본을 공유하므로, 같은 group 안에서는 중복으로 보지 않는다.
    """

    def __init__(self, class_name: str = "box") -> None:
        self.class_name = class_name
        self._images: list[dict] = []
        self._annotations: list[dict] = []
        self._image_by_id: dict[int, dict] = {}
        self._index_by_file: dict[str, int] = {}
        self._identity_owner: dict[str, str] = {}  # 정체성 → 선점한 group
        self._next_image_id = 1
        self._next_annotation_id = 1

    def add_image(
        self,
        file_name: str,
        width: int,
        height: int,
        stats: BuildStats,
        group: str | None = None,
    ) -> int | None:
        """이미지를 등록하고 새 image_id를 돌려준다. 중복·비정상이면 None."""
        file_name = file_name.replace("\\", "/")

        if file_name in self._index_by_file:
            stats.skip("image", "중복 file_name")
            return None

        identity = image_identity(file_name)
        owner = self._identity_owner.get(identity)
        if owner is not None and owner != group:
            stats.skip("image", f"{owner}와 동일 원본")
            return None
        if width <= 0 or height <= 0:
            stats.skip("image", "width/height 없음")
            return None

        image_id = self._next_image_id
        self._next_image_id += 1
        image = {"id": image_id, "file_name": file_name, "width": width, "height": height}
        self._images.append(image)
        self._image_by_id[image_id] = image
        self._index_by_file[file_name] = image_id
        self._identity_owner.setdefault(identity, group)
        stats.images += 1
        return image_id

    def add_annotation(
        self,
        image_id: int,
        bbox: tuple[float, float, float, float],
        stats: BuildStats,
        iscrowd: int = 0,
    ) -> bool:
        """bbox는 COCO 규약대로 [x, y, w, h] (좌상단 기준, 픽셀)."""
        image = self._image_by_id[image_id]
        clipped = _clip_bbox(bbox, image["width"], image["height"])
        if clipped is None:
            stats.skip("annotation", "bbox 범위 이탈 또는 면적 0")
            return False

        x, y, w, h = clipped
        self._annotations.append(
            {
                "id": self._next_annotation_id,
                "image_id": image_id,
                "category_id": BOX_CATEGORY_ID,
                "bbox": [x, y, w, h],
                "area": w * h,
                "iscrowd": iscrowd,
            }
        )
        self._next_annotation_id += 1
        stats.annotations += 1
        return True

    def drop_empty_images(self) -> int:
        """어노테이션이 하나도 없는 이미지를 제거한다.

        배경 이미지는 학습에 도움이 되지만, 소스가 부분 라벨링된 경우
        '박스가 있는데 라벨이 없는' 이미지가 섞여 오탐 학습을 유발한다.
        FR-101-2(라벨 검수)에서 선별하기 전까지는 제거하는 쪽이 안전하다.
        """
        annotated = {ann["image_id"] for ann in self._annotations}
        kept = [img for img in self._images if img["id"] in annotated]
        removed = len(self._images) - len(kept)
        if removed:
            self._images = kept
            self._image_by_id = {img["id"]: img for img in kept}
            self._index_by_file = {img["file_name"]: img["id"] for img in kept}
        return removed

    def to_dict(self) -> dict:
        return {
            "info": {
                "description": "S15P11A304 지게차 화물 인식 데이터셋 (박스 단일 클래스)",
                "version": "0.1",
            },
            "licenses": [],
            "categories": [
                {"id": BOX_CATEGORY_ID, "name": self.class_name, "supercategory": "cargo"}
            ],
            "images": self._images,
            "annotations": self._annotations,
        }

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8") as f:
            json.dump(self.to_dict(), f, ensure_ascii=False)

    @property
    def num_images(self) -> int:
        return len(self._images)

    @property
    def num_annotations(self) -> int:
        return len(self._annotations)


def _clip_bbox(
    bbox: tuple[float, float, float, float], width: int, height: int
) -> tuple[float, float, float, float] | None:
    """이미지 경계로 bbox를 자른다. 남는 면적이 없으면 None.

    공개 데이터셋에는 경계를 조금 넘는 좌표가 흔해서, 버리기보다 자르는 편이
    데이터 손실이 적다. 다만 완전히 밖에 있거나 폭·높이가 0이면 버린다.
    """
    x, y, w, h = (float(v) for v in bbox)
    if w <= 0 or h <= 0:
        return None

    x1, y1 = max(x, 0.0), max(y, 0.0)
    x2, y2 = min(x + w, float(width)), min(y + h, float(height))
    if x2 - x1 <= 0 or y2 - y1 <= 0:
        return None

    return (round(x1, 2), round(y1, 2), round(x2 - x1, 2), round(y2 - y1, 2))
