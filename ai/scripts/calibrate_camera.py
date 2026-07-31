"""카메라 내부 파라미터 캘리브레이션 — 촬영과 풀이 (S15P11A304-152).

포크 정렬의 `yaw_deg`·`distance_mm`이 **초점거리 하나에 걸려 있다.** 그게 없으면
152 DoD의 "요각 ±5°"를 숫자로 판정할 수 없다(`AlignError.yaw_deg`가 None인 이유).

스테이션이 BRIO에 쓴 것과 **같은 체커보드**를 쓴다 — 9×6 내부 코너, 한 칸 24mm
(`docs/ai/measurement/camera-calibration.md`). 새로 인쇄할 필요 없다.

    # 1단계: 젯슨에서 촬영 (체커보드 검출 여부를 매 프레임 알려준다)
    PYTHONPATH=src python3 scripts/calibrate_camera.py shoot \\
        --out data/calib/onboard --camera 0 --width 1280 --height 720 --rotate180

    # 2단계: 풀이 (노트북에서 해도 된다)
    python scripts/calibrate_camera.py solve --dir data/calib/onboard

⚠️ **추론과 같은 해상도로 찍어야 한다.** 초점거리는 픽셀 단위라 해상도가 바뀌면
비례해서 달라진다. 젯슨 카메라는 **1280×720이 최대**다(1280×800 지원 안 함) —
`onboard_fork_align_node.py`의 기본값과 맞춰 720으로 찍는다.

⚠️ **뒤집어 단 카메라는 `--rotate180`을 켜고 찍는다.** 추론 경로가 회전을 넣으므로
캘리브레이션도 같은 방향이어야 `cx`·`cy`가 맞는다(`fx`·`fy`는 회전과 무관하다).

⚠️ **정면으로만 찍으면 안 된다.** 체커보드가 화면과 평행하기만 하면 방정식이 축퇴돼
초점거리와 거리를 서로 구분하지 못한다. **기울여서** 찍는 것이 핵심이다.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import cv2
import numpy as np

# 스테이션과 같은 보드 (docs/ai/measurement/camera-calibration.md)
BOARD = (9, 6)          # 내부 코너 수 (가로, 세로)
SQUARE_MM = 24.0

CRITERIA = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.001)
GOOD_RMS = 0.5          # 재투영 오차 합격선(px)
MIN_SHOTS = 12


def find_corners(gray, board=BOARD):
    ok, corners = cv2.findChessboardCorners(
        gray, board,
        cv2.CALIB_CB_ADAPTIVE_THRESH + cv2.CALIB_CB_NORMALIZE_IMAGE + cv2.CALIB_CB_FAST_CHECK)
    if not ok:
        return None
    return cv2.cornerSubPix(gray, corners, (11, 11), (-1, -1), CRITERIA)


def cmd_shoot(a) -> int:
    """체커보드를 찾은 프레임만 저장한다 — 못 찾은 사진은 어차피 못 쓴다."""
    cap = cv2.VideoCapture(a.camera)
    if not cap.isOpened():
        print(f"카메라 {a.camera}를 열 수 없습니다.")
        return 1
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, a.width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, a.height)
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    print(f"카메라 {a.camera}: {w}x{h}"
          + ("" if (w, h) == (a.width, a.height) else "  ⚠️ 요청과 다르다"))
    a.out.mkdir(parents=True, exist_ok=True)

    print(f"\n체커보드 {BOARD[0]}x{BOARD[1]} 내부 코너, 한 칸 {SQUARE_MM:.0f}mm")
    print(f"목표 {a.shots}장. 검출된 프레임만 저장한다.\n")
    print("찍는 요령 — 이대로 안 하면 값이 안 맞는다:")
    print("  · 보드를 **기울여서** 찍는다(정면만 찍으면 초점거리가 안 풀린다)")
    print("  · 화면 네 귀퉁이·중앙에 골고루 오게 한다(왜곡 계수가 가장자리에서 나온다)")
    print("  · 거리도 바꾼다(가까이·멀리)")
    print("  · 보드는 평평해야 한다(휘면 그만큼 오차)\n")

    for _ in range(15):
        cap.read()

    saved = 0
    last_save = 0.0
    try:
        while saved < a.shots:
            ok, frame = cap.read()
            if not ok:
                print("캡처 실패"); break
            if a.rotate180:
                frame = cv2.rotate(frame, cv2.ROTATE_180)
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            corners = find_corners(gray)
            now = time.time()
            if corners is None:
                print("\r  보드 안 보임 — 각도·조명 조정             ", end="", flush=True)
                continue
            # 연속 프레임이 거의 같은 자세라 중복이다. 간격을 둬서 자세를 바꾸게 한다.
            if now - last_save < a.interval:
                print(f"\r  ✅ 검출됨 — 자세 바꾸는 중 ({a.interval - (now-last_save):.1f}s)",
                      end="", flush=True)
                continue
            path = a.out / f"calib_{saved:03d}.jpg"
            cv2.imwrite(str(path), frame)
            saved += 1
            last_save = now
            print(f"\r  [{saved:2d}/{a.shots}] 저장 {path.name}"
                  f" — **자세를 바꾸세요**                    ")
    except KeyboardInterrupt:
        print("\n중단됨")
    finally:
        cap.release()

    print(f"\n{saved}장 저장 → {a.out}")
    if saved < MIN_SHOTS:
        print(f"⚠️ {MIN_SHOTS}장 미만이면 결과가 불안정하다. 더 찍는 것을 권한다.")
    else:
        print(f"다음: python scripts/calibrate_camera.py solve --dir {a.out}")
    return 0


def cmd_solve(a) -> int:
    files = sorted(a.dir.glob("*.jpg")) + sorted(a.dir.glob("*.png"))
    if not files:
        print(f"이미지가 없습니다: {a.dir}")
        return 1

    # 보드 좌표계의 3D 점 (z=0 평면). 단위는 mm — 그래야 tvec도 mm로 나온다.
    objp = np.zeros((BOARD[0] * BOARD[1], 3), np.float32)
    objp[:, :2] = np.mgrid[0:BOARD[0], 0:BOARD[1]].T.reshape(-1, 2) * SQUARE_MM

    obj_points, img_points, used, size = [], [], [], None
    for p in files:
        img = cv2.imread(str(p))
        if img is None:
            continue
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        if size is None:
            size = gray.shape[::-1]
        elif gray.shape[::-1] != size:
            print(f"⚠️ 해상도가 다른 이미지 건너뜀: {p.name} {gray.shape[::-1]} ≠ {size}")
            continue
        corners = find_corners(gray)
        if corners is None:
            print(f"  건너뜀(보드 못 찾음): {p.name}")
            continue
        obj_points.append(objp)
        img_points.append(corners)
        used.append(p.name)

    print(f"\n{len(used)}/{len(files)}장 사용, 해상도 {size[0]}x{size[1]}")
    if len(used) < MIN_SHOTS:
        print(f"⚠️ {MIN_SHOTS}장 미만 — 결과를 믿기 어렵다. 더 찍을 것.")
    if len(used) < 4:
        return 1

    rms, K, dist, _, _ = cv2.calibrateCamera(obj_points, img_points, size, None, None)
    fx, fy, cx, cy = K[0, 0], K[1, 1], K[0, 2], K[1, 2]

    print(f"\n{'='*56}")
    print(f"재투영 오차(RMS) : {rms:.3f} px   "
          f"{'✅ 양호' if rms <= GOOD_RMS else '⚠️ 크다 — 보드가 휘었거나 자세가 단조로움'}")
    print(f"{'='*56}")
    print(f"  fx = {fx:8.2f} px      cx = {cx:8.2f} px")
    print(f"  fy = {fy:8.2f} px      cy = {cy:8.2f} px")
    print(f"  왜곡 = {np.round(dist.ravel(), 5).tolist()}")

    print(f"\n포크 정렬에 쓸 값 — **fx를 쓴다**")
    print(f"  구멍 두 개는 **가로로** 놓여 있고 `approach_px`가 가로 픽셀 거리라,")
    print(f"  yaw 유도식 tanθ = 2f/span · yaw_signal 의 f는 **가로 초점거리**다.")
    print(f"  (스테이션이 적재 높이에 fy를 쓴 것과 축이 다르다.)\n")
    print(f"  python3 scripts/onboard_fork_align_node.py ... --focal-px {fx:.1f}")

    out = a.dir / "calibration.json"
    out.write_text(json.dumps({
        "resolution": {"width": size[0], "height": size[1]},
        "board": {"inner_corners": list(BOARD), "square_mm": SQUARE_MM},
        "images_used": len(used),
        "rms_reprojection_px": round(float(rms), 4),
        "fx": round(float(fx), 2), "fy": round(float(fy), 2),
        "cx": round(float(cx), 2), "cy": round(float(cy), 2),
        "distortion": np.round(dist.ravel(), 6).tolist(),
        "focal_px_for_fork_align": round(float(fx), 1),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n저장 → {out}")

    print(f"\n⚠️ 이 값은 **{size[0]}x{size[1]}에서만** 유효하다. 다른 해상도로 추론하면")
    print(f"   fx를 가로 비율만큼 곱해 쓰거나 다시 찍어야 한다.")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="카메라 캘리브레이션")
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("shoot", help="체커보드 촬영(검출된 프레임만 저장)")
    s.add_argument("--out", type=Path, required=True)
    s.add_argument("--camera", type=int, default=0)
    s.add_argument("--width", type=int, default=1280)
    s.add_argument("--height", type=int, default=720)
    s.add_argument("--shots", type=int, default=20)
    s.add_argument("--interval", type=float, default=1.5,
                   help="저장 최소 간격(초) — 같은 자세 중복 방지")
    s.add_argument("--rotate180", action="store_true")
    s.set_defaults(func=cmd_shoot)

    v = sub.add_parser("solve", help="촬영분으로 내부 파라미터 계산")
    v.add_argument("--dir", type=Path, required=True)
    v.set_defaults(func=cmd_solve)

    a = ap.parse_args(argv)
    return a.func(a)


if __name__ == "__main__":
    raise SystemExit(main())
