"""온보드 TRT 종단 지연 측정 (S15P11A304-68 완료조건 ③).

엔진 단독(trtexec) 10.18ms와 달리, **전처리(letterbox·정규화·회전) + 추론 + 후처리**를
파이썬 파이프라인으로 묶은 종단 시간을 잰다 — 실제 온보드가 겪는 지연이다.
카메라가 없으면 이미지 폴더를 반복 입력한다(순수 연산 지연).

    # 젯슨에서, ai/ 를 PYTHONPATH에 두고
    PYTHONPATH=src python3 scripts/onboard_trt_bench.py \
        --engine ~/trt_test/onboard_s640_ep116_fp16.engine \
        --plugin ~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so \
        --images ~/S15P11A304/ai/data/raw/onboard/eval_20260730 --runs 200
"""
from __future__ import annotations

import argparse
import time
from pathlib import Path

import cv2
import numpy as np

from perception.trt_detector import TrtDetector


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="온보드 TRT 종단 지연 측정")
    ap.add_argument("--engine", required=True)
    ap.add_argument("--plugin", required=True)
    ap.add_argument("--images", type=Path, required=True)
    ap.add_argument("--runs", type=int, default=200)
    ap.add_argument("--warmup", type=int, default=30)
    ap.add_argument("--rotate180", action="store_true")
    ap.add_argument("--score", type=float, default=0.4)
    a = ap.parse_args(argv)

    det = TrtDetector(a.engine, a.plugin, score_threshold=a.score,
                      class_names=("pallet", "hole"), rotate180=a.rotate180)

    files = sorted(a.images.glob("*.jpg"))[:64]
    frames = [cv2.imread(str(p)) for p in files]
    frames = [f for f in frames if f is not None]
    if not frames:
        print("이미지가 없습니다"); return 1
    print(f"프레임 {len(frames)}장 순환 · warmup {a.warmup} · 측정 {a.runs}")

    for i in range(a.warmup):
        det.detect(frames[i % len(frames)])

    lat, ndet = [], []
    for i in range(a.runs):
        f = frames[i % len(frames)]
        t0 = time.perf_counter()
        d = det.detect(f)
        lat.append((time.perf_counter() - t0) * 1000)
        ndet.append(len(d))
    lat = np.array(lat)

    print(f"\n종단 지연 (전처리+추론+후처리):")
    print(f"  mean   {lat.mean():.2f} ms")
    print(f"  median {np.median(lat):.2f} ms")
    print(f"  p90    {np.percentile(lat, 90):.2f} ms")
    print(f"  p99    {np.percentile(lat, 99):.2f} ms")
    print(f"  min/max {lat.min():.2f} / {lat.max():.2f} ms")
    print(f"  FPS    {1000 / lat.mean():.1f}")
    print(f"  검출 평균 {np.mean(ndet):.1f}개/프레임")
    print(f"\nG4 (≤100ms): {'PASS' if lat.mean() <= 100 else 'FAIL'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
