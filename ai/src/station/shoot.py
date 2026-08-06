"""데이터셋 촬영 도구 — 파인튜닝·평가셋용 프레임 수집.

**추론에 쓸 카메라로 찍는다.** 해상도 자체는 학습 시 리사이즈되므로 중요하지 않지만,
렌즈 왜곡·색감·화이트밸런스·뷰포인트는 도메인 그대로 남는다. 그래서 StationConfig의
카메라 인덱스·해상도를 그대로 쓰고, 다르면 경고한다 (serve.py와 같은 방침).

    python src/station/shoot.py --out data/raw/rig/20260724 --prefix pallet
    python src/station/shoot.py --auto 1.5          # 1.5초마다 자동 저장으로 시작
    python src/station/shoot.py --burst 5           # B키 한 번에 5장
    python src/station/shoot.py --rotate180         # 카메라를 뒤집어 장착한 경우

``--rotate180``은 카메라를 물리적으로 뒤집어 단 경우에 쓴다. 뒤집힌 채로 그냥 찍으면
라벨링이 어려워지고(사람이 뒤집힌 화면을 봐야 한다), 정립으로 라벨된 공개 데이터셋과
방향이 어긋나며, "화물은 파렛트 위에 얹힌다" 같은 하류 기하 가정이 반대가 된다.
⚠️ **추론 경로에도 같은 회전을 넣어야 한다** — 한쪽만 돌리면 학습·추론 도메인이 어긋난다.

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

from perception.tfnova import MeasurementUnreliable, TfNova  # noqa: E402
from station.config import StationConfig  # noqa: E402

PREVIEW_WIDTH = 960          # 미리보기만 축소 — 저장은 원본 해상도
HUD_BG = (30, 30, 30)


def open_camera(cfg: StationConfig, index: int, width: int, height: int) -> cv2.VideoCapture:
    # ⚠️ **CAP_DSHOW 는 윈도우 전용이다**(DirectShow). 리눅스에서 지정하면 카메라가
    # 멀쩡한데도 열리지 않는다 — 젯슨에서 온보드 촬영을 하려다 걸렸다(2026-08-04).
    # 스테이션(윈도우)에서는 DSHOW 가 필요하다: 기본 백엔드(MSMF)는 해상도 설정이
    # 잘 안 먹고 초기화가 느리다. 그래서 플랫폼으로 갈라 준다.
    backends = [cv2.CAP_DSHOW, cv2.CAP_ANY] if sys.platform == "win32" else [cv2.CAP_ANY]
    cap = None
    for backend in backends:
        cap = cv2.VideoCapture(index, backend)
        if cap.isOpened():
            break
        cap.release()
    if cap is None or not cap.isOpened():
        raise RuntimeError(f"카메라 index {index}를 열 수 없습니다 "
                           f"(윈도우: serve.py --probe / 리눅스: ls /dev/video*)")
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
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
    # 온보드 -s 촬영(S15P11A304-144)은 스테이션 BRIO와 다른 USB 카메라(1280x960)를 쓴다.
    # 해상도는 학습 시 어차피 리사이즈되므로 도메인만 맞으면 되고, 여기서 실제 카메라
    # 네이티브 해상도를 지정해 매 프레임 mismatch 경고가 뜨지 않게 한다.
    parser.add_argument("--width", type=int, help="캡처 폭 (기본: config)")
    parser.add_argument("--height", type=int, help="캡처 높이 (기본: config)")
    parser.add_argument("--burst", type=int, default=5, help="B키 1회에 저장할 장수")
    parser.add_argument("--auto", type=float, metavar="SEC",
                        help="자동 저장 간격(초) — 지정하면 자동 모드로 시작")
    # SSH 로 젯슨에 붙어 촬영할 때는 창을 띄울 수 없다(GTK 초기화 실패).
    # 미리보기를 끄고 --auto 로만 저장한다. 종료는 Ctrl-C.
    parser.add_argument("--headless", action="store_true",
                        help="미리보기 창 없이 --auto 로만 저장 (SSH·젯슨용). "
                             "키 입력이 없으므로 --auto 가 필수, 종료는 Ctrl-C")
    # 치수 평가셋(스테이션)은 프레임마다 TF-Nova 실측 거리가 있어야 오프라인 치수
    # 검증이 된다. 이 플래그를 켜면 저장할 때마다 거리를 읽어 session.csv에 남긴다.
    # (온보드 -s 촬영은 detection이라 거리 불필요 → 끄고 쓴다.)
    parser.add_argument("--rotate180", action="store_true",
                        help="카메라를 뒤집어 장착했을 때 — 프레임을 180° 돌려 "
                             "정립으로 저장한다 (⚠️ 추론 경로에도 같은 회전을 넣을 것)")
    parser.add_argument("--tfnova", action="store_true",
                        help="저장 시 TF-Nova 거리를 읽어 session.csv에 기록 (치수 평가셋용)")
    args = parser.parse_args(argv)

    cfg = StationConfig()
    index = args.camera if args.camera is not None else cfg.camera_index
    width = args.width if args.width is not None else cfg.frame_width
    height = args.height if args.height is not None else cfg.frame_height
    args.out.mkdir(parents=True, exist_ok=True)

    cap = open_camera(cfg, index, width, height)

    # TF-Nova는 저장 시점에만 읽는다 — 미리보기 루프에서 매 프레임 읽으면(0.5초) 뷰가
    # 버벅인다. 포트 열기 실패해도 거리 없이 계속 찍는다(촬영을 막지 않는다).
    sensor = None
    if args.tfnova:
        try:
            sensor = TfNova(cfg.tfnova_port)
            print(f"TF-Nova 연결({cfg.tfnova_port}) — 저장마다 거리 기록")
        except Exception as e:
            print(f"[TF-Nova 열기 실패] {e} — 거리 없이 진행", file=sys.stderr)

    def read_distance() -> tuple[float | None, float | None]:
        if sensor is None:
            return None, None
        try:
            m = sensor.measure(cfg.tfnova_seconds, scale=cfg.tfnova_scale,
                               offset_cm=cfg.tfnova_offset_cm)
            return round(m.distance_cm, 1), round(m.std_cm, 2)
        except MeasurementUnreliable as e:
            print(f"[거리 측정 불가] {e}", file=sys.stderr)
            return None, None
        except Exception as e:
            print(f"[TF-Nova 오류] {e}", file=sys.stderr)
            return None, None

    saved: list[Path] = []
    auto_interval = args.auto
    auto_on = args.auto is not None
    next_auto = time.monotonic()
    stamp = f"{_dt.datetime.now():%Y%m%d-%H%M%S}"
    last_dist: float | None = None      # HUD 표시용
    log_path = args.out / "session.csv"
    new_log = not log_path.exists()

    headless = args.headless
    if headless and not auto_on:
        # 키 입력이 없으므로 --auto 없이는 **한 장도 안 찍힌다.** 조용히 도는 대신
        # 여기서 끊는다 — 다 찍은 줄 알고 나중에 빈 폴더를 발견하는 게 더 나쁘다.
        raise SystemExit("--headless 는 --auto 가 필요하다 (예: --headless --auto 2)")

    print(f"저장 폴더: {args.out.resolve()}")
    if headless:
        print(f"headless — {auto_interval:.1f}초마다 자동 저장. 종료는 Ctrl-C")
    else:
        print("SPACE 저장 / B 버스트 / A 자동 / U 취소 / Q 종료")

    with open(log_path, "a", newline="", encoding="utf-8") as fp:
        log = csv.writer(fp)
        if new_log:
            log.writerow(["index", "file", "saved_at", "mode",
                          "distance_cm", "distance_std"])

        def store(frame, mode: str, dist: float | None, std: float | None) -> None:
            path = args.out / f"{args.prefix}_{stamp}_{len(saved) + 1:04d}.jpg"
            if not save(frame, path):
                print("저장 실패", file=sys.stderr)
                return
            saved.append(path)
            log.writerow([len(saved), path.name,
                          _dt.datetime.now().isoformat(timespec="seconds"), mode,
                          "" if dist is None else dist,
                          "" if std is None else std])
            fp.flush()

        try:
            while True:
                ok, frame = cap.read()
                if not ok:
                    print("프레임 캡처 실패", file=sys.stderr)
                    break

                if args.rotate180:
                    frame = cv2.rotate(frame, cv2.ROTATE_180)

                h, w = frame.shape[:2]
                warn = ("" if (w, h) == (width, height)
                        else f"  !! {width}x{height} 아님")
                dist_txt = (f"dist {last_dist}cm" if last_dist is not None
                            else ("dist --" if sensor else "dist off"))
                if not headless:
                    cv2.imshow("shoot", draw_hud(frame, [
                        f"saved {len(saved)}   {w}x{h}{warn}   {dist_txt}",
                        f"auto {'ON ' + format(auto_interval, '.1f') + 's' if auto_on else 'OFF'}"
                        f"   burst {args.burst}   [SPACE/B/A/U/Q]",
                    ]))

                if auto_on and time.monotonic() >= next_auto:
                    d, s = read_distance()
                    last_dist = d if d is not None else last_dist
                    store(frame, "auto", d, s)
                    next_auto = time.monotonic() + (auto_interval or 1.0)
                    if headless:
                        # 화면이 없으니 저장 사실을 stdout 으로 알린다. 이게 없으면
                        # "돌고는 있는데 찍히는지 모르겠는" 상태가 된다.
                        print(f"[{len(saved):4d}] 저장 {saved[-1].name}  {w}x{h}{warn}  {dist_txt}",
                              flush=True)

                if headless:
                    # imshow 를 안 하면 waitKey 가 이벤트를 못 돌려 키 입력이 안 온다.
                    # headless 는 --auto 전용이므로 그냥 프레임 간격만 쉰다(Ctrl-C 로 종료).
                    time.sleep(0.03)
                    continue

                key = cv2.waitKey(1) & 0xFF
                if key in (ord("q"), 27):
                    break
                if key == ord(" "):
                    d, s = read_distance()
                    last_dist = d if d is not None else last_dist
                    store(frame, "single", d, s)
                elif key == ord("b"):
                    d, s = read_distance()    # 버스트는 거리 한 번만 (짧은 동안 불변)
                    last_dist = d if d is not None else last_dist
                    for _ in range(args.burst):   # 손 흔들림·조명 흔들림으로 다양성 확보
                        ok, f2 = cap.read()
                        if ok:
                            store(f2, "burst", d, s)
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
            if sensor is not None:
                sensor.close()

    print(f"\n총 {len(saved)}장 저장 → {args.out.resolve()}")
    print(f"기록: {log_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
