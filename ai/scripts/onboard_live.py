"""온보드 라이브 검출 — 젯슨 USB 카메라 + TRT 엔진 실시간 확인 (S15P11A304-68).

저장된 이미지 평가(G1~G3)와 종단 벤치(G4)는 끝났지만, **카메라에서 들어온 프레임으로
실제로 도는지**는 확인한 적이 없다. 이 스크립트가 그 관통을 본다:
카메라 → 전처리 → TRT → 검출 → (헤드리스면 파일 저장, 화면 있으면 표시).

    # 젯슨에서, ai/ 를 PYTHONPATH에 두고
    PYTHONPATH=src python3 scripts/onboard_live.py \
        --engine ~/trt_test/onboard_s640_ep116_fp16.engine \
        --plugin ~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so \
        --camera 0 --save-dir ~/live_out --frames 60

⚠️ **rotate180**: 카메라를 뒤집어 장착했다면 `--rotate180`. 학습은 정립 프레임이라
추론 입력도 정립이어야 한다 — 어긋나면 "아무것도 검출 안 됨"이 된다(runbook §6).
어느 쪽인지 모르면 일단 빼고 돌려서 저장된 이미지가 뒤집혔는지 눈으로 본다.

⚠️ 해상도는 학습·평가와 같은 **1280x800**을 요청한다. 다른 해상도로 들어오면
letterbox 비율이 달라져 검출이 나빠질 수 있다 — 실제 잡힌 해상도를 찍어준다.
"""
from __future__ import annotations

import argparse
import time
from pathlib import Path

import cv2

from perception.trt_detector import TrtDetector

COLOR = {"pallet": (255, 140, 60), "hole": (60, 60, 240)}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="온보드 라이브 검출")
    ap.add_argument("--engine", required=True)
    ap.add_argument("--plugin", required=True)
    ap.add_argument("--camera", type=int, default=0)
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=800)
    ap.add_argument("--score", type=float, default=0.4)
    ap.add_argument("--frames", type=int, default=60, help="처리할 프레임 수")
    ap.add_argument("--save-dir", type=Path, help="검출 오버레이 이미지 저장 폴더")
    ap.add_argument("--save-every", type=int, default=10, help="N프레임마다 저장")
    ap.add_argument("--rotate180", action="store_true")
    ap.add_argument("--show", action="store_true", help="화면 표시(디스플레이 있을 때)")
    a = ap.parse_args(argv)

    cap = cv2.VideoCapture(a.camera)
    if not cap.isOpened():
        print(f"카메라 {a.camera}를 열 수 없습니다. /dev/video* 확인", flush=True)
        return 1
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, a.width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, a.height)
    got_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    got_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    print(f"카메라 {a.camera}: 요청 {a.width}x{a.height} → 실제 {got_w}x{got_h}", flush=True)
    if (got_w, got_h) != (a.width, a.height):
        print("  ⚠️ 학습·평가와 다른 해상도다 — 검출이 나빠질 수 있다", flush=True)

    det = TrtDetector(a.engine, a.plugin, score_threshold=a.score,
                      class_names=("pallet", "hole"), rotate180=a.rotate180)
    if a.save_dir:
        a.save_dir.mkdir(parents=True, exist_ok=True)

    # 자동 노출 안정화 — 안 하면 초반 프레임이 어둡다(스테이션에서 실측된 함정)
    for _ in range(15):
        cap.read()

    lat, counts = [], []
    saved = 0
    try:
        for i in range(a.frames):
            ok, frame = cap.read()
            if not ok:
                print(f"프레임 {i} 캡처 실패", flush=True)
                break
            t0 = time.perf_counter()
            dets = det.detect(frame)
            lat.append((time.perf_counter() - t0) * 1000)
            counts.append(len(dets))

            n_p = sum(1 for d in dets if d.label == "pallet")
            n_h = sum(1 for d in dets if d.label == "hole")
            print(f"[{i:3d}] {lat[-1]:6.1f}ms  pallet {n_p}  hole {n_h}  "
                  + " ".join(f"{d.label}:{d.score:.2f}" for d in dets[:4]), flush=True)

            if (a.save_dir and i % a.save_every == 0) or a.show:
                view = cv2.rotate(frame, cv2.ROTATE_180) if a.rotate180 else frame.copy()
                for d in dets:
                    x, y, w, h = (int(v) for v in (d.box.x, d.box.y, d.box.w, d.box.h))
                    c = COLOR.get(d.label, (0, 255, 0))
                    cv2.rectangle(view, (x, y), (x + w, y + h), c, 2)
                    cv2.putText(view, f"{d.label} {d.score:.2f}", (x, max(y - 6, 12)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.6, c, 2)
                if a.save_dir and i % a.save_every == 0:
                    cv2.imwrite(str(a.save_dir / f"live_{i:04d}.jpg"), view)
                    saved += 1
                if a.show:
                    cv2.imshow("onboard", view)
                    if cv2.waitKey(1) & 0xFF == ord("q"):
                        break
    finally:
        cap.release()
        if a.show:
            cv2.destroyAllWindows()

    if lat:
        import statistics
        print(f"\n프레임 {len(lat)}장 · 지연 평균 {statistics.mean(lat):.1f}ms "
              f"({1000/statistics.mean(lat):.1f}fps)", flush=True)
        print(f"검출 평균 {statistics.mean(counts):.1f}개/프레임 "
              f"(0개인 프레임 {counts.count(0)}장)", flush=True)
        if a.save_dir:
            print(f"오버레이 {saved}장 → {a.save_dir}", flush=True)
        if counts.count(0) == len(counts):
            print("⚠️ 전 프레임 검출 0 — rotate180 방향이나 조명·거리를 의심할 것",
                  flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
