"""스테이션 라이브 뷰 — 카메라 화면에 검출·치수를 실시간으로 그린다 (S15P11A304-91).

시연에서 "박스·파렛트가 실시간으로 잡히는 것"을 보여주기 위한 화면이다.
`annotate.annotate()`가 이미 그리는 일을 하므로 그걸 루프로 돌린다.

    # 스테이션 노트북에서
    python src/station/../scripts/station_live_view.py --infer-url http://<젯슨>:8877

⚠️ **여기서 나온 값은 백엔드로 보내지 않는다.** 보여주기 전용이다.

측정과 표시를 나눈 이유가 정확도다. 저장되는 측정은 **트리거 시점에 한 번**만 재야
한다 — 화물을 놓는 도중·미는 도중 프레임은 사람 손이 걸리거나 화물이 프레임 가장자리에
있어 hull이 오염된다(CLAUDE.md 측정 조건, 가장자리에서 +6.2mm 실측). 계속 추론해서
계속 보내면 그중 뭐가 진짜인지 구분할 방법이 없다.

**표시용 추론도 보드에서 한다**(`--infer-url`). "모델이 보드에서 돈다"가 요지인데
화면만 노트북이 그리면 취지가 흐려진다. 화면 좌상단에 어느 경로인지 항상 띄운다.

⚠️ 원격 추론은 왕복이 있어 프레임률이 낮다(실측 284ms ≈ 3.5fps). `--fps`로 조절한다.
   빠르게 돌릴수록 WiFi·보드를 더 먹고, 보드는 주행 중 Nav2·SLAM도 함께 돌린다.
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

import cv2  # noqa: E402

from perception.tfnova import Measurement  # noqa: E402
from station.annotate import annotate  # noqa: E402
from station.config import StationConfig  # noqa: E402
from station.detector import OnnxDetector  # noqa: E402
from station.pipeline import build_payload  # noqa: E402
from station.tilt import estimate_roll_deg  # noqa: E402

PATH_COLOR = {"onboard": (120, 220, 120), "local": (80, 180, 255), "none": (80, 80, 240)}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="스테이션 라이브 뷰 (표시 전용)")
    ap.add_argument("--infer-url", help="온보드 추론 서버. 없으면 노트북에서 추론")
    ap.add_argument("--distance", type=float, default=150.0,
                    help="표시용 고정 거리(cm). 라이브 뷰는 TF-Nova를 읽지 않는다 — "
                         "거리계는 측정 시점에만 쓰고, 화면은 bbox를 보이는 게 목적이다")
    ap.add_argument("--fps", type=float, default=3.0, help="추론 주기 상한")
    ap.add_argument("--window", default="FAST station", help="창 제목")
    ap.add_argument("--save-dir", type=Path, help="헤드리스일 때 프레임 저장")
    ap.add_argument("--frames", type=int, default=0, help="N장 처리 후 종료(0=무한)")
    a = ap.parse_args(argv)

    cfg = StationConfig()
    local = OnnxDetector(cfg.model_path, cfg.input_size, cfg.score_threshold,
                         cfg.class_names, cfg.norm_mean, cfg.norm_std,
                         class_thresholds=cfg.class_score_thresholds)
    if a.infer_url:
        from station.remote_detector import RemoteDetector
        detector = RemoteDetector(a.infer_url, input_size=cfg.input_size,
                                  local_detector=local)
        h = detector.health()
        print(f"추론: 보드 {a.infer_url} — {'연결됨' if h else '⚠️ 응답 없음(로컬 폴백 예정)'}")
    else:
        detector = local
        print("추론: 노트북(로컬)")

    cap = cv2.VideoCapture(cfg.camera_index, cv2.CAP_DSHOW)
    if not cap.isOpened():
        print(f"카메라 index {cfg.camera_index} 안 열림 (serve.py --probe로 확인)")
        return 1
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, cfg.frame_width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, cfg.frame_height)
    if a.save_dir:
        a.save_dir.mkdir(parents=True, exist_ok=True)

    for _ in range(cfg.warmup_frames):        # 자동 노출 안정화
        cap.read()

    distance = Measurement(distance_cm=a.distance, std_cm=0.0,
                           frames_used=0, frames_seen=0)
    period = 1.0 / a.fps if a.fps > 0 else 0.0
    n = 0
    try:
        while a.frames == 0 or n < a.frames:
            t0 = time.perf_counter()
            ok, frame = cap.read()
            if not ok:
                print("프레임 캡처 실패")
                break

            dets = detector.detect(frame)
            pallets = [d for d in dets if d.label == "pallet"
                       and d.score >= cfg.threshold_for("pallet")]
            tilt = None
            if pallets:
                occ = [d.box for d in dets if d.label == "box"
                       and d.score >= cfg.threshold_for("box")]
                tilt = estimate_roll_deg(
                    frame, max(pallets, key=lambda d: d.score).box, occluders=occ)
            payload = build_payload(dets, distance, cfg, tilt_deg=tilt)
            view = annotate(frame, payload)

            path = getattr(detector, "last_path", "local")
            ms = getattr(detector, "last_inference_ms", None)
            took = (time.perf_counter() - t0) * 1000
            tag = (f"inference: {'ONBOARD (Jetson)' if path == 'onboard' else 'LOCAL (laptop)'}"
                   + (f"  {ms:.0f}ms" if ms else "") + f"   loop {took:.0f}ms")
            # 추론 경로를 화면에 항상 띄운다 — 폴백으로 떨어졌는데 모르고 "보드가 한다"고
            # 설명하면 시연이 사실과 달라진다.
            cv2.putText(view, tag, (14, view.shape[0] - 18),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.9,
                        PATH_COLOR.get(path, (200, 200, 200)), 2)

            n += 1
            if a.save_dir:
                cv2.imwrite(str(a.save_dir / f"live_{n:04d}.jpg"), view)
            else:
                cv2.imshow(a.window, view)
                if cv2.waitKey(1) & 0xFF in (ord("q"), 27):
                    break

            rest = period - (time.perf_counter() - t0)
            if rest > 0:
                time.sleep(rest)
    except KeyboardInterrupt:
        print("\n중단")
    finally:
        cap.release()
        cv2.destroyAllWindows()
    print(f"프레임 {n}장")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
