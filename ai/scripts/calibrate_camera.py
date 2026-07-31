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
BAD_IMAGE_PX = 1.0      # 장별 오차가 이보다 크면 흔들린 장으로 보고 뺀다
MIN_SHOTS = 12

STILL_PX = 1.0
"""저장 조건: 직전 프레임 대비 코너 이동이 이보다 작아야 한다(= 보드가 멈춰 있다).

**이게 없으면 캘리브레이션이 통째로 망가진다.** 2026-07-31 실측: 움직이는 중에 저장한
20장에서 선명도가 낮은 장일수록 재투영 오차가 컸다 — 선명도 70~208인 장이 5~6.5px,
455~557인 장이 0.44~0.86px. 전체 RMS 3.65px로 못 쓰는 값이 나왔고, 자유 파라미터를
줄여도(광학중심 고정 등) RMS가 안 내려갔다. 자세 다양성이 아니라 **블러**가 원인이었다.

코너는 서브픽셀로 잡히므로 몇 px만 흘러도 그대로 오차가 된다."""


def pose_stats(corners, w: int, h: int) -> dict:
    """한 장의 자세를 숫자로 — 촬영 중 피드백과 사후 판정이 같은 기준을 쓴다.

    `tilt`가 핵심이다. **화면 안에서 보드를 빙글빙글 돌리는 것(roll)은 도움이 안 된다** —
    새로운 구속을 주지 않는다. 필요한 건 **판을 앞뒤로 젖히는 것**(원근)이고, 그때
    윗변/아랫변, 좌변/우변 길이가 달라진다. 그 비의 1로부터의 거리가 `tilt`다.
    """
    pts = corners.reshape(-1, 2)
    grid = pts.reshape(BOARD[1], BOARD[0], 2)
    x0, y0 = pts.min(0)
    x1, y1 = pts.max(0)
    top = np.linalg.norm(grid[0, -1] - grid[0, 0])
    bot = np.linalg.norm(grid[-1, -1] - grid[-1, 0])
    left = np.linalg.norm(grid[-1, 0] - grid[0, 0])
    right = np.linalg.norm(grid[-1, -1] - grid[0, -1])
    return {
        "cx": float((x0 + x1) / 2 / w),
        "cy": float((y0 + y1) / 2 / h),
        "area": float((x1 - x0) * (y1 - y0) / (w * h)),
        "tilt": float(max(abs(top / max(bot, 1e-6) - 1), abs(left / max(right, 1e-6) - 1))),
    }


# 합격 기준 — 이걸 못 채우면 초점거리가 안 풀리거나 부정확하다.
NEED_AREA = 0.25        # 최소 한 장은 화면의 25% 이상을 덮어야 한다
NEED_TILT = 0.25        # 최소 한 장은 이만큼 젖혀져야 한다
NEED_SPREAD_X = 0.35    # 가로 위치 범위
NEED_SPREAD_Y = 0.30    # 세로 위치 범위


def coverage_report(stats: list[dict]) -> list[str]:
    """부족한 것만 문장으로 돌려준다. 비어 있으면 합격."""
    if not stats:
        return ["한 장도 없다"]
    miss = []
    if max(s["area"] for s in stats) < NEED_AREA:
        miss.append(f"보드를 **더 크게**(가까이) — 최대 {max(s['area'] for s in stats)*100:.0f}%"
                    f", {NEED_AREA*100:.0f}% 이상 필요")
    if max(s["tilt"] for s in stats) < NEED_TILT:
        miss.append(f"보드를 **앞뒤로 젖혀서** — 최대 {max(s['tilt'] for s in stats):.2f}"
                    f", {NEED_TILT:.2f} 이상 필요 (화면 안에서 돌리는 건 소용없다)")
    sx = max(s["cx"] for s in stats) - min(s["cx"] for s in stats)
    sy = max(s["cy"] for s in stats) - min(s["cy"] for s in stats)
    if sx < NEED_SPREAD_X:
        miss.append(f"**좌우 끝**에도 대기 — 범위 {sx:.2f}, {NEED_SPREAD_X:.2f} 이상 필요")
    if sy < NEED_SPREAD_Y:
        miss.append(f"**위아래 끝**에도 대기 — 범위 {sy:.2f}, {NEED_SPREAD_Y:.2f} 이상 필요")
    return miss


def stats_for_dir(d: Path) -> list[dict]:
    """폴더의 모든 장을 다시 읽어 자세 통계를 낸다(이어 찍기 판정용)."""
    out = []
    for p in sorted(d.glob("calib_*.jpg")):
        img = cv2.imread(str(p))
        if img is None:
            continue
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        c = find_corners(gray)
        if c is not None:
            out.append(pose_stats(c, gray.shape[1], gray.shape[0]))
    return out


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
    print("  ⓪ **자세를 잡고 잠깐 멈춘다.** 움직이는 중에는 저장하지 않는다.")
    print("     보드가 흔들리면 코너가 흐려져 오차가 통째로 커진다(실측: RMS 3.6px).")
    print("  ① 보드를 **앞뒤로 젖힌다**(위쪽 끝을 카메라 쪽/반대쪽으로 눕히기).")
    print("     ⚠️ 화면 안에서 **빙글빙글 돌리는 건 소용없다** — 새 정보가 안 생긴다.")
    print(f"  ② 보드를 **크게**(가까이) — 화면의 {NEED_AREA*100:.0f}% 이상을 덮는 장이 있어야 한다")
    print("  ③ 화면 **네 귀퉁이·위아래 끝**에도 오게 한다")
    print("  ④ 보드는 평평해야 한다(휘면 그만큼 오차)\n")
    print("  ▶ 흐름: 자세 잡기 → **멈추기** → 자동 저장 → 다음 자세\n")

    for _ in range(15):
        cap.read()

    # 이어 찍기 — 기존 장을 덮어쓰지 않는다. 부족한 자세만 보태는 것이 정상 경로다.
    existing = sorted(a.out.glob("calib_*.jpg"))
    start = 0
    if existing:
        start = max(int(p.stem.rsplit("_", 1)[-1]) for p in existing) + 1
        print(f"기존 {len(existing)}장 발견 — calib_{start:03d} 부터 이어서 저장한다.\n")

    saved = 0
    last_save = 0.0
    prev_corners = None
    stats: list[dict] = []
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
                prev_corners = None
                print("\r  보드 안 보임 — 각도·조명 조정                        ",
                      end="", flush=True)
                continue

            # **멈췄는지부터 본다.** 흔들리는 중에 저장하면 그 장이 통째로 오염된다.
            motion = None
            if prev_corners is not None and prev_corners.shape == corners.shape:
                motion = float(np.median(np.linalg.norm(
                    corners.reshape(-1, 2) - prev_corners.reshape(-1, 2), axis=1)))
            prev_corners = corners
            if motion is None or motion > STILL_PX:
                bar = "움직임" if motion is None else f"움직임 {motion:4.1f}px"
                print(f"\r  ✋ {bar} — **멈추세요**                              ",
                      end="", flush=True)
                continue

            # 같은 자세가 연속 저장되는 것을 막는다(멈춰 있으면 계속 조건을 만족하므로).
            if now - last_save < a.interval:
                print(f"\r  ✅ 저장됨 — **다음 자세로** ({a.interval - (now-last_save):.1f}s)",
                      end="", flush=True)
                continue
            path = a.out / f"calib_{start + saved:03d}.jpg"
            cv2.imwrite(str(path), frame)
            saved += 1
            last_save = now
            s = pose_stats(corners, gray.shape[1], gray.shape[0])
            stats.append(s)
            flag = ""
            if s["area"] < NEED_AREA:
                flag += " 📏더 가까이"
            if s["tilt"] < NEED_TILT:
                flag += " 📐더 젖혀서"
            print(f"\r  [{saved:2d}/{a.shots}] 크기 {s['area']*100:4.1f}%  "
                  f"젖힘 {s['tilt']:4.2f}  위치({s['cx']:.2f},{s['cy']:.2f})  "
                  f"흔들림 {motion:.2f}px{flag}        ")
    except KeyboardInterrupt:
        print("\n중단됨")
    finally:
        cap.release()

    total = len(sorted(a.out.glob("calib_*.jpg")))
    print(f"\n이번 {saved}장 저장 → {a.out} (폴더 총 {total}장)")
    if total < MIN_SHOTS:
        print(f"⚠️ {MIN_SHOTS}장 미만이면 결과가 불안정하다. 더 찍는 것을 권한다.")

    # 판정은 **폴더 전체** 기준이다 — 이어 찍었다면 기존 장이 이미 채웠을 수 있다.
    miss = coverage_report(stats_for_dir(a.out) or stats)
    if miss:
        print("\n⚠️ **이대로 풀면 값이 안 맞는다.** 아래를 채워 다시 찍을 것:")
        for m in miss:
            print(f"   · {m}")
        print("\n   (같은 폴더에 이어 찍으면 기존 장과 합쳐진다 — --out 을 그대로 주고 재실행)")
        return 2
    print("✅ 자세 다양성 합격")
    print(f"다음: python3 calibrate_camera.py solve --dir {a.out}")
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

    stats = [pose_stats(c, size[0], size[1]) for c in img_points]
    miss = coverage_report(stats)

    rms, K, dist, rvecs, tvecs = cv2.calibrateCamera(obj_points, img_points, size, None, None)

    # 장별 오차 — 전체 RMS만 보면 "몇 장이 망쳤나 / 전부 나쁜가"를 구분할 수 없다.
    per = []
    for i in range(len(used)):
        proj, _ = cv2.projectPoints(obj_points[i], rvecs[i], tvecs[i], K, dist)
        per.append(float(cv2.norm(img_points[i], proj, cv2.NORM_L2) / np.sqrt(len(proj))))

    keep = [i for i, e in enumerate(per) if e <= BAD_IMAGE_PX]
    dropped = [(used[i], per[i]) for i in range(len(used)) if i not in keep]
    if dropped and len(keep) >= MIN_SHOTS:
        # 흔들린 장을 빼고 다시 푼다. 코너는 서브픽셀이라 블러 한 장이 전체를 끌어내린다.
        print(f"\n흔들린 장 {len(dropped)}개 제외하고 재계산"
              f" (오차 >{BAD_IMAGE_PX}px): "
              + ", ".join(f"{n}({e:.1f})" for n, e in dropped[:6])
              + (" ..." if len(dropped) > 6 else ""))
        rms, K, dist, _, _ = cv2.calibrateCamera(
            [obj_points[i] for i in keep], [img_points[i] for i in keep], size, None, None)
        used = [used[i] for i in keep]
    elif dropped:
        print(f"\n⚠️ 흔들린 장이 {len(dropped)}개나 되어 제외하면 표본이 부족하다"
              f"({len(keep)}장 < {MIN_SHOTS}장). 다시 찍어야 한다.")

    fx, fy, cx, cy = K[0, 0], K[1, 1], K[0, 2], K[1, 2]

    print(f"\n{'='*56}")
    print(f"재투영 오차(RMS) : {rms:.3f} px   "
          f"{'✅ 양호' if rms <= GOOD_RMS else '⚠️ 크다'}")
    print(f"{'='*56}")
    # **합격 판정은 RMS와 광학중심이 한다.** 자세 다양성은 그 둘이 나쁠 때 원인을 짚는
    # 보조 지표다 — 커버리지가 조금 모자라도 RMS가 좋고 광학중심이 화면 중앙 근처면
    # 해가 잘 잡힌 것이다. 반대로 커버리지만 보고 "쓰지 말라"고 하면 멀쩡한 값을 버린다.
    center_off = max(abs(cx - size[0] / 2) / size[0], abs(cy - size[1] / 2) / size[1])
    sane = rms <= GOOD_RMS and center_off <= 0.12
    if not sane:
        print("\n⚠️ **이 값을 쓰지 말 것.**"
              + (f" 광학중심이 화면 중앙에서 {center_off*100:.0f}% 벗어났다"
                 f"(허용 12%) — 해가 발산했다는 신호다." if center_off > 0.12 else ""))
        for m in (miss or ["자세는 기준을 넘었으나 RMS가 크다 — 보드가 휘었는지 확인"]):
            print(f"   · {m}")
    elif miss:
        print("\n참고: 자세 다양성은 기준에 조금 못 미치지만 "
              f"RMS {rms:.3f}px·광학중심 중앙 근접이라 **값은 쓸 만하다.**")
        for m in miss:
            print(f"   · (권장) {m}")
    if not sane:
        print(f"   현재: 최대크기 {max(s['area'] for s in stats)*100:.0f}% · "
              f"최대젖힘 {max(s['tilt'] for s in stats):.2f} · "
              f"가로범위 {max(s['cx'] for s in stats)-min(s['cx'] for s in stats):.2f} · "
              f"세로범위 {max(s['cy'] for s in stats)-min(s['cy'] for s in stats):.2f}")
        print(f"   → 같은 폴더에 이어 찍으면 합쳐진다: shoot --out {a.dir}\n")
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
