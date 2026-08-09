"""스테이션 측정 실행 — 하드웨어·모델을 파이프라인에 배선하는 진입점.

사용 (ai/ 에서, ai_env):
    python src/station/serve.py --once                 # 카메라+TF-Nova 실측 1회
    python src/station/serve.py --once --image x.jpg   # 저장된 사진으로 (카메라 없이)
    python src/station/serve.py --once --distance 150  # 거리 고정값으로 (센서 없이)
    python src/station/serve.py --probe                # 카메라 인덱스 확인용 프리뷰 저장

결과 JSON은 stdout(및 --out 파일)으로 낸다. 백엔드 전송(MQTT/HTTP)은 규격
합의(S15P11A304-91) 후 붙인다 — 지금은 출력까지가 범위.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
from contextlib import contextmanager
from pathlib import Path

# 직접 실행(python src/station/serve.py)도 되게 src를 경로에 얹는다
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# `ai/` 루트 — 기본 CA 경로처럼 레포 안 파일을 가리킬 때 쓴다.
# cwd 에 의존하면 실행 위치가 바뀔 때 조용히 못 찾는다(config.py 와 같은 이유).
_AI_ROOT = Path(__file__).resolve().parents[2]

import cv2  # noqa: E402

from perception.tfnova import Measurement, MeasurementUnreliable, TfNova  # noqa: E402
from station import livestream  # noqa: E402
from station.config import StationConfig  # noqa: E402
from station.detector import OnnxDetector  # noqa: E402
from station.pipeline import build_payload  # noqa: E402
from station.tilt import estimate_roll_deg  # noqa: E402


class Spans:
    """측정 판정 지연을 구간별로 재는 계측기.

    **왜 필요한가** — KPI(요구사항명세서 §측정 판정 지연)는 *"박스 도착 →
    치수·편하중 결과 ≤ 1초"* 로 정의돼 있는데, 그동안 그 자리에 쓰던 값은
    젯슨 서버가 **자기 추론 구간만 재서 돌려준** `inference_ms` 였다
    (`onboard_infer_server.py` 의 `detect_letterboxed()` 앞뒤). 정지 대기·
    프레임 인코딩·네트워크 왕복·거리 측정·치수 계산이 전부 빠져 있다.
    **정의와 다른 구간을 정의의 이름으로 쓰면 안 된다** — 그래서 여기서
    정의대로 다시 잰다.

    "박스 도착"의 해석이 둘이라 **둘 다 낸다.** 어느 쪽으로 물어도 답할 수 있어야 한다:

    - ``total_ms`` — 트리거 수신부터. **정지 대기를 포함**한다(최대 5초,
      `trigger.STILL_TIMEOUT_S`). 화물을 놓는 순간부터로 읽으면 이 값이다.
    - ``judge_ms`` — 화물이 멎은 뒤부터. **시스템이 판단에 쓴 시간**만이다.
      정지 대기는 사람이 화물을 내려놓는 물리 과정이라 시스템 지연이 아니라고
      보면 이 값이다.

    끝점은 **결과가 확정된 순간**(`build_payload` 반환)이다. 백엔드 REST 전송은
    "판정"이 아니므로 `spans_ms["전송"]` 으로 따로 낸다.

    `--once` 로 재면 카메라 워밍업(약 0.8초)이 `spans_ms["캡처"]` 에 들어간다.
    상시 모드(`--listen`)는 카메라를 미리 열어두므로 그 시간이 없다 —
    **두 모드의 값을 섞어 비교하지 말 것.** KPI 로 쓸 값은 `--listen` 쪽이다.
    """

    def __init__(self) -> None:
        self._t0 = time.perf_counter()
        self._judge_t0: float | None = None
        self.spans_ms: dict[str, float] = {}
        self.total_ms: float | None = None
        self.judge_ms: float | None = None

    @contextmanager
    def span(self, name: str):
        t = time.perf_counter()
        try:
            yield
        finally:
            # 예외가 나도 남긴다 — 실패한 측정이 어디서 오래 걸렸는지가 특히 궁금하다
            self.spans_ms[name] = round((time.perf_counter() - t) * 1000, 1)

    def start_judge(self) -> None:
        """정지 대기가 끝난 시점을 찍는다 — `judge_ms` 의 기준점."""
        self._judge_t0 = time.perf_counter()

    def finish(self) -> None:
        """결과가 확정된 순간을 찍는다. 두 번 불러도 마지막 호출이 이긴다."""
        now = time.perf_counter()
        self.total_ms = round((now - self._t0) * 1000, 1)
        if self._judge_t0 is not None:
            self.judge_ms = round((now - self._judge_t0) * 1000, 1)

    def as_dict(self) -> dict:
        return {
            "total_ms": self.total_ms,
            "judge_ms": self.judge_ms,
            "spans_ms": dict(self.spans_ms),
        }

    def summary(self) -> str:
        parts = " · ".join(f"{k} {v:.0f}ms" for k, v in self.spans_ms.items())
        head = f"[timing] 판정 총 {self.total_ms:.0f}ms" if self.total_ms is not None else "[timing]"
        if self.judge_ms is not None:
            head += f" (정지 후 {self.judge_ms:.0f}ms)"
        return f"{head}  —  {parts}"


def capture_backend() -> int:
    """이 OS 에서 쓸 OpenCV 캡처 백엔드.

    ⚠️ **백엔드를 한 값으로 박으면 다른 OS 에서 카메라가 아예 안 열린다.**
    2026-08-06 에 `CAP_DSHOW`(윈도우 전용)가 `CAP_V4L2`(리눅스 전용)로 바뀌면서
    **측정 PC 에서 `--check` 가 "index 0 안 열림"으로 죽었다.** 인덱스를 고쳐도
    소용없다 — 백엔드가 그 OS 에 없으면 어느 인덱스든 안 열린다.

    스테이션 측정은 윈도우 노트북에서 돌고(카메라·TF-Nova 가 거기 붙어 있다),
    같은 코드를 젯슨에서도 돌린다. 그래서 고르는 쪽이 아니라 **묻는 쪽**으로 둔다.

    `STATION_CAMERA_BACKEND` 로 강제할 수 있다(`dshow`/`v4l2`/`any`). 자동 판단이
    틀린 환경에서 코드를 고치지 않고 넘기기 위한 탈출구다.
    """
    forced = os.environ.get("STATION_CAMERA_BACKEND", "").strip().lower()
    if forced:
        table = {"dshow": cv2.CAP_DSHOW, "v4l2": cv2.CAP_V4L2, "any": cv2.CAP_ANY}
        if forced not in table:
            raise ValueError(
                f"STATION_CAMERA_BACKEND={forced!r} — dshow/v4l2/any 중 하나여야 한다")
        return table[forced]
    if sys.platform.startswith("win"):
        return cv2.CAP_DSHOW
    if sys.platform.startswith("linux"):
        return cv2.CAP_V4L2
    return cv2.CAP_ANY


def capture(cfg: StationConfig) -> "cv2.typing.MatLike":
    cap = cv2.VideoCapture(cfg.camera_index, capture_backend())
    if not cap.isOpened():
        raise RuntimeError(f"카메라 index {cfg.camera_index}를 열 수 없습니다 (--probe로 확인)")
    try:
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, cfg.frame_width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, cfg.frame_height)
        for _ in range(cfg.warmup_frames):   # 자동 노출 안정화 (실측: 안 하면 어둡다)
            cap.read()
        ok, frame = cap.read()
        if not ok:
            raise RuntimeError("프레임 캡처 실패")
        h, w = frame.shape[:2]
        if (w, h) != (cfg.frame_width, cfg.frame_height):
            # 해상도가 다르면 캘리브레이션(fx/fy)이 무효다 — 조용히 넘어가면 치수가 다 틀린다
            raise RuntimeError(
                f"프레임 {w}x{h} ≠ 캘리브레이션 기준 {cfg.frame_width}x{cfg.frame_height}")
        return frame
    finally:
        cap.release()


def read_distance(cfg: StationConfig) -> Measurement | None:
    try:
        with TfNova(cfg.tfnova_port) as sensor:
            return sensor.measure(cfg.tfnova_seconds, scale=cfg.tfnova_scale,
                                  offset_cm=cfg.tfnova_offset_cm)
    except MeasurementUnreliable as e:
        print(f"[거리 측정 불가] {e}", file=sys.stderr)
        return None
    except Exception as e:  # 포트 점유·미연결 등
        print(f"[TF-Nova 오류] {e}", file=sys.stderr)
        return None


def probe_cameras(max_index: int = 3) -> None:
    for idx in range(max_index):
        cap = cv2.VideoCapture(idx, capture_backend())
        if not cap.isOpened():
            print(f"index {idx}: 안 열림")
            continue
        for _ in range(15):
            cap.read()
        ok, frame = cap.read()
        if ok:
            path = f"camera_probe_{idx}.jpg"
            cv2.imwrite(path, frame)
            h, w = frame.shape[:2]
            print(f"index {idx}: {w}x{h} → {path} (열어서 어느 카메라인지 확인)")
        cap.release()


def release_session(abandon: bool = False) -> int:
    """잠긴 세션을 푼다 — `--release-session`.

    측정 전송이 실패한 채 프로세스가 끝나면 설비가 잠긴 상태로 남고, 그동안 **아무도 새
    측정을 시작할 수 없다**(409 `STATION_ALREADY_OCCUPIED`).

    ⚠️ **"사람이 푸는 유일한 경로"가 아니다** — 2026-07-31에 백엔드가 세션 TTL을 넣었다
    (`STATION_SESSION_TTL_SECONDS`, 기본 60초, Jira 172). 그냥 두면 60초 뒤 자동 해제되고
    해당 작업은 `FAILED`로 끝난다. **재측정은 자동으로 안 된다.**

    그래도 이 경로가 필요한 이유는 **기다리지 않기 위해서**다. 시연 중 60초를 서서
    기다릴 수는 없고, 자동 해제는 작업을 실패로 마감하므로 어차피 사람이 다시 시작해야
    한다. 지금 풀고 바로 다시 재는 쪽이 빠르다.

    `--abandon`은 마지막 수단이다. 백엔드는 **측정이 저장된 세션만** 닫아주므로, 측정
    없이 잠긴 세션은 `unreliable` 측정을 하나 남겨야 풀린다. **없는 측정을 지어내는
    것이 아니라 "이 세션은 측정에 실패했다"를 기록하는 것**이라 status가 unreliable이다.
    """
    from station.rest_client import (StationApiError, active_session, close_session,
                                     post_measurement)

    session = active_session()
    if not session:
        print("활성 세션이 없습니다 — 설비는 비어 있습니다.")
        return 0
    session_id = session.get("sessionId")
    print(f"활성 세션: sessionId={session_id} cargoId={session.get('cargoId')}")

    try:
        close_session(session_id)
        print("세션을 닫았습니다.")
        return 0
    except StationApiError as e:
        if not (e.status == 409 and "MEASUREMENT_NOT_COMPLETED" in e.body):
            print(f"세션 종료 실패: {e}", file=sys.stderr)
            return 1

    print("이 세션엔 측정 결과가 없어 백엔드가 종료를 거부합니다.", file=sys.stderr)
    if not abandon:
        print("  --abandon 을 주면 unreliable 측정을 남기고 강제로 풉니다.", file=sys.stderr)
        return 1

    from datetime import datetime, timezone
    stamp = datetime.now(timezone.utc).astimezone()
    abandoned = {
        "measurement_id": f"abandoned-{session_id}",
        "status": "unreliable",
        "measured_at": stamp.isoformat(timespec="seconds"),
        "dimensions": None,
        "tipping": None,
    }
    try:
        # ⚠️ `session_id`를 반드시 실어야 한다. 2026-07-31 백엔드가 sessionId를 필수로
        # 바꿨을 때 이 복구 경로를 빠뜨려, **정작 잠긴 세션을 풀려는 순간 400으로 실패**했다.
        post_measurement(abandoned, session_id=session_id)
        close_session(session_id)
    except StationApiError as e:
        print(f"강제 해제 실패: {e}", file=sys.stderr)
        return 1
    print(f"unreliable 측정을 남기고 세션을 풀었습니다 (measurementId={abandoned['measurement_id']}).")
    return 0


def make_detector(args, cfg: StationConfig):
    """추론기를 만든다.

    `--infer-url`이 있으면 온보드 원격 추론 서버만 사용한다.
    원격 주소가 없을 때만 로컬 ONNX 모델을 초기화한다.
    """
    if args.infer_url:
        from station.remote_detector import RemoteDetector
        return RemoteDetector(
            args.infer_url,
            input_size=cfg.input_size,
            local_detector=None,
            score_threshold=cfg.score_threshold,
            class_thresholds=cfg.class_score_thresholds,
        )

    return OnnxDetector(
        cfg.model_path, cfg.input_size, cfg.score_threshold,
        cfg.class_names, cfg.norm_mean, cfg.norm_std,
        class_thresholds=cfg.class_score_thresholds,
    )


def start_stream_overlay(args, cfg: StationConfig, bus, stop, detector=None):
    """송출 화면에 검출을 그리는 추론 스레드를 띄운다. 끄면 `None`.

    ⚠️ **표시 전용이다.** 저장되는 측정은 트리거 시점에 한 번만 재고, 이건 화면에
    "지금 무엇을 잡고 있는가"를 보여줄 뿐이다. 둘을 섞으면 어느 프레임이 진짜 측정인지
    구분할 수 없다.
    """
    if args.stream_infer_fps <= 0:
        return None, None
    detector = detector or make_detector(args, cfg)
    overlay = livestream.Overlay()
    thread = livestream.InferenceThread(bus, detector, overlay, stop,
                                        fps=args.stream_infer_fps)
    thread.start()
    where = args.infer_url or "로컬 ONNX"
    print(f"[stream] 검출 오버레이 {args.stream_infer_fps}fps ({where}) — 표시 전용",
          flush=True)
    return overlay, thread


def run_stream_only(args) -> int:
    """카메라 화면만 내보낸다 — MQTT 도 측정도 하지 않는다.

    `--listen` 은 브로커 연결이 필수라 자격증명이 없으면 뜨지도 않는다. 그런데
    "관제 화면에 측정 카메라가 보이는가"는 그와 **별개로 확인해야 하는 것**이라
    이 모드를 둔다. 카메라·방화벽·주소를 측정 체인과 분리해서 가를 수 있다.

    ⚠️ **이게 카메라를 쥐고 있으면 측정은 못 한다.** 시연 본편은
    `--listen --publish --stream-port` 로 한 프로세스가 둘 다 한다.
    """
    cfg = StationConfig()
    port = args.stream_port or livestream.DEFAULT_PORT
    cap = cv2.VideoCapture(cfg.camera_index, capture_backend())
    if not cap.isOpened():
        print(f"카메라 index {cfg.camera_index} 안 열림 — 다른 창(라이브 뷰·측정)이 "
              f"물고 있는지 확인 (--probe)", file=sys.stderr)
        return 1
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, cfg.frame_width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, cfg.frame_height)
    for _ in range(cfg.warmup_frames):
        cap.read()

    bus = livestream.FrameBus()
    bus.describe(f"index {cfg.camera_index} {cfg.frame_width}x{cfg.frame_height}")
    stop = threading.Event()
    thread = livestream.CaptureThread(cap, bus, stop)
    thread.start()
    overlay, _infer = start_stream_overlay(args, cfg, bus, stop)
    try:
        server = livestream.start_server(bus, port=port, stream_width=args.stream_width,
                                         overlay=overlay)
    except OSError as e:
        print(f"❌ 포트 {port} 열기 실패 — {e}", file=sys.stderr)
        stop.set()
        thread.join(timeout=2.0)
        cap.release()
        return 1

    print(f"송출 중 — http://<이 PC>:{port}/stream  (폭 {args.stream_width}px)")
    print(f"  확인: http://127.0.0.1:{port}/health · /snapshot")
    print("  ⚠️ 측정은 못 한다(카메라 배타). Ctrl-C로 종료", flush=True)
    try:
        while True:
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("\n종료", flush=True)
    finally:
        # 캡처 스레드를 먼저 세운다 — read() 안에 있는 채로 release 하면 카메라가
        # 깨끗이 반납되지 않아 다음 실행이 실패할 수 있다.
        stop.set()
        thread.join(timeout=2.0)
        server.shutdown()
        cap.release()
    return 0


def check_wiring(args) -> int:
    """시연 전 점검 — 측정하지 않고 연결만 확인한다 (`--check`).

    시연 도중에 "왜 안 되지"를 찾는 대신, 시작 전에 **어디가 끊겼는지** 알려준다.
    셋 다 초록이어야 "카메라는 노트북, 추론은 보드, 저장은 백엔드"가 성립한다.
    """
    from station.rest_client import active_session, base_url_of

    ok = True

    print("=== 온보드 추론 서버 ===")
    if args.infer_url:
        from station.remote_detector import RemoteDetector
        h = RemoteDetector(args.infer_url).health()
        if h and h.get("ok"):
            print(f"  ✅ {args.infer_url}  엔진 {h.get('engine')}  입력 {h.get('input_size')}")
        else:
            print(f"  ❌ {args.infer_url} 응답 없음 — 측정하면 **로컬로 폴백**된다")
            ok = False
    else:
        print("  ○ --infer-url 없음 — 로컬(노트북)에서 추론한다")

    print("=== 백엔드 ===")
    base = base_url_of(None)
    try:
        sess = active_session()
        print(f"  ✅ {base}")
        print(f"  {'⚠️ 활성 세션 있음: ' + str(sess) if sess else '○ 활성 세션 없음(정상)'}")
    except Exception as e:
        print(f"  ❌ {base} — {e}")
        ok = False

    print("=== 카메라 ===")
    cfg = StationConfig()
    cap = cv2.VideoCapture(cfg.camera_index, capture_backend())
    if cap.isOpened():
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, cfg.frame_width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, cfg.frame_height)
        w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        match = (w, h) == (cfg.frame_width, cfg.frame_height)
        print(f"  {'✅' if match else '❌'} index {cfg.camera_index}: {w}x{h}"
              + ("" if match else f" — 캘리브레이션 기준 {cfg.frame_width}x{cfg.frame_height}와 다르다"))
        ok = ok and match

        # **정지 판정 임계가 센서 노이즈 바닥보다 위인지 확인한다.** 아래면 판정이
        # 아예 동작하지 않는다 — 가만히 있어도 늘 "안 멎었다"가 된다(2026-07-31 실측 사고).
        # 조명·카메라가 바뀌면 노이즈가 달라지므로 시연 전에 매번 본다.
        from station.trigger import STILL_DIFF, frame_motion
        for _ in range(cfg.warmup_frames):
            cap.read()
        vals, prev = [], None
        for _ in range(20):
            got, f = cap.read()
            if not got:
                break
            if prev is not None:
                vals.append(frame_motion(prev, f))
            prev = f
        if vals:
            floor = max(vals)
            margin_ok = STILL_DIFF > floor * 1.2
            print(f"  {'✅' if margin_ok else '❌'} 정지 판정: 노이즈 바닥 "
                  f"{min(vals):.2f}~{floor:.2f} vs 임계 {STILL_DIFF}"
                  + ("" if margin_ok else "  ← **임계가 낮아 판정이 안 된다**"))
            ok = ok and margin_ok
    else:
        print(f"  ❌ index {cfg.camera_index} 안 열림 (--probe로 확인)")
        ok = False
    cap.release()

    print(f"\n{'✅ 준비됨' if ok else '❌ 위 항목을 고칠 것'}")
    return 0 if ok else 1


def main(argv: list[str] | None = None) -> int:
    # ⚠️ argparse 기본값이 `os.environ.get(...)` 을 **파서를 만들 때** 읽는다.
    #    그래서 .env 로드는 반드시 그 전에 해야 한다.
    #    (수동 트리거 `station.send_trigger` 도 같은 모듈을 써서 설정이 갈리지 않는다.)
    from station.envfile import load_dotenv
    load_dotenv()

    parser = argparse.ArgumentParser(description="측정 스테이션 (FR-101-5)")
    parser.add_argument("--once", action="store_true", help="1회 측정 후 종료")
    parser.add_argument("--probe", action="store_true", help="카메라 인덱스 확인")
    parser.add_argument("--image", type=Path, help="카메라 대신 이미지 파일 사용")
    parser.add_argument("--distance", type=float, help="TF-Nova 대신 고정 거리(cm) 사용")
    parser.add_argument("--out", type=Path, help="결과 JSON 저장 경로")
    parser.add_argument("--save-frame", type=Path, help="캡처 프레임 저장 경로 (디버그)")
    parser.add_argument("--publish", action="store_true",
                        help="측정 결과를 백엔드로 전송 "
                             "(POST /api/stations/measurements, STATION_API_BASE 환경변수)")
    parser.add_argument("--cargo-id",
                        help="이 측정의 화물 ID. 주면 측정 전에 세션을 열고 끝나면 반드시 "
                             "닫는다. 안 주면 남이 연 세션에 측정이 붙는다(오귀속 위험)")
    parser.add_argument("--release-session", action="store_true",
                        help="잠긴 측정 세션을 조회·해제한다 (측정은 하지 않는다)")
    parser.add_argument("--abandon", action="store_true",
                        help="--release-session 전용. 측정이 없어 닫히지 않는 세션을 "
                             "unreliable 측정을 남겨 강제로 푼다")
    parser.add_argument("--infer-url",
                        help="온보드 추론 서버 주소 (예: http://70.12.247.81:8877). "
                             "주면 보드에서 추론하고, 실패 시 로컬로 폴백한다. "
                             "안 주면 지금까지처럼 로컬에서 추론")
    parser.add_argument("--check", action="store_true",
                        help="측정하지 않고 점검만 — 추론 서버·백엔드 연결 확인 (시연 전용)")
    parser.add_argument("--listen", action="store_true",
                        help="상시 모드 — 백엔드의 측정 요청(MQTT)을 기다렸다가 측정한다")
    # 기본값이 EC2 브로커다. 종전 GPU서버(70.12.130.106:1883)는 쓰지 않는다
    # (2026-08-03: GPU서버 미사용 결정 + mosquitto 프로세스도 없어졌다, Jira 188).
    #
    # ⚠️ **호스트명이 아니라 IP 다.** 브로커 서버 인증서의 SAN 이
    #    `IP Address:3.38.178.143` 뿐이고 DNS 이름이 없다. `i15a304.p.ssafy.io` 로
    #    붙으면 호스트명 검증에서 막힌다(둘은 같은 서버다 — 실측 확인).
    #    인증서를 DNS SAN 으로 재발급하면 호스트명으로 바꿀 수 있다.
    parser.add_argument("--broker", default=os.environ.get(
                            "STATION_MQTT_BROKER", "3.38.178.143"),
                        help="--listen 전용. MQTT 브로커 주소 "
                             "(환경변수 STATION_MQTT_BROKER). 인증서가 IP 로 "
                             "발급돼 있어 기본값이 IP 다")
    parser.add_argument("--broker-port", type=int,
                        default=int(os.environ.get("STATION_MQTT_PORT", "8883")))
    # ⚠️ EC2 브로커는 `allow_anonymous false` + TLS 다. 셋 다 있어야 붙는다.
    #    비밀번호를 명령줄로 받지 않는 이유는 셸 이력·프로세스 목록에 남기 때문이다.
    # CA 는 **레포 루트** `infra/` 에 있다(`ai/infra/` 아님). Dockerfile.backend 도
    # 같은 경로를 COPY 하므로 백엔드·스테이션이 같은 인증서를 본다.
    parser.add_argument("--broker-ca", default=os.environ.get(
                            "STATION_MQTT_CA",
                            str(_AI_ROOT.parent / "infra" / "mqtt-ca.crt")),
                        help="브로커 CA 인증서 (환경변수 STATION_MQTT_CA). "
                             "빈 값이면 평문 접속")
    parser.add_argument("--topic", default="fast/station/measure_request",
                        help="측정 요청 토픽. 백엔드 application.yml 과 같아야 한다")
    parser.add_argument("--cargo-field", default="cargoId",
                        help="요청 페이로드에서 화물 ID를 담은 키 이름")
    parser.add_argument("--max-measurements", type=int, default=0,
                        help="--listen 전용. N건 측정 후 종료(0=무한). "
                             "리허설·검증용 — 한 번만 돌려보고 로그를 확인할 때 쓴다")
    parser.add_argument("--stream-port", type=int, nargs="?",
                        const=livestream.DEFAULT_PORT, default=None,
                        help=f"--listen 전용. 측정 카메라 화면을 MJPEG 으로 송출한다"
                             f"(기본 포트 {livestream.DEFAULT_PORT}). 관제 화면의 "
                             f"'AI 측정 영상' 패널이 이 주소를 본다. "
                             f"⚠️ 별도 송출 서버를 띄우면 카메라를 뺏겨 측정이 실패하므로 "
                             f"여기서 함께 내보낸다")
    parser.add_argument("--stream-width", type=int, default=livestream.DEFAULT_STREAM_WIDTH,
                        help="송출 폭(px). 측정은 원본 해상도로 하고 화면만 줄인다")
    parser.add_argument("--stream-infer-fps", type=float, default=0.0,
                        help="송출 화면에 검출 상자를 그리는 주기(fps). **기본 0(끔)** — "
                             "관제 화면이 측정 결과로 상자를 이미 그리므로 켜면 두 벌이 "
                             "겹쳐 보인다. 관제 화면 없이 카메라만 볼 때(--stream-only) "
                             "쓴다. ⚠️ 켜도 표시 전용이고 백엔드로 가지 않는다")
    parser.add_argument("--stream-only", action="store_true",
                        help="송출만 한다 — MQTT·측정 없이 카메라 화면만 내보낸다. "
                             "브로커 자격증명 없이 관제 화면을 띄워보거나 카메라를 "
                             "확인할 때 쓴다. ⚠️ 이게 카메라를 쥐고 있으면 측정은 "
                             "못 한다(시연 본편은 --listen --stream-port)")
    args = parser.parse_args(argv)

    if args.probe:
        probe_cameras()
        return 0
    if args.release_session:
        return release_session(abandon=args.abandon)
    if args.check:
        return check_wiring(args)
    if args.stream_only:
        return run_stream_only(args)
    if not (args.once or args.listen):
        parser.error("--once(1회) 또는 --listen(상시 대기) 또는 --probe 를 지정하세요")
    if args.listen and not args.publish:
        # 상시 모드는 시뮬 신호를 받아 백엔드에 저장하는 것이 목적이다. 전송을 안 하면
        # 측정만 하고 버리게 되므로 실수를 막는다.
        parser.error("--listen 은 --publish 와 함께 씁니다")

    cfg = StationConfig()

    def build_detector():
        return make_detector(args, cfg)

    def measure_and_emit(detector=None, frame=None, spans: Spans | None = None) -> dict | None:
        """측정하고 결과를 stdout·`--out`으로 낸다. 이미지 로드 실패면 None.

        `frame`을 주면 그걸 쓴다 — **상시 모드(`--listen`)는 카메라를 계속 열어두고**
        직접 잡은 프레임을 넘긴다. 매번 `capture()`를 부르면 자동 노출 워밍업만 0.8초라
        측정 예산(≤1초)을 넘긴다.

        `spans`를 주면 그 계측기에 이어 붙인다 — 상시 모드가 **트리거 수신 시점부터**
        재려고 밖에서 만들어 넘긴다. 안 주면 여기서 시작한다(`--once` 경로).
        """
        spans = spans or Spans()
        with spans.span("캡처"):
            if frame is not None:
                pass
            elif args.image:
                frame = cv2.imread(str(args.image))
                if frame is None:
                    print(f"이미지를 읽을 수 없습니다: {args.image}", file=sys.stderr)
                    return None
            else:
                frame = capture(cfg)
        if args.save_frame:
            cv2.imwrite(str(args.save_frame), frame)

        with spans.span("거리"):
            if args.distance is not None:
                distance = Measurement(distance_cm=args.distance, std_cm=0.0,
                                       frames_used=0, frames_seen=0)
            else:
                distance = read_distance(cfg)

        if detector is None:
            detector = build_detector()
        # ⚠️ 이 구간은 젯슨이 돌려주는 `inference_ms`보다 **넓다** — 클라이언트 letterbox·
        # 무손실 PNG 인코딩·HTTP 왕복이 포함된다. KPI에 쓸 값은 이쪽이다.
        with spans.span("추론"):
            detections = detector.detect(frame)

        with spans.span("계산"):
            # 카메라 롤 추정 — 파렛트 상판이 실제 수평이라는 점을 기준면으로 쓴다.
            # 파렛트가 없거나 추정이 불안정하면 None이고, 그러면 치수 보정을 건너뛴다.
            pallets = [d for d in detections
                       if d.label == "pallet" and d.score >= cfg.threshold_for("pallet")]
            tilt_deg = None
            if pallets:
                best = max(pallets, key=lambda d: d.score)
                # 박스가 상판을 가리는 구간은 제외한다 — 안 그러면 편심 배치에서 각도가 뒤집힌다
                occluders = [d.box for d in detections
                             if d.label == "box" and d.score >= cfg.threshold_for("box")]
                tilt_deg = estimate_roll_deg(frame, best.box, occluders=occluders)

            payload = build_payload(detections, distance, cfg, tilt_deg=tilt_deg)

        # 여기가 KPI의 끝점 — "치수·편하중 결과"가 확정된 순간이다.
        spans.finish()

        # **어디서 추론했는지 결과에 남긴다.** 폴백으로 떨어졌는지 결과만 봐서는 알 수
        # 없고, 시연에서 "보드가 했다"고 말하려면 근거가 있어야 한다.
        # 백엔드 전송은 6·7필드만 골라 쓰므로 이 필드가 늘어도 계약에 영향이 없다.
        payload["inference"] = {
            "path": getattr(detector, "last_path", "local"),
            "ms": getattr(detector, "last_inference_ms", None),
            "endpoint": args.infer_url or None,
        }
        if payload["inference"]["path"] == "local" and args.infer_url:
            print("[measure] ⚠️ 보드 추론에 실패해 **로컬로 측정했습니다** — "
                  "시연 설명에 주의", file=sys.stderr)

        # KPI 근거를 결과에 남긴다. 백엔드 전송은 6·7필드만 골라 쓰므로 계약에 영향이 없다.
        payload["timing"] = spans.as_dict()
        print(spans.summary(), file=sys.stderr)

        text = json.dumps(payload, ensure_ascii=False, indent=2)
        print(text)
        if args.out:
            args.out.write_text(text, encoding="utf-8")
        return payload

    def publish(payload: dict, session_id: str | None = None) -> bool:
        # status != ok도 보낸다 — 백엔드가 status별 검증을 하고, 재측정 판단에 쓴다.
        from station.rest_client import send
        ok = send(payload, session_id=session_id)
        print(f"[publish] {'성공' if ok else '실패'} "
              f"POST /api/stations/measurements ({payload.get('measurement_id')})",
              file=sys.stderr)
        return ok

    # ⚠️ `not args.listen`이 빠지면 상시 모드가 여기 걸린다. `--listen`은 cargoId를
    # MQTT로 받으므로 **시작 시점엔 `--cargo-id`가 없는 것이 정상**인데, 그것을
    # "cargo-id 없이 보내는 1회 측정"으로 오인해 상시 모드에 도달하지 못했다
    # (2026-07-31 실측 — 카메라를 열어 한 번 재고 세션 없이 전송해 400을 받았다).
    if not args.listen and not (args.publish and args.cargo_id):
        payload = measure_and_emit()
        if payload is None:
            return 1
        if not args.publish:
            return 0
        print("[publish] ⚠️ --cargo-id 없이 보냅니다 — 세션을 열지 않으므로 "
              "`sessionId` 없이 전송되고, 백엔드는 400(`sessionId 는 필수입니다`)으로 "
              "거부합니다. 저장하려면 --cargo-id 를 주세요.", file=sys.stderr)
        return 0 if publish(payload) else 1

    # **세션을 측정 앞에 연다** (2026-07-31 팀 결정). 백엔드 문서상 정상 흐름
    # ("세션 시작 → 측정 → 결과 등록")이고, 세션이 **"지금 이 화물을 측정 중"** 이라는
    # 뜻을 갖는다. 얻는 것 둘:
    #   ① 관제가 `GET /sessions/active`로 설비 점유를 실시간 표시할 수 있다.
    #   ② 설비가 이미 점유 중이면 **측정을 시작하기 전에** 409로 튕겨 헛수고를 막는다.
    #
    # ⚠️ 대가: 측정 도중 예외(카메라 미개방·해상도 불일치·모델 파일 없음)가 나면 측정이
    # 저장되지 않은 채 세션만 열려 **백엔드가 종료를 거부한다**(MEASUREMENT_NOT_COMPLETED).
    # `measurement_session`이 세션 ID와 복구 명령을 찍는다. 대안으로 실패 시 unreliable
    # 측정을 남겨 자동 해제하는 방식도 검토했으나, **측정 실패 행을 DB에 쌓지 않기로**
    # 했다. 근본 해결(TTL·강제 해제)은 S15P11A304-172.
    from station.rest_client import SessionNotReleased, StationApiError, measurement_session

    def measure_for_cargo(cargo_id: str, detector=None, frame=None,
                          spans: Spans | None = None) -> int:
        """세션 열기 → 측정 → 전송 → 닫기. `--once`와 `--listen`이 함께 쓴다.

        두 모드가 같은 함수를 타야 "손으로는 되는데 자동으로는 안 된다"가 안 생긴다.
        """
        spans = spans or Spans()
        try:
            with measurement_session(cargo_id) as session_id:
                payload = measure_and_emit(detector, frame, spans)
                if payload is None:
                    return 1
                # sessionId는 백엔드 필수 필드다(2026-07-31~). 활성 세션 조회로 붙이던
                # 방식은 TTL 만료 뒤 늦게 도착한 측정이 다음 세션에 오귀속되는 구멍이 있었다.
                #
                # 전송은 **판정 이후**라 `total_ms`에 안 들어간다 — KPI가 "결과"까지이지
                # "백엔드에 저장"까지가 아니기 때문이다. 따로 재서 옆에 놓는다.
                with spans.span("전송"):
                    sent = publish(payload, session_id)
                print(f"[timing] 전송 {spans.spans_ms['전송']:.0f}ms "
                      f"(판정 총 {spans.total_ms:.0f}ms 에는 미포함)", file=sys.stderr)
                return 0 if sent else 1
        except StationApiError as e:
            # 세션 열기 실패(대개 409 ALREADY_OCCUPIED) — 측정은 시작도 안 했다.
            #
            # **측정 실패(1)와 구분해 3을 돌려준다.** 운영자가 할 일이 다르다:
            #   1 → 화물 배치·조명·검출을 본다 (재려고 했는데 안 됐다)
            #   3 → 설비 점유·백엔드 연결을 본다 (재려는 시도조차 못 했다)
            # 종전엔 둘 다 1이라 로그 마지막 줄만 보면 원인을 가릴 수 없었다.
            print(f"[station-api] 세션을 열 수 없어 측정을 건너뜁니다: {e}", file=sys.stderr)
            return 3
        except SessionNotReleased:
            return 2      # 잠긴 세션이 남았다 — 성공(0)·측정실패(1)와 구분한다

    if not args.listen:
        return measure_for_cargo(args.cargo_id)

    # ── 상시 모드 ────────────────────────────────────────────────────────────
    #
    # 시뮬이 적재 위치에 도착하면 신호를 쏘고, 그때 한 번 측정한다. 사람이 버튼을
    # 누르는 방식은 "무인 스마트팩토리"와 맞지 않는다.
    #
    # **카메라와 추론기를 미리 만들어 둔다.** 요청이 온 뒤에 열면 자동 노출 워밍업만
    # 0.8초라 측정 예산(≤1초)을 넘긴다.
    from station.trigger import MeasureTrigger, wait_until_still

    # **백엔드를 트리거보다 먼저 확인한다.** 안 그러면 신호를 받은 **뒤에야** 백엔드가
    # 안 닿는 걸 알게 되고, 그 신호는 그대로 날아간다(재발행이 없다). 2026-08-03에
    # `STATION_API_BASE` 미설정으로 localhost 를 찌르다 트리거를 하나 잃었다.
    from station.rest_client import active_session, base_url_of
    backend = base_url_of(None)
    try:
        active_session()
        print(f"[listen] 백엔드 {backend} ✅")
    except Exception as e:
        print(f"[listen] ❌ 백엔드 연결 실패 {backend} — {e}", file=sys.stderr)
        if "localhost" in backend:
            print("  `STATION_API_BASE` 가 설정되지 않아 기본값(localhost)을 씁니다. "
                  "레포 루트 `.env` 에 배포 주소를 넣으세요.", file=sys.stderr)
        return 1

    # **브로커를 카메라보다 먼저 연결한다.** 주소·포트가 틀렸으면 하드웨어를 잡기 전에
    # 실패하는 편이 낫다 — 카메라를 열어놓고 죽으면 다른 프로세스가 못 쓴다.
    # 비밀번호는 환경변수로만 받는다 — 명령줄은 셸 이력·프로세스 목록에 남는다.
    trigger = MeasureTrigger(args.broker, args.broker_port, args.topic,
                             args.cargo_field,
                             tls_ca=args.broker_ca or None,
                             username=os.environ.get("STATION_MQTT_USER"),
                             password=os.environ.get("STATION_MQTT_PASSWORD"))
    scheme = "TLS" if args.broker_ca else "평문"
    auth = "인증" if os.environ.get("STATION_MQTT_USER") else "익명"
    print(f"[listen] 브로커 {args.broker}:{args.broker_port} ({scheme}·{auth})")
    try:
        trigger.__enter__()
    except Exception as e:
        print(f"[listen] ❌ 브로커 연결 실패 {args.broker}:{args.broker_port} — "
              f"{type(e).__name__}: {e}", file=sys.stderr)
        # 흔한 원인을 같이 알려준다 — 연결 실패는 원인 후보가 넓어서
        # 메시지만 보면 어디부터 볼지 모른다.
        print("  확인: ① CA 인증서 경로(--broker-ca) ② STATION_MQTT_USER/"
              "PASSWORD 환경변수 ③ 포트(TLS 8883 / 평문 1883) ④ 방화벽",
              file=sys.stderr)
        return 1

    detector = build_detector()
    cap = cv2.VideoCapture(cfg.camera_index, capture_backend())
    if not cap.isOpened():
        print(f"카메라 index {cfg.camera_index} 안 열림 (--probe로 확인)", file=sys.stderr)
        trigger.__exit__(None, None, None)
        return 1
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, cfg.frame_width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, cfg.frame_height)
    for _ in range(cfg.warmup_frames):
        cap.read()

    # **카메라를 쥔 쪽이 송출도 한다.** 별도 송출 서버를 띄우면 그쪽이 카메라를 물어
    # 트리거가 와도 측정을 못 한다(윈도우·리눅스 둘 다 배타적). 그래서 캡처를 상시
    # 스레드로 돌리고 측정·송출이 같은 프레임을 나눠 본다.
    #
    # 상시 캡처는 송출이 없어도 이득이다 — 열어둔 채 오래 쉬면 드라이버 버퍼에 낡은
    # 프레임이 남아 첫 read() 가 몇 분 전 장면을 돌려줄 수 있다.
    bus = livestream.FrameBus()
    bus.describe(f"index {cfg.camera_index} {cfg.frame_width}x{cfg.frame_height}")
    stop_capture = threading.Event()
    capture_thread = livestream.CaptureThread(cap, bus, stop_capture)
    capture_thread.start()

    stream_server = None
    if args.stream_port:
        # 측정용 추론기를 그대로 넘긴다 — 화면과 측정이 같은 모델·임계를 보게 한다.
        # 다른 것을 쓰면 화면에 잡히는데 측정은 못 잡는(또는 그 반대) 일이 생긴다.
        overlay, _ = start_stream_overlay(args, cfg, bus, stop_capture, detector)
        try:
            stream_server = livestream.start_server(
                bus, port=args.stream_port, stream_width=args.stream_width,
                overlay=overlay)
            print(f"[listen] 송출 http://<이 PC>:{args.stream_port}/stream "
                  f"(폭 {args.stream_width}px · /health 로 상태 확인)", flush=True)
        except OSError as e:
            # 포트가 이미 쓰이면 조용히 넘어가지 않는다 — 관제 화면이 "연결 대기"로
            # 남는데 왜인지 알 길이 없다.
            print(f"[listen] ❌ 송출 포트 {args.stream_port} 열기 실패 — {e}",
                  file=sys.stderr)
            stop_capture.set()
            cap.release()
            trigger.__exit__(None, None, None)
            return 1

    def grab():
        # 같은 프레임을 두 번 돌려주면 정지 판정의 프레임 차가 0 이라 늘 "멎었다"가
        # 된다 — 흔들리는 중에도 통과한다. 그래서 새 프레임을 기다린다.
        frame, grab.seq = bus.next(getattr(grab, "seq", -1))
        return frame

    print(f"상시 모드 — {args.topic} 대기 중. Ctrl-C로 종료", flush=True)
    measured: set[str] = set()
    rc = done = ok_count = fail_count = 0
    try:
        with trigger as trig:
            for req in trig.requests():
                if req.cargo_id in measured:
                    # 같은 화물을 두 번 재면 백엔드가 409로 거부한다. 신호가 중복으로
                    # 오는 경우를 여기서 걸러 로그를 깨끗하게 둔다.
                    print(f"[listen] 이미 측정한 화물 — 건너뜀: {req.cargo_id}",
                          file=sys.stderr)
                    continue

                # ⏱ KPI 시계는 **트리거를 받은 순간** 시작한다 — "박스 도착"의 가장
                #    넓은 해석이다. 정지 대기를 뺀 값도 같이 내려고 아래에서 한 번 더 찍는다.
                spans = Spans()

                # 사람이 의자를 밀다 멈춘 직후라 아직 흔들린다. 멎을 때까지 기다린다.
                with spans.span("정지대기"):
                    frame, still = wait_until_still(grab)
                spans.start_judge()
                if frame is None:
                    print("[listen] 프레임을 못 잡았다 — 건너뜀", file=sys.stderr)
                    continue
                if not still:
                    # 흔들린 채로도 잰다. 시연에서 "아무 일도 안 일어남"보다는 낫고,
                    # 결과에 남으니 나중에 걸러낼 수 있다.
                    print("[listen] ⚠️ 흔들림이 안 멎었다 — 그대로 측정한다"
                          "(모션 블러·가장자리 왜곡 위험)", file=sys.stderr)

                rc = measure_for_cargo(req.cargo_id, detector, frame, spans)
                if rc == 0:
                    measured.add(req.cargo_id)
                    ok_count += 1
                else:
                    fail_count += 1
                done += 1

                # ⚠️ **성공과 시도를 구분해 찍는다.** 종전엔 실패해도 "N건 측정 완료"
                #    라고 해서, 마지막 줄만 보면 성공한 것으로 읽혔다. 실제로 세션
                #    점유(409)로 측정을 **시작조차 못 한** 경우에도 "완료"가 찍혔다.
                #    위에 409 가 남아 있어도 결론 줄이 거짓이면 그쪽을 믿게 된다.
                verdict = {
                    0: "성공",
                    2: "실패(내 세션이 안 풀림 — --release-session 필요)",
                    3: "실패(세션을 못 열었다 — 설비 점유·백엔드 확인)",
                }.get(rc, "실패(측정 불가 — 화물 배치·조명 확인)")
                if args.max_measurements and done >= args.max_measurements:
                    tail = f" (성공 {ok_count} · 실패 {fail_count})" if fail_count else ""
                    print(f"[listen] {done}건 시도{tail} — 종료  [{verdict}]", flush=True)
                    break
                print(f"[listen] {verdict} — 다음 요청 대기 "
                      f"(누적 성공 {ok_count} · 실패 {fail_count})", flush=True)
    except KeyboardInterrupt:
        print("\n종료", flush=True)
    finally:
        # 캡처 스레드를 먼저 세운다 — cap.read() 안에 들어가 있는 채로 release 하면
        # 카메라가 깨끗이 반납되지 않아 다음 실행이 "열 수 없음"으로 실패할 수 있다.
        stop_capture.set()
        capture_thread.join(timeout=2.0)
        if stream_server is not None:
            stream_server.shutdown()
        cap.release()
    return 0


if __name__ == "__main__":
    sys.exit(main())
