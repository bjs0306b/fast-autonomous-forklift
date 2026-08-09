"""스테이션 라이브 뷰 (전복 등급판) — 발표 영상 녹화 전용.

`station_live_view.py`와 배선은 같고 **상단 패널만 다르다.** 기본 라이브 뷰는
`load: BALANCED/ECCENTRIC`(편하중)을 그리는데, 발표 슬라이드가 보여주려는 것은
**전복 등급 SAFE/WARNING/DANGER**다. 둘은 다른 질문이라 서로 대체되지 않는다
(`station/tipping.py` 도크스트링 참조) — 편하중은 *화물이 자기 폭 대비 쏠렸나*,
전복은 *무게중심이 파렛트 지지면을 벗어나나*.

그래서 화면에는 전복 등급을 크게 그리고, 편하중은 참고로 작게 남긴다.

⚠️ **표시 전용이다. 백엔드로 아무것도 보내지 않는다.**

⚠️ **`serve.py --listen`과 동시에 쓰지 말 것.** 윈도우에서 카메라·COM 포트는
   배타적이라 이쪽이 물고 있으면 트리거 순간 측정이 실패한다.

⚠️ **녹화할 때는 `--nova`를 켤 것.** 끄면 거리가 고정 가정값(150cm)이라 화면 치수가
   틀린 채 그럴듯하게 표시된다 — 그 화면을 "오차 0.66mm" 슬라이드 옆에 붙이면 안 된다.

    python scripts/station_live_view_tipping.py --nova --record demo_warning.mp4
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

import cv2  # noqa: E402

from perception.tfnova import Measurement, MeasurementUnreliable, TfNova  # noqa: E402
from station.annotate import BOX_COLOR, PALLET_COLOR, PANEL_BG, _label  # noqa: E402
from station.config import StationConfig  # noqa: E402
from station.detector import OnnxDetector  # noqa: E402
from station.pipeline import build_payload  # noqa: E402
from station.serve import capture_backend  # noqa: E402
from station.tilt import estimate_roll_deg  # noqa: E402

PATH_COLOR = {"onboard": (120, 220, 120), "local": (80, 180, 255), "none": (80, 80, 240)}

# 등급 색 — tip_calib.py와 같은 값을 쓴다(같은 등급이 도구마다 다른 색이면 헷갈린다).
LEVEL_COLOR = {"safe": (80, 200, 80), "warning": (0, 200, 255), "danger": (60, 60, 255)}

PROBE_FRAMES = 6
"""녹화 fps를 재기 위해 앞에서 흘려보내는 프레임 수(버퍼에 담아뒀다 flush)."""


class _Recorder:
    """오버레이 화면을 mp4로 남긴다.

    `--fps`는 요청 상한일 뿐이라 그대로 writer에 적으면 재생 속도가 왜곡된다
    (요청 3fps / 실제 2.07fps면 1.45배 빨리감기). 앞 `PROBE_FRAMES`장으로 실제
    주기를 재서 연다.
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
            f = self._buf[0]
            self._open(f.shape[1], f.shape[0])
        if self._writer is not None:
            self._writer.release()
        return n


def annotate_tipping(frame, payload: dict):
    """bbox + 상단 패널. 편하중 자리에 전복 등급을 그린다."""
    img = frame.copy()
    det = payload["detection"]

    for b in det["boxes"]:
        x, y, w, h = b["bbox_px"]
        cv2.rectangle(img, (x, y), (x + w, y + h), BOX_COLOR, 3)
        _label(img, f"box {b['score']:.2f}", x, y, BOX_COLOR)

    if det["pallet"]:
        x, y, w, h = det["pallet"]["bbox_px"]
        cv2.rectangle(img, (x, y), (x + w, y + h), PALLET_COLOR, 3)
        _label(img, f"pallet {det['pallet']['score']:.2f}", x, y, PALLET_COLOR)

    lines: list[tuple[str, tuple]] = [(f"status: {payload['status']}", (240, 240, 240))]
    if payload.get("distance"):
        lines.append((f"distance: {payload['distance']['front_cm']} cm", (240, 240, 240)))
    if payload.get("dimensions"):
        d = payload["dimensions"]
        line = (f"W {d['width_cm']} x H {d['height_cm']} cm"
                f"  (mini {d['miniature_width_mm']} x {d['miniature_height_mm']} mm)")
        if d.get("tilt_deg"):
            line += f"   roll {d['tilt_deg']:+.1f} deg corrected"
        lines.append((line, (240, 240, 240)))

    # --- 전복 등급 (이 화면의 주인공) ---
    tip = payload.get("tipping") or {}
    if tip.get("assessable"):
        level = tip["level"]
        text = (f"TIPPING: {level.upper()}"
                f"   offset {tip['support_offset']:.0%} of pallet half-width"
                f"   margin {tip['margin']:.0%}")
        if not tip.get("static_stable", True):
            text += "   [COM OUTSIDE PALLET]"
        lines.append((text, LEVEL_COLOR.get(level, (240, 240, 240))))
    else:
        # 판정 불가를 빈칸으로 두면 "안전하다"로 오해된다 — 이유까지 찍는다.
        lines.append((f"TIPPING: N/A ({tip.get('reason', 'unknown')})", (80, 80, 240)))

    # 편하중은 다른 질문이므로 지우지 않고 참고로 남긴다.
    if payload.get("load_balance"):
        lb = payload["load_balance"]
        state = ("ECCENTRIC " + "/".join(lb["direction"])) if lb["eccentric"] else "BALANCED"
        lines.append((f"load: {state}  (ratio_x {lb['ratio_x']})", (190, 190, 190)))

    # 패널은 상단에 — 파렛트는 늘 프레임 하단이라 하단 패널은 그걸 가린다.
    font, fs, ft, pad, lh = cv2.FONT_HERSHEY_SIMPLEX, 1.0, 2, 14, 42
    panel_h = pad * 2 + lh * len(lines)
    overlay = img.copy()
    cv2.rectangle(overlay, (0, 0), (img.shape[1], panel_h), PANEL_BG, -1)
    img = cv2.addWeighted(overlay, 0.75, img, 0.25, 0)
    for i, (line, color) in enumerate(lines):
        cv2.putText(img, line, (pad, pad + lh * (i + 1) - 10), font, fs, color, ft)
    return img


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="스테이션 라이브 뷰 — 전복 등급판(표시 전용)")
    ap.add_argument("--infer-url", help="온보드 추론 서버. 없으면 노트북에서 추론")
    ap.add_argument("--distance", type=float, default=150.0,
                    help="표시용 고정 거리(cm). 녹화에는 --nova를 쓸 것")
    ap.add_argument("--nova", action="store_true",
                    help="TF-Nova를 실제로 읽어 치수를 맞춘다. serve.py --listen과 같이 쓰지 말 것")
    ap.add_argument("--fps", type=float, default=3.0, help="추론 주기 상한")
    ap.add_argument("--window", default="FAST station (tipping)", help="창 제목")
    ap.add_argument("--save-dir", type=Path, help="헤드리스일 때 프레임 저장")
    ap.add_argument("--record", type=Path, help="오버레이 화면을 mp4로 녹화")
    ap.add_argument("--frames", type=int, default=0, help="N장 처리 후 종료(0=무한)")
    a = ap.parse_args(argv)

    if a.record and not a.nova:
        # 조용히 넘어가면 틀린 치수가 발표 자료에 박힌다.
        print(f"⚠️ --record인데 --nova가 없다 — 거리 {a.distance}cm는 가정값이고 "
              f"화면 치수는 틀린다. 발표용이면 --nova를 켤 것.")

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
        print(f"카메라 index {cfg.camera_index} 안 열림 (다른 창이 물고 있는지 확인)")
        return 1
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, cfg.frame_width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, cfg.frame_height)
    if a.save_dir:
        a.save_dir.mkdir(parents=True, exist_ok=True)

    for _ in range(cfg.warmup_frames):        # 자동 노출 안정화
        cap.read()

    distance = Measurement(distance_cm=a.distance, std_cm=0.0, frames_used=0, frames_seen=0)
    sensor = None
    if a.nova:
        try:
            sensor = TfNova(cfg.tfnova_port).__enter__()
            print(f"거리계: {cfg.tfnova_port} 열림 — 실측값으로 치수를 낸다")
        except Exception as e:
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
                try:
                    distance = sensor.measure(0.15, scale=cfg.tfnova_scale,
                                              offset_cm=cfg.tfnova_offset_cm)
                except MeasurementUnreliable:
                    pass    # 빔이 잠깐 빗나가는 건 흔하다 — 직전 값 유지

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
            view = annotate_tipping(frame, payload)

            path = getattr(detector, "last_path", "local")
            ms = getattr(detector, "last_inference_ms", None)
            took = (time.perf_counter() - t0) * 1000
            tag = (f"inference: {'ONBOARD (Jetson)' if path == 'onboard' else 'LOCAL (laptop)'}"
                   + (f"  {ms:.0f}ms" if ms else "") + f"   loop {took:.0f}ms")
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
            sensor.__exit__(None, None, None)    # 포트를 놓아야 --listen이 다시 잡는다
        cv2.destroyAllWindows()
        if rec:
            rec.close()
            print(f"녹화 저장: {a.record}  ({rec.fps:.1f}fps 실측)")
    print(f"프레임 {n}장")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
