"""전복 임계 캘리브레이션 — 실제로 밀어보며 사람 판단과 이탈률을 짝지어 기록한다.

`tipping.py`의 임계(warning 0.60 / danger 0.85)는 물리 한계(1.0) 전에 여유를 둔
**합리적 추정**이지 실측값이 아니다. 실제로 박스를 조금씩 밀면서 "사람이 위험하다고
느끼는 지점"의 이탈률을 모아야 임계를 근거 있게 정할 수 있다.

라이브 화면에 이탈률·등급을 띄우고, 사람이 상태를 눈으로 판단해 키로 기록한다.

    python src/station/tip_calib.py --distance 200
    python src/station/tip_calib.py                # TF-Nova로 거리 실측

키:
    1  안정 — 아직 여유 있어 보임
    2  주의 — 아슬아슬해 보임
    3  위험 — 곧 넘어질 것 같음 / 실제로 넘어짐
    U  마지막 기록 취소
    Q  저장하고 종료

기록은 ``tip_calib.csv``(이탈률·종횡비·돌출·사람판단)로 남고, 프레임도 같이 저장한다.
⚠️ 실제로 넘어뜨릴 필요 없다 — 손으로 받치고 "넘어질 것 같은" 지점만 표시하면 된다.
"""

from __future__ import annotations

import argparse
import csv
import datetime as _dt
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402

from perception.tfnova import Measurement  # noqa: E402
from station import tipping  # noqa: E402
from station.config import StationConfig  # noqa: E402
from station.detector import OnnxDetector  # noqa: E402
from station.pipeline import on_pallet  # noqa: E402
from station.serve import capture, read_distance  # noqa: E402

LABELS = {ord("1"): "안정", ord("2"): "주의", ord("3"): "위험"}
COLORS = {"safe": (80, 200, 80), "warning": (0, 200, 255), "danger": (60, 60, 255)}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="전복 임계 캘리브레이션")
    parser.add_argument("--out", type=Path,
                        default=Path(f"data/raw/tip_calib/{_dt.date.today():%Y%m%d}"))
    parser.add_argument("--distance", type=float,
                        help="고정 거리(cm). 없으면 TF-Nova 실측")
    parser.add_argument("--camera", type=int, help="카메라 인덱스 (기본: config)")
    args = parser.parse_args(argv)

    cfg = StationConfig()
    args.out.mkdir(parents=True, exist_ok=True)
    detector = OnnxDetector(cfg.model_path, cfg.input_size, cfg.score_threshold,
                            cfg.class_names, cfg.norm_mean, cfg.norm_std,
                            class_thresholds=cfg.class_score_thresholds)

    # 카메라를 못 열면 조용히 끝내지 말고 분명히 알린다 — 캘리브레이션 도구가
    # 아무 말 없이 꺼지면 원인을 못 찾는다(실측에서 겪음).
    index = args.camera if args.camera is not None else cfg.camera_index
    cap = cv2.VideoCapture(index, cv2.CAP_DSHOW)
    if not cap.isOpened():
        print(f"카메라 index {index}를 열 수 없습니다. "
              f"`python src/station/serve.py --probe`로 인덱스를 확인하고 "
              f"--camera 로 지정하세요.", file=sys.stderr)
        return 1
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, cfg.frame_width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, cfg.frame_height)
    for _ in range(cfg.warmup_frames):
        cap.read()
    ok, probe = cap.read()
    if not ok or probe is None:
        print(f"카메라 index {index}가 열렸지만 프레임을 못 읽습니다 "
              f"(다른 프로그램이 점유 중일 수 있음).", file=sys.stderr)
        cap.release()
        return 1

    log_path = args.out / "tip_calib.csv"
    new = not log_path.exists()
    saved = []

    print(f"저장: {args.out.resolve()}")
    print("1 안정 / 2 주의 / 3 위험 / U 취소 / Q 종료")

    with open(log_path, "a", newline="", encoding="utf-8") as fp:
        log = csv.writer(fp)
        if new:
            log.writerow(["idx", "file", "판단", "이탈률", "여유", "종횡비",
                          "돌출", "현등급", "높이cm", "폭cm", "거리cm", "시각"])
        try:
            while True:
                ok, frame = cap.read()
                if not ok:
                    break

                dets = detector.detect(frame)
                pallets = [d for d in dets if d.label == "pallet"
                           and d.score >= cfg.threshold_for("pallet")]
                boxes = [d.box for d in dets if d.label == "box"
                         and d.score >= cfg.threshold_for("box")]
                pal = max(pallets, key=lambda d: d.score).box if pallets else None
                boxes = on_pallet(boxes, pal)

                info, t = None, {}
                if pal and boxes:
                    from station import measure
                    from station.pipeline import hull
                    load = hull(boxes)
                    dist = args.distance or 200.0
                    h_cm = measure.height_cm(load.h, dist, cfg.calib.fy)
                    w_cm = measure.width_cm(load.w, dist, cfg.calib.fx)
                    t = tipping.assess_tipping(boxes, pal, h_cm, w_cm)
                    info = (h_cm, w_cm, dist)

                # --- 오버레이 ---
                view = frame.copy()
                if pal:
                    x, y, w, h = (int(v) for v in (pal.x, pal.y, pal.w, pal.h))
                    cv2.rectangle(view, (x, y), (x + w, y + h), (60, 140, 255), 3)
                    cx = int(pal.center_x)
                    cv2.line(view, (cx, y - 40), (cx, y + h), (60, 140, 255), 2)
                for b in boxes:
                    x, y, w, h = (int(v) for v in (b.x, b.y, b.w, b.h))
                    cv2.rectangle(view, (x, y), (x + w, y + h), (80, 200, 80), 2)
                if boxes and pal:
                    com = int(sum(b.center_x for b in boxes) / len(boxes))
                    cv2.line(view, (com, int(pal.y) - 120), (com, int(pal.y) + int(pal.h)),
                             (0, 0, 255), 3)

                lines = [f"기록 {len(saved)}   박스 {len(boxes)}   파렛트 {'O' if pal else 'X'}"]
                if t.get("assessable"):
                    lines.append(f"이탈률 {t['support_offset']:.3f}  여유 {t['margin']:.0%}"
                                 f"  종횡비 {t['aspect_ratio']}  돌출 {t['overhang']:.3f}")
                    lines.append(f"현재등급 {t['level'].upper()}")
                else:
                    lines.append("판정 불가 — 파렛트/박스 확인")
                col = COLORS.get(t.get("level"), (240, 240, 240))

                sc = 1100 / view.shape[1]
                view = cv2.resize(view, (1100, int(view.shape[0] * sc)))
                cv2.rectangle(view, (0, 0), (view.shape[1], 26 * len(lines) + 12),
                              (30, 30, 30), -1)
                for i, ln in enumerate(lines):
                    cv2.putText(view, ln, (10, 24 + 26 * i), cv2.FONT_HERSHEY_SIMPLEX,
                                0.65, col if i == 2 else (240, 240, 240), 2)
                cv2.imshow("tip_calib", view)

                key = cv2.waitKey(1) & 0xFF
                if key in (ord("q"), 27):
                    break
                if key in LABELS and t.get("assessable"):
                    idx = len(saved) + 1
                    name = f"tip_{idx:03d}.jpg"
                    ok2, buf = cv2.imencode(".jpg", frame,
                                            [cv2.IMWRITE_JPEG_QUALITY, 92])
                    if ok2:
                        buf.tofile(str(args.out / name))
                    h_cm, w_cm, dist = info
                    log.writerow([idx, name, LABELS[key], t["support_offset"],
                                  t["margin"], t["aspect_ratio"], t["overhang"],
                                  t["level"], round(h_cm, 1), round(w_cm, 1), dist,
                                  _dt.datetime.now().isoformat(timespec="seconds")])
                    fp.flush()
                    saved.append(name)
                    print(f"  [{LABELS[key]}] 이탈률 {t['support_offset']:.3f} → {name}")
                elif key == ord("u") and saved:
                    (args.out / saved.pop()).unlink(missing_ok=True)
                    print("  마지막 기록 취소")
        finally:
            cap.release()
            cv2.destroyAllWindows()

    print(f"\n{len(saved)}건 기록 → {log_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
