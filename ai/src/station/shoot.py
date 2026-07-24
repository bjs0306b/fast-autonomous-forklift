"""데이터셋 촬영 도구 — 파인튜닝·평가셋용 프레임 수집.

**추론에 쓸 카메라로 찍는다.** 해상도 자체는 학습 시 리사이즈되므로 중요하지 않지만,
렌즈 왜곡·색감·화이트밸런스·뷰포인트는 도메인 그대로 남는다. 그래서 StationConfig의
카메라 인덱스·해상도를 그대로 쓰고, 다르면 경고한다 (serve.py와 같은 방침).

    python src/station/shoot.py --out data/raw/rig/20260724 --prefix pallet
    python src/station/shoot.py --auto 1.5          # 1.5초마다 자동 저장으로 시작
    python src/station/shoot.py --burst 5           # B키 한 번에 5장

키:
    SPACE  1장 저장          B  버스트 저장(--burst 장)
    A      자동 저장 토글     U  마지막 저장 취소
    Q/ESC  종료

파일명은 ``<prefix>_<YYYYmmdd-HHMMSS>_<연번4자리>.jpg``이고, 같은 폴더의
``session.csv``에 연번·파일명·저장 시각·모드를 남긴다 (촬영 조건 메모용).
"""

from __future__ import annotations

import argparse
import csv
import datetime as _dt
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402

from station.config import StationConfig  # noqa: E402

PREVIEW_WIDTH = 960          # 미리보기만 축소 — 저장은 원본 해상도
HUD_BG = (30, 30, 30)


def open_camera(cfg: StationConfig, index: int) -> cv2.VideoCapture:
    cap = cv2.VideoCapture(index, cv2.CAP_DSHOW)
    if not cap.isOpened():
        raise RuntimeError(f"카메라 index {index}를 열 수 없습니다 "
                           f"(serve.py --probe로 확인)")
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, cfg.frame_width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, cfg.frame_height)
    for _ in range(cfg.warmup_frames):   # 자동 노출 안정화 (실측: 안 하면 어둡다)
        cap.read()
    return cap


def save(frame, path: Path) -> bool:
    """cv2.imwrite는 Windows 비ASCII 경로에서 조용히 실패한다 → imencode+tofile."""
    ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 95])
    if ok:
        buf.tofile(str(path))
    return ok


def draw_hud(frame, lines: list[str]):
    view = cv2.resize(frame, (PREVIEW_WIDTH,
                              int(frame.shape[0] * PREVIEW_WIDTH / frame.shape[1])))
    font, fs, ft, lh, pad = cv2.FONT_HERSHEY_SIMPLEX, 0.6, 1, 26, 10
    panel_h = pad * 2 + lh * len(lines)
    overlay = view.copy()
    cv2.rectangle(overlay, (0, 0), (view.shape[1], panel_h), HUD_BG, -1)
    view = cv2.addWeighted(overlay, 0.7, view, 0.3, 0)
    for i, line in enumerate(lines):
        cv2.putText(view, line, (pad, pad + lh * (i + 1) - 8),
                    font, fs, (240, 240, 240), ft)
    return view


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="데이터셋 촬영 (파인튜닝·평가셋용)")
    parser.add_argument("--out", type=Path,
                        default=Path(f"data/raw/rig/{_dt.date.today():%Y%m%d}"),
                        help="저장 폴더 (없으면 만든다)")
    parser.add_argument("--prefix", default="rig", help="파일명 접두사")
    parser.add_argument("--camera", type=int, help="카메라 인덱스 (기본: config)")
    parser.add_argument("--burst", type=int, default=5, help="B키 1회에 저장할 장수")
    parser.add_argument("--auto", type=float, metavar="SEC",
                        help="자동 저장 간격(초) — 지정하면 자동 모드로 시작")
    args = parser.parse_args(argv)

    cfg = StationConfig()
    index = args.camera if args.camera is not None else cfg.camera_index
    args.out.mkdir(parents=True, exist_ok=True)

    cap = open_camera(cfg, index)
    saved: list[Path] = []
    auto_interval = args.auto
    auto_on = args.auto is not None
    next_auto = time.monotonic()
    stamp = f"{_dt.datetime.now():%Y%m%d-%H%M%S}"
    log_path = args.out / "session.csv"
    new_log = not log_path.exists()

    print(f"저장 폴더: {args.out.resolve()}")
    print("SPACE 저장 / B 버스트 / A 자동 / U 취소 / Q 종료")

    with open(log_path, "a", newline="", encoding="utf-8") as fp:
        log = csv.writer(fp)
        if new_log:
            log.writerow(["index", "file", "saved_at", "mode"])

        def store(frame, mode: str) -> None:
            path = args.out / f"{args.prefix}_{stamp}_{len(saved) + 1:04d}.jpg"
            if not save(frame, path):
                print("저장 실패", file=sys.stderr)
                return
            saved.append(path)
            log.writerow([len(saved), path.name,
                          _dt.datetime.now().isoformat(timespec="seconds"), mode])
            fp.flush()

        try:
            while True:
                ok, frame = cap.read()
                if not ok:
                    print("프레임 캡처 실패", file=sys.stderr)
                    break

                h, w = frame.shape[:2]
                warn = ("" if (w, h) == (cfg.frame_width, cfg.frame_height)
                        else f"  !! {cfg.frame_width}x{cfg.frame_height} 아님")
                cv2.imshow("shoot", draw_hud(frame, [
                    f"saved {len(saved)}   {w}x{h}{warn}",
                    f"auto {'ON ' + format(auto_interval, '.1f') + 's' if auto_on else 'OFF'}"
                    f"   burst {args.burst}   [SPACE/B/A/U/Q]",
                ]))

                if auto_on and time.monotonic() >= next_auto:
                    store(frame, "auto")
                    next_auto = time.monotonic() + (auto_interval or 1.0)

                key = cv2.waitKey(1) & 0xFF
                if key in (ord("q"), 27):
                    break
                if key == ord(" "):
                    store(frame, "single")
                elif key == ord("b"):
                    for _ in range(args.burst):   # 손 흔들림·조명 흔들림으로 다양성 확보
                        ok, f2 = cap.read()
                        if ok:
                            store(f2, "burst")
                elif key == ord("a"):
                    if auto_interval is None:
                        auto_interval = 1.5   # --auto 없이 켜면 기본 간격
                    auto_on = not auto_on
                    next_auto = time.monotonic()
                elif key == ord("u") and saved:
                    saved.pop().unlink(missing_ok=True)   # 로그는 남긴다(추적용)
        finally:
            cap.release()
            cv2.destroyAllWindows()

    print(f"\n총 {len(saved)}장 저장 → {args.out.resolve()}")
    print(f"기록: {log_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
