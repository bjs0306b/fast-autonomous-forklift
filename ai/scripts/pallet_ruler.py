"""파렛트 거리·각도 읽기 — 정렬 임계를 실측으로 정하기 위한 자 (S15P11A304-152).

`fork_servo`의 `ALIGN_ENTER_MM`·`INSERT_ENTER_MM`은 지금 추정값이다. 실제로 얼마에서
정렬을 시작하고 얼마에서 진입해야 하는지는 **파렛트를 놓고 재봐야** 안다.

제어 노드 로그는 초당 10~20줄이라 눈으로 못 읽는다. 이 스크립트는 한 줄만 갱신하며
**최근 프레임의 중앙값**을 보여줘 숫자가 튀지 않는다.

    PYTHONPATH=src python3 scripts/pallet_ruler.py \\
        --engine ~/trt_test/onboard_s640_ep116_fp16.engine \\
        --plugin ~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so \\
        --camera 0 --rotate180 --focal-px 1277.7

Ctrl-C로 끝내면 요약이 나온다 — **구멍이 보인 가장 가까운 거리**가 곧
`INSERT_ENTER_MM`의 상한이다(그보다 가까우면 정렬 판정을 못 한다).
"""
from __future__ import annotations

import argparse
import statistics
import time
from collections import deque

import cv2

from perception.fork_align import TargetTracker
from perception.trt_detector import TrtDetector

WINDOW = 9      # 중앙값을 낼 프레임 수 — 홀수로 둬 중앙값이 실제 표본이 되게 한다


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="파렛트 거리·각도 자")
    ap.add_argument("--engine", required=True)
    ap.add_argument("--plugin", required=True)
    ap.add_argument("--camera", type=int, default=0)
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--rotate180", action="store_true")
    ap.add_argument("--focal-px", type=float, required=True,
                    help="calibrate_camera.py 가 준 fx")
    ap.add_argument("--fork-center-x", type=float, default=None)
    a = ap.parse_args(argv)

    cap = cv2.VideoCapture(a.camera)
    if not cap.isOpened():
        print(f"카메라 {a.camera}를 열 수 없습니다.")
        return 1
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, a.width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, a.height)
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

    det = TrtDetector(a.engine, a.plugin, class_names=("pallet", "hole"),
                      rotate180=a.rotate180)
    tracker = TargetTracker()

    print(f"\n카메라 {w}x{h} · fx {a.focal_px:.1f}\n")
    print("파렛트를 카메라 앞에 놓고 **거리를 바꿔가며** 숫자를 보세요.")
    print("멈춘 상태에서 읽어야 값이 안정됩니다. 끝내려면 Ctrl-C.\n")

    dists, yaws = deque(maxlen=WINDOW), deque(maxlen=WINDOW)
    nearest_with_holes = None     # 구멍이 보인 가장 가까운 거리
    farthest_seen = None          # 파렛트가 잡힌 가장 먼 거리
    seen_frames = lost_frames = 0

    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            dets = det.detect(frame)
            target = tracker.update(dets)
            if target is None:
                lost_frames += 1
                dists.clear(); yaws.clear()
                n_p = sum(1 for d in dets if d.label == "pallet")
                print(f"\r  파렛트 없음 (원시 검출 pallet {n_p}개)                    ",
                      end="", flush=True)
                time.sleep(0.02)
                continue

            err = target.error(image_width=w, fork_center_x=a.fork_center_x,
                               focal_px=a.focal_px)
            dists.append(err.distance_mm)
            yaws.append(err.yaw_deg)
            seen_frames += 1

            d = statistics.median(dists)
            y = statistics.median(yaws)
            if farthest_seen is None or d > farthest_seen:
                farthest_seen = d
            if nearest_with_holes is None or d < nearest_with_holes:
                nearest_with_holes = d

            print(f"\r  거리 {d:6.0f} mm ({d/10:5.1f} cm)   "
                  f"요각 {y:+6.1f}°   좌우 {err.lateral_ratio:+5.2f}   "
                  f"폭 {err.approach_px:5.0f}px      ", end="", flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        cap.release()

    print("\n\n" + "=" * 52)
    print(f"파렛트 인식 프레임 {seen_frames} / 미인식 {lost_frames}")
    if nearest_with_holes is not None:
        print(f"구멍까지 보인 **가장 가까운** 거리 : {nearest_with_holes:6.0f} mm "
              f"({nearest_with_holes/10:.1f} cm)")
        print(f"파렛트가 잡힌 **가장 먼** 거리     : {farthest_seen:6.0f} mm "
              f"({farthest_seen/10:.1f} cm)")
        print("\n임계 정하는 법:")
        print(f"  INSERT_ENTER_MM ≤ {nearest_with_holes:.0f}  "
              "(이보다 가까우면 구멍이 안 보여 정렬 판정 불가)")
        print(f"  ALIGN_ENTER_MM  ≤ {farthest_seen:.0f}  "
              "(여기부터 정렬 가능. 회전반경상 450~600mm 확보 권장)")
    else:
        print("파렛트를 한 번도 못 잡았다 — 거리·조명·rotate180 확인")
    print("=" * 52)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
