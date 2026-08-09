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

## 거리계를 기본으로 안 읽는 이유 — COM 포트가 배타적이다

화면 치수는 거리에 비례하므로 TF-Nova를 읽는 게 정확하다. 그런데 **윈도우 COM 포트는
한 프로세스만 잡는다.** 라이브 뷰가 COM3를 물고 있으면 트리거가 떨어진 순간
`serve.py --listen`이 포트를 못 열어 **측정 자체가 실패한다.** 화면 숫자 하나를 맞추자고
저장되는 측정을 날리는 셈이라, 기본은 `--distance` 고정값으로 두고 `--nova`를 준
경우에만 읽는다(라이브 뷰 단독 실행 — 발표 영상 녹화 등).
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

import cv2  # noqa: E402

from perception.tfnova import Measurement, MeasurementUnreliable, TfNova  # noqa: E402
from station.annotate import annotate  # noqa: E402
from station.config import StationConfig  # noqa: E402
from station.detector import OnnxDetector  # noqa: E402
from station.pipeline import build_payload  # noqa: E402
from station.serve import capture_backend  # noqa: E402
from station.tilt import estimate_roll_deg  # noqa: E402

PATH_COLOR = {"onboard": (120, 220, 120), "local": (80, 180, 255), "none": (80, 80, 240)}

PROBE_FRAMES = 6
"""녹화 fps를 재기 위해 앞에서 흘려보내는 프레임 수. 이 동안은 버퍼에 담아뒀다 flush한다."""


class _Recorder:
    """오버레이 화면을 mp4로 남긴다.

    ⚠️ **`--fps`를 그대로 쓰면 재생 속도가 틀린다.** 그건 요청 상한일 뿐이고 실제
    주기는 추론 왕복·WiFi에 좌우된다(원격 실측 284ms → 3.5fps 상한인데 실제는 더 느릴
    수 있다). 상한으로 적어두면 느리게 찍힌 영상이 빨리 감긴 것처럼 재생돼 "얼마나
    부드럽게 도는가"를 보여주려던 영상이 오히려 사실을 왜곡한다.

    그래서 앞 `PROBE_FRAMES`장으로 **실제 주기를 재고** 그 값으로 writer를 연다.
    재는 동안의 프레임은 버려지지 않고 버퍼에 담겼다가 flush된다.
    """

    def __init__(self, path: Path, fallback_fps: float) -> None:
        self.path = path
        self.fallback_fps = fallback_fps
        self._writer = None
        self._buf: list = []
        self._t: list[float] = []
        self.fps = 0.0

    def add(self, frame) -> None:
        self._t.append(time.perf_counter())
        if self._writer is None:
            self._buf.append(frame.copy())
            if len(self._buf) >= PROBE_FRAMES:
                self._open(frame.shape[1], frame.shape[0])
            return
        self._writer.write(frame)

    def _open(self, w: int, h: int) -> None:
        span = self._t[-1] - self._t[0]
        # 측정에 실패하면(순간적으로 0에 가까운 간격) 상한값으로 되돌린다.
        self.fps = (len(self._t) - 1) / span if span > 0.05 else self.fallback_fps
        self.fps = max(1.0, min(30.0, self.fps))
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._writer = cv2.VideoWriter(
            str(self.path), cv2.VideoWriter_fourcc(*"mp4v"), self.fps, (w, h))
        for f in self._buf:
            self._writer.write(f)
        self._buf.clear()

    def close(self) -> int:
        n = len(self._t)
        if self._writer is None and self._buf:
            # PROBE_FRAMES를 못 채우고 끝났다 — 짧아도 남긴다.
            f = self._buf[0]
            self._open(f.shape[1], f.shape[0])
        if self._writer is not None:
            self._writer.release()
        return n


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="스테이션 라이브 뷰 (표시 전용)")
    ap.add_argument("--infer-url", help="온보드 추론 서버. 없으면 노트북에서 추론")
    ap.add_argument("--distance", type=float, default=150.0,
                    help="표시용 고정 거리(cm). 기본은 TF-Nova를 읽지 않는다 — 이유는 "
                         "--nova 설명 참조")
    ap.add_argument("--nova", action="store_true",
                    help="TF-Nova를 실제로 읽어 치수를 맞춘다. ⚠️ **`serve.py --listen`과 "
                         "같이 쓰지 말 것** — 윈도우 COM 포트는 배타적이라 이쪽이 물고 "
                         "있으면 트리거 순간 측정이 포트를 못 열어 실패한다. 라이브 뷰만 "
                         "단독으로 돌릴 때(발표 영상 녹화 등) 쓴다")
    ap.add_argument("--fps", type=float, default=3.0, help="추론 주기 상한")
    ap.add_argument("--window", default="FAST station", help="창 제목")
    ap.add_argument("--save-dir", type=Path, help="헤드리스일 때 프레임 저장")
    ap.add_argument("--record", type=Path, help="오버레이 화면을 mp4로 녹화")
    ap.add_argument("--frames", type=int, default=0, help="N장 처리 후 종료(0=무한)")
    a = ap.parse_args(argv)

    cfg = StationConfig()
    local = OnnxDetector(cfg.model_path, cfg.input_size, cfg.score_threshold,
                         cfg.class_names, cfg.norm_mean, cfg.norm_std,
                         class_thresholds=cfg.class_score_thresholds)
    if a.infer_url:
        from station.remote_detector import RemoteDetector
        detector = RemoteDetector(a.infer_url, input_size=cfg.input_size,
                                  local_detector=local,
                                  score_threshold=cfg.score_threshold,
                                  class_thresholds=cfg.class_score_thresholds)
        h = detector.health()
        print(f"추론: 보드 {a.infer_url} — {'연결됨' if h else '⚠️ 응답 없음(로컬 폴백 예정)'}")
    else:
        detector = local
        print("추론: 노트북(로컬)")

    cap = cv2.VideoCapture(cfg.camera_index, capture_backend())
    if not cap.isOpened():
        print(f"카메라 index {cfg.camera_index} 안 열림 (serve.py --probe로 확인)")
        return 1
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, cfg.frame_width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, cfg.frame_height)
    if a.save_dir:
        a.save_dir.mkdir(parents=True, exist_ok=True)

    for _ in range(cfg.warmup_frames):        # 자동 노출 안정화
        cap.read()

    fixed = Measurement(distance_cm=a.distance, std_cm=0.0,
                        frames_used=0, frames_seen=0)
    distance = fixed
    sensor = None
    if a.nova:
        try:
            sensor = TfNova(cfg.tfnova_port).__enter__()
            print(f"거리계: {cfg.tfnova_port} 열림 — 실측값으로 치수를 낸다")
        except Exception as e:
            # 포트를 못 잡는 흔한 이유가 `--listen`이 이미 물고 있는 경우다. 조용히
            # 고정 거리로 넘어가면 화면 치수가 틀린 채 그럴듯해 보이므로 크게 알린다.
            print(f"⚠️ 거리계 {cfg.tfnova_port} 못 엶({e}) — 고정 {a.distance}cm로 표시한다")
    period = 1.0 / a.fps if a.fps > 0 else 0.0
    rec = _Recorder(a.record, a.fps) if a.record else None
    if rec:
        print(f"녹화: {a.record}")
    n = 0
    try:
        while a.frames == 0 or n < a.frames:
            t0 = time.perf_counter()
            ok, frame = cap.read()
            if not ok:
                print("프레임 캡처 실패")
                break

            if sensor is not None:
                # 표시용이라 0.5초(측정용)까지 안 쓴다 — 루프가 그만큼 느려진다.
                # 실패하면 직전 값을 유지한다(빔이 잠깐 빗나가는 건 흔하다).
                try:
                    distance = sensor.measure(0.15, scale=cfg.tfnova_scale,
                                              offset_cm=cfg.tfnova_offset_cm)
                except MeasurementUnreliable:
                    pass

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
            if rec:
                rec.add(view)
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
        if sensor is not None:
            # 포트를 놓아야 `--listen`이 다시 잡을 수 있다.
            sensor.__exit__(None, None, None)
        cv2.destroyAllWindows()
        if rec:
            rec.close()
            print(f"녹화 저장: {a.record}  ({rec.fps:.1f}fps 실측)")
    print(f"프레임 {n}장")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
