"""Roboflow COCO 내보내기 zip 추출 CLI (Windows 경로 길이 대응).

Roboflow는 원본 URL을 base64로 인코딩해 파일명에 넣는 경우가 있어 파일명이
255자에 이른다. Windows는 긴 경로 지원이 꺼져 있으면 전체 경로 260자에서
막히므로(기본값이 꺼짐), 그런 파일만 짧은 이름으로 바꾸고 어노테이션의
file_name도 함께 고쳐 준다.

사용법:
    python -m dataset.extract_roboflow "~/Downloads/Logistics.v2i.coco.zip" data/raw/logistics
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

# 전체 경로 상한(260)에서 여유를 둔 값. 넘으면 파일명을 해시로 줄인다.
MAX_PATH = 250


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Roboflow zip 추출 (긴 파일명 대응)")
    parser.add_argument("zip_path", type=Path, help="Roboflow COCO 내보내기 zip")
    parser.add_argument("dest", type=Path, help="추출 위치")
    args = parser.parse_args(argv)

    dest = args.dest.resolve()
    dest.mkdir(parents=True, exist_ok=True)

    renames: dict[str, str] = {}  # 원래 zip 경로 → 새 상대경로
    with zipfile.ZipFile(args.zip_path) as z:
        members = [n for n in z.namelist() if not n.endswith("/")]
        print(f"엔트리 {len(members):,}개 → {dest}")

        for name in members:
            target_rel = name
            if len(str(dest / name)) > MAX_PATH:
                target_rel = _shorten(name, dest)
                renames[name] = target_rel

            target = dest / target_rel
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(name) as src, target.open("wb") as out:
                out.write(src.read())

    print(f"추출 완료. 이름을 줄인 파일: {len(renames):,}개")

    if renames:
        patched = _patch_annotations(dest, renames)
        print(f"어노테이션 file_name 수정: {patched:,}건")
        if patched != len(renames):
            print(
                f"경고: 줄인 파일 {len(renames)}개 중 {patched}건만 어노테이션에서 찾았습니다. "
                "라벨 없는 이미지가 섞여 있으면 정상입니다.",
                file=sys.stderr,
            )
    return 0


def _shorten(name: str, dest: Path) -> str:
    """확장자와 디렉터리는 두고 파일명만 해시로 줄인다."""
    p = Path(name)
    digest = hashlib.sha1(name.encode("utf-8")).hexdigest()[:16]
    candidate = p.with_name(f"rf_{digest}{p.suffix}")

    # 디렉터리 깊이 자체가 문제면 손댈 수 없다 — Roboflow 구조상 발생하지 않는다.
    if len(str(dest / candidate)) > MAX_PATH:
        raise ValueError(f"파일명을 줄여도 경로가 너무 깁니다: {name}")
    return str(candidate).replace("\\", "/")


def _patch_annotations(dest: Path, renames: dict[str, str]) -> int:
    """_annotations.coco.json의 file_name을 새 이름으로 바꾼다.

    어노테이션의 file_name은 split 디렉터리 기준 basename이므로,
    zip 경로에서 basename만 떼어 대조한다.
    """
    by_basename = {Path(old).name: Path(new).name for old, new in renames.items()}
    patched = 0

    for ann_path in dest.rglob("_annotations.coco.json"):
        data = json.loads(ann_path.read_text(encoding="utf-8-sig"))
        changed = False
        for image in data.get("images", []):
            new_name = by_basename.get(image["file_name"])
            if new_name:
                image["file_name"] = new_name
                patched += 1
                changed = True
        if changed:
            ann_path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

    return patched


if __name__ == "__main__":
    raise SystemExit(main())
