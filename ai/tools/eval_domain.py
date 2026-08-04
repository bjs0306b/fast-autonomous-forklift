"""도메인 평가 하네스 — 파인튜닝 파이프라인 스테이징.

**measure-first 원칙**: 새 도메인 데이터(실물 리그/미니어처 온보드)가 도착하면 파인튜닝
전에 먼저 현재 체크포인트를 그 데이터로 채점한다. mAP@0.5가 KPI를 넘으면 파인튜닝 없이
그대로 배포하고, 미달일 때만 configs/finetune/*.py로 넘어간다. 이 스크립트가 그 첫 단계다.

mmdet의 test 파이프라인(`mim test mmdet`)을 그대로 감싸 재사용한다 — config에 이미 박힌
전처리(keep_ratio 리사이즈+패딩+정규화)와 100% 동일한 경로로 평가해야 학습 로그에 남은
성능과 비교 가능하기 때문에, 직접 추론 루프를 새로 짜지 않는다.

⚠️ 평가셋의 COCO category_id는 프로젝트 스키마(box=1, pallet=2)를 따라야 한다. 다른 스키마면
클래스가 잘못 매핑된다.

사용법 (ai/ 디렉터리에서):
    python tools/eval_domain.py \\
        --config configs/rtmdet_m_800_scd_forklift.py \\
        --checkpoint work_dirs/rtmdet_m_800_scd/best_coco_bbox_mAP_epoch_99.pth \\
        --ann /path/to/domain_val.json --img-root /path/to/domain_images \\
        --out work_dirs/eval_domain_rig.json
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SUMMARY_RE = re.compile(
    r"coco/bbox_mAP:\s*([\d.]+)\s+coco/bbox_mAP_50:\s*([\d.]+)\s+coco/bbox_mAP_75:\s*([\d.]+)"
)
CLASSWISE_HEADER_RE = re.compile(r"^\|\s*category\s*\|")
CLASSWISE_ROW_RE = re.compile(
    r"^\|\s*(\S+)\s*\|\s*([\d.]+)\s*\|\s*([\d.]+)\s*\|\s*([\d.]+)\s*\|"
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="도메인 데이터로 체크포인트 채점(measure-first)")
    parser.add_argument("--config", type=Path, required=True, help="모델 config (.py)")
    parser.add_argument("--checkpoint", type=Path, required=True, help="평가할 체크포인트 (.pth)")
    parser.add_argument("--ann", type=Path, required=True, help="평가셋 COCO json")
    parser.add_argument("--img-root", type=Path, required=True, help="평가셋 이미지 루트 디렉터리")
    parser.add_argument("--out", type=Path, default=None, help="결과 json 저장 경로")
    parser.add_argument("--work-dir", type=Path, default=None,
                        help="mim test 작업 디렉터리 (기본: 임시 디렉터리, 종료 후 삭제)")
    parser.add_argument("--gpu", type=int, default=1,
                        help="CUDA_VISIBLE_DEVICES (기본 1 — 규칙상 1만 사용)")
    parser.add_argument("--kpi", type=float, default=0.92,
                        help="mAP@0.5 KPI 임계값 (기본 0.92 = 스테이션 -m 판정 기준)")
    args = parser.parse_args(argv)

    if not args.ann.exists():
        parser.error(f"평가 ann 파일이 없습니다: {args.ann}")
    if not args.img_root.exists():
        parser.error(f"이미지 루트가 없습니다: {args.img_root}")
    if not args.checkpoint.exists():
        parser.error(f"체크포인트가 없습니다: {args.checkpoint}")
    if not args.config.exists():
        parser.error(f"config가 없습니다: {args.config}")

    with tempfile.TemporaryDirectory(prefix="eval_domain_") as tmp:
        work_dir = args.work_dir or Path(tmp)
        work_dir.mkdir(parents=True, exist_ok=True)
        outfile_prefix = work_dir / "results"

        cmd = [
            "mim", "test", "mmdet", str(args.config),
            "--checkpoint", str(args.checkpoint),
            "--gpus", "1",
            "--work-dir", str(work_dir),
            "--cfg-options",
            "test_dataloader.dataset.data_root=",
            f"test_dataloader.dataset.ann_file={args.ann.resolve()}",
            f"test_dataloader.dataset.data_prefix.img={args.img_root.resolve()}/",
            "test_evaluator.classwise=True",
            f"test_evaluator.ann_file={args.ann.resolve()}",
            f"test_evaluator.outfile_prefix={outfile_prefix}",
        ]
        env = {**os.environ, "CUDA_VISIBLE_DEVICES": str(args.gpu)}
        print("실행:", " ".join(cmd))
        proc = subprocess.run(cmd, capture_output=True, text=True, env=env)
        print(proc.stdout[-4000:])
        if proc.returncode != 0:
            print(proc.stderr[-4000:], file=sys.stderr)
            return 1

        result = _parse_output(proc.stdout)

    result["config"] = str(args.config)
    result["checkpoint"] = str(args.checkpoint)
    result["ann"] = str(args.ann)
    _print_summary(result, args.kpi)

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n저장 → {args.out}")

    return 0


def _parse_output(stdout: str) -> dict:
    """`mim test mmdet` 로그에서 전체·클래스별 mAP를 뽑아낸다."""
    overall = None
    for m in SUMMARY_RE.finditer(stdout):
        overall = {
            "mAP": float(m.group(1)),
            "mAP_50": float(m.group(2)),
            "mAP_75": float(m.group(3)),
        }
    if overall is None:
        raise RuntimeError("mim test 출력에서 mAP 요약을 찾지 못했습니다 — 위 로그를 확인하세요")

    classwise: dict[str, dict[str, float]] = {}
    in_table = False
    for line in stdout.splitlines():
        if CLASSWISE_HEADER_RE.match(line):
            in_table = True
            continue
        if not in_table:
            continue
        if line.startswith("+--"):
            continue
        m = CLASSWISE_ROW_RE.match(line)
        if not m:
            break
        name, mAP, mAP_50, mAP_75 = m.groups()
        classwise[name] = {"mAP": float(mAP), "mAP_50": float(mAP_50), "mAP_75": float(mAP_75)}

    return {"overall": overall, "classwise": classwise}


def _print_summary(result: dict, kpi: float) -> None:
    o = result["overall"]
    print("\n=== 평가 결과 ===")
    print(f"config:     {result['config']}")
    print(f"checkpoint: {result['checkpoint']}")
    print(f"ann:        {result['ann']}")
    print(f"\n전체   mAP {o['mAP']:.3f}  mAP@0.5 {o['mAP_50']:.3f}  mAP@0.75 {o['mAP_75']:.3f}")
    for name, c in result["classwise"].items():
        print(f"{name:<6} mAP {c['mAP']:.3f}  mAP@0.5 {c['mAP_50']:.3f}  mAP@0.75 {c['mAP_75']:.3f}")

    verdict = "PASS" if o["mAP_50"] >= kpi else "FAIL"
    print(f"\nKPI(mAP@0.5 ≥ {kpi:.2f}): {verdict} (측정값 {o['mAP_50']:.3f})")
    if verdict == "FAIL":
        print("→ measure-first: 미달이므로 configs/finetune/*.py로 파인튜닝 검토")
    else:
        print("→ measure-first: 달성이므로 파인튜닝 없이 그대로 배포 가능")


if __name__ == "__main__":
    sys.exit(main())
