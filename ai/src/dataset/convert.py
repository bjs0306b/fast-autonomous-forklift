"""데이터셋 변환 CLI (FR-101-1).

사용법:
    python -m dataset.convert --config configs/datasets.yaml
    python -m dataset.convert --config configs/datasets.yaml --only loco
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml

from .coco import BuildStats, CocoBuilder
from .sources import convert_coco, convert_sku110k


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="공개 데이터셋 → 박스 단일 클래스 COCO 변환")
    parser.add_argument("--config", type=Path, required=True, help="데이터셋 구성 YAML")
    parser.add_argument("--out", type=Path, help="출력 경로 (미지정 시 config의 output 사용)")
    parser.add_argument("--only", help="특정 소스 하나만 변환 (소스 name)")
    parser.add_argument(
        "--keep-empty",
        action="store_true",
        help="어노테이션 없는 이미지도 남긴다 (기본은 제거)",
    )
    parser.add_argument(
        "--verify-images",
        action="store_true",
        help="변환 후 이미지 파일이 실제로 존재하는지 확인한다 (경로 규칙 검증)",
    )
    args = parser.parse_args(argv)

    config = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    root = args.config.parent.parent  # ai/ 기준으로 상대경로 해석
    out_path = args.out or (root / config["output"])

    builder = CocoBuilder(class_name=config.get("class_name", "box"))
    converted = 0
    roots: dict[str, Path] = {}  # prefix → 이미지 루트 (검증용)

    for source in config["sources"]:
        name = source["name"]
        if args.only and name != args.only:
            continue
        if not source.get("enabled", True) and not args.only:
            print(f"[건너뜀] {name} — enabled: false")
            continue

        annotations = root / source["annotations"]
        if not annotations.exists():
            print(f"[건너뜀] {name} — 어노테이션 없음: {annotations}", file=sys.stderr)
            continue

        print(f"[변환] {name} ← {annotations}")
        stats = _convert_one(builder, source, annotations)
        _print_stats(name, stats)
        roots[source.get("prefix", name)] = root / source["images_root"]
        converted += 1

    if converted == 0:
        print("변환한 소스가 없습니다. README의 데이터셋 내려받기 절차를 먼저 확인하세요.",
              file=sys.stderr)
        return 1

    if not args.keep_empty:
        removed = builder.drop_empty_images()
        if removed:
            print(f"[정리] 어노테이션 없는 이미지 {removed:,}장 제거")

    if args.verify_images and not _verify_images(builder, roots):
        return 1

    builder.save(out_path)
    print(
        f"\n완료 → {out_path}\n"
        f"  이미지 {builder.num_images:,}장 / 박스 {builder.num_annotations:,}개"
    )
    return 0


def _verify_images(builder: CocoBuilder, roots: dict[str, Path]) -> bool:
    """file_name이 실제 이미지 파일을 가리키는지 확인한다.

    어노테이션의 경로 규칙과 실제 배치가 어긋나면 학습 단계에 가서야
    터지므로, 변환 시점에 잡는다.
    """
    missing: list[str] = []
    for image in builder.to_dict()["images"]:
        prefix, _, rel = image["file_name"].partition("/")
        root = roots.get(prefix)
        if root is None or not (root / rel).exists():
            missing.append(image["file_name"])

    if not missing:
        print(f"[검증] 이미지 {builder.num_images:,}장 모두 존재")
        return True

    print(
        f"\n[검증 실패] 이미지 {len(missing):,}장을 찾을 수 없습니다 "
        f"(전체 {builder.num_images:,}장). images_root 또는 path_key 설정을 확인하세요.",
        file=sys.stderr,
    )
    for name in missing[:5]:
        print(f"    {name}", file=sys.stderr)
    return False


def _convert_one(builder: CocoBuilder, source: dict, annotations: Path) -> BuildStats:
    kind = source.get("type", "coco")
    prefix = source.get("prefix", source["name"])
    # group은 '같은 데이터셋'의 단위. train/valid/test처럼 한 데이터셋을 여러
    # 소스로 쪼개 선언한 경우 같은 group을 줘야 증강본이 중복으로 걸리지 않는다.
    group = source.get("group", prefix)

    if kind == "coco":
        return convert_coco(
            builder,
            annotations,
            prefix,
            keep_categories=source.get("keep_categories"),
            path_key=source.get("path_key", "file_name"),
            strip_path_prefix=source.get("strip_path_prefix", ""),
            group=group,
        )
    if kind == "sku110k":
        return convert_sku110k(builder, annotations, prefix, group=group)
    raise ValueError(f"알 수 없는 소스 type: {kind}")


def _print_stats(name: str, stats: BuildStats) -> None:
    print(f"  {name}: 이미지 {stats.images:,}장 / 박스 {stats.annotations:,}개")
    if stats.skipped_images or stats.skipped_annotations:
        print(f"    제외 — 이미지 {stats.skipped_images:,} / 박스 {stats.skipped_annotations:,}")
        for reason, count in sorted(stats.reasons.items(), key=lambda kv: -kv[1]):
            print(f"      {reason}: {count:,}")


if __name__ == "__main__":
    raise SystemExit(main())
