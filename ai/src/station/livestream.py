"""스테이션 카메라 라이브 송출 — 관제 화면에 측정 카메라 화면을 띄운다.

## 왜 별도 서버가 아니라 `serve.py --listen` 안에 있는가

**카메라는 한 프로세스만 잡는다.** 송출 서버를 따로 띄우면 그 프로세스가 카메라를
물어 `--listen` 이 트리거를 받아도 측정을 못 한다(윈도우 DirectShow·리눅스 V4L2
둘 다 배타적이다. `station_live_view.py` 주석의 COM 포트 제약과 같은 이야기다).

젯슨은 이 문제가 없어서 `scripts/onboard_camera_stream.py` 를 따로 띄운다 —
거기서는 **송출 카메라와 측정 카메라가 서로 다른 장치**이기 때문이다. 스테이션은
같은 BRIO 하나라 그 구조를 그대로 옮길 수 없다.

그래서 카메라를 쥔 쪽이 송출도 한다. 캡처 스레드가 최신 프레임 한 장을 계속
갱신하고, 측정은 `FrameBus.next()` 로 **새 프레임**을 받아 간다.

## 프레임을 쌓아두지 않는다

클라이언트가 느려도 지연이 누적되면 안 된다 — 관제 화면은 "지금 모습"이 중요하지
모든 프레임이 필요하지 않다. 그래서 버스는 최신 한 장만 들고, 늦은 클라이언트는
중간 프레임을 그냥 건너뛴다.

## JPEG 인코딩을 캡처 스레드에서 하지 않는 이유

보는 사람이 없을 때 1920x1080 JPEG 인코딩을 계속 돌리는 건 낭비다(측정 PC 는
추론도 같이 돈다). 그래서 인코딩은 **요청 처리 쪽에서** 필요할 때만 한다.
"""
from __future__ import annotations

import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import cv2
import numpy as np

BOUNDARY = "frame"

DEFAULT_PORT = 8879
"""젯슨 온보드 송출(8878)·추론 서버(8877)와 겹치지 않게 둔다."""

DEFAULT_STREAM_WIDTH = 960
"""송출 폭. 캘리브레이션 기준 1920 을 그대로 보내면 프레임당 수백 KB 라 화면이 밀린다.

⚠️ **줄인 것은 화면뿐이고 측정은 원본으로 한다.** 치수는 픽셀 수에 비례하므로
송출 해상도가 측정에 영향을 주면 안 된다 — `FrameBus` 는 원본을 들고 있고 축소는
내보낼 때만 한다.
"""


class FrameBus:
    """최신 프레임 한 장을 여러 소비자가 나눠 보는 통로."""

    def __init__(self) -> None:
        self._lock = threading.Condition()
        self._frame: np.ndarray | None = None
        self._seq = 0
        self._alive = False
        self._error: str | None = None
        self._label = ""

    # --- 생산자 ---

    def publish(self, frame: np.ndarray) -> None:
        with self._lock:
            self._frame = frame
            self._seq += 1
            self._alive = True
            self._lock.notify_all()

    def fail(self, message: str) -> None:
        with self._lock:
            self._alive = False
            self._error = message
            self._lock.notify_all()

    def describe(self, label: str) -> None:
        with self._lock:
            self._label = label

    # --- 소비자 ---

    def latest(self) -> tuple[np.ndarray | None, int]:
        with self._lock:
            return self._frame, self._seq

    def next(self, after_seq: int = -1, timeout: float = 2.0) -> tuple[np.ndarray | None, int]:
        """`after_seq` 보다 새로운 프레임을 기다렸다 돌려준다.

        **같은 프레임을 두 번 돌려주면 안 된다.** 정지 판정이 연속 두 프레임의 차를
        보는데, 같은 장을 두 번 받으면 차가 0 이라 늘 "멎었다"가 된다 — 흔들리는 중에도
        통과한다.
        """
        deadline = time.monotonic() + timeout
        with self._lock:
            # `self._frame is None` 을 함께 본다. seq 는 0 에서 시작하므로 첫 호출
            # (`after_seq=-1`)이면 `0 <= -1` 이 거짓이라 **프레임도 없이 즉시** 빠져나온다.
            while self._frame is None or self._seq <= after_seq:
                remain = deadline - time.monotonic()
                if remain <= 0:
                    return None, self._seq
                self._lock.wait(remain)
            return self._frame, self._seq

    def health(self) -> dict:
        with self._lock:
            return {"ok": self._alive, "camera": self._label,
                    "frames": self._seq, "error": self._error}


class CaptureThread(threading.Thread):
    """카메라를 계속 읽어 버스에 흘린다.

    상시로 읽는 이유가 송출만은 아니다. 열어둔 채 오래 쉬면 드라이버 버퍼에 **낡은
    프레임**이 남아, 트리거가 왔을 때 첫 `read()` 가 몇 분 전 장면을 돌려줄 수 있다.
    계속 읽으면 그 문제도 같이 없어진다.
    """

    def __init__(self, cap, bus: FrameBus, stop: threading.Event, fps: float = 15.0) -> None:
        super().__init__(name="station-capture", daemon=True)
        self._cap = cap
        self._bus = bus
        # ⚠️ 이름을 `_stop` 으로 두면 안 된다 — `threading.Thread` 가 내부적으로
        # `_stop()` 메서드를 쓰고, 덮어쓰면 `join()` 이
        # `TypeError: 'Event' object is not callable` 로 죽는다(실측).
        self._stopping = stop
        self._interval = 1.0 / fps if fps > 0 else 0.0

    def run(self) -> None:
        fail_streak = 0
        while not self._stopping.is_set():
            started = time.monotonic()
            ok, frame = self._cap.read()
            if not ok:
                fail_streak += 1
                # 일시적 실패는 흔하다. 계속 실패하면 카메라가 빠진 것이므로 조용히
                # 도는 대신 상태를 남긴다 — /health 가 이 값을 보고한다.
                if fail_streak >= 30:
                    self._bus.fail("카메라에서 30프레임 연속으로 읽지 못했습니다(연결 확인)")
                    print("[stream] 카메라 읽기 30회 연속 실패 — 캡처 중단",
                          file=sys.stderr, flush=True)
                    return
                if self._stopping.wait(0.1):
                    return
                continue
            fail_streak = 0
            self._bus.publish(frame)
            if self._interval:
                remain = self._interval - (time.monotonic() - started)
                if remain > 0:
                    self._stopping.wait(remain)


def _handler_factory(bus: FrameBus, stream_width: int, quality: int):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        # Nagle 이 작은 쓰기를 모으려고 기다리면 수백 ms 가 그냥 붙는다. 관제 영상은
        # 대역폭 효율보다 지연이 중요하다.
        disable_nagle_algorithm = True

        def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler 규약)
            path = self.path.split("?", 1)[0].rstrip("/") or "/"
            if path in ("/", "/stream"):
                self._stream()
            elif path == "/health":
                self._health()
            elif path == "/snapshot":
                self._snapshot()
            else:
                self.send_error(404, "not found")

        def _encode(self, frame: np.ndarray) -> bytes | None:
            if stream_width and frame.shape[1] > stream_width:
                h = int(round(frame.shape[0] * stream_width / frame.shape[1]))
                frame = cv2.resize(frame, (stream_width, h), interpolation=cv2.INTER_AREA)
            ok, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), quality])
            return buf.tobytes() if ok else None

        def _health(self) -> None:
            body = json.dumps(bus.health(), ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _snapshot(self) -> None:
            """단일 JPEG 한 장. 스트림이 안 뜰 때 카메라 자체를 가르는 데 쓴다."""
            frame, _ = bus.next(-1, timeout=15.0)
            jpeg = self._encode(frame) if frame is not None else None
            if jpeg is None:
                self.send_error(503, "no frame yet")
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(jpeg)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(jpeg)

        def _stream(self) -> None:
            # **첫 프레임을 기다린다.** 관제 화면은 iframe 한 번 로드로 끝이라, 카메라가
            # 예열되기 전에 붙었다고 503 을 주면 그 화면이 그대로 굳는다(실측: 워밍업
            # 25프레임 도는 사이에 붙으면 매번 "camera not ready"). 캡처가 정말 죽었으면
            # 아래 timeout 뒤에 이유와 함께 503 을 준다 — 조용히 빈 화면을 주지 않는다.
            frame, seq = bus.next(-1, timeout=15.0)
            if frame is None:
                self.send_error(503, bus.health().get("error") or "camera not ready")
                return

            self.send_response(200)
            self.send_header("Content-Type",
                             f"multipart/x-mixed-replace; boundary={BOUNDARY}")
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
            self.send_header("Pragma", "no-cache")
            # 길이를 모르는 무한 응답이라 keep-alive 를 쓰지 않는다.
            self.send_header("Connection", "close")
            self.end_headers()

            sent = -1
            try:
                while True:
                    frame, seq = bus.next(sent, timeout=5.0)
                    if frame is None:
                        # 5초간 새 프레임이 없다 — 캡처가 죽었거나 카메라가 빠졌다.
                        return
                    sent = seq
                    jpeg = self._encode(frame)
                    if jpeg is None:
                        continue
                    # 한 번에 쓴다. 나눠 쓰면 조각마다 TCP 세그먼트가 생겨 헤더만
                    # 먼저 나가고 본문이 다음 왕복으로 밀릴 수 있다.
                    self.wfile.write(
                        b"--" + BOUNDARY.encode() + b"\r\n"
                        b"Content-Type: image/jpeg\r\n"
                        + f"Content-Length: {len(jpeg)}\r\n\r\n".encode()
                        + jpeg
                        + b"\r\n"
                    )
            except (BrokenPipeError, ConnectionResetError):
                # 관제 화면을 닫거나 새로고침하면 매번 일어난다. 정상이다.
                return

        def log_message(self, fmt: str, *args) -> None:
            # 프레임마다 한 줄씩 찍으면 측정 로그가 묻힌다.
            return

    return Handler


def start_server(bus: FrameBus, port: int = DEFAULT_PORT, host: str = "0.0.0.0",
                 stream_width: int = DEFAULT_STREAM_WIDTH,
                 quality: int = 80) -> ThreadingHTTPServer:
    """MJPEG 서버를 데몬 스레드로 띄우고 돌려준다.

    ⚠️ `ThreadingHTTPServer` 를 쓴다. MJPEG 은 클라이언트가 접속을 길게 유지하므로
    단일 스레드면 **관제 화면 하나가 붙는 순간 `/health` 조차 막힌다.** (형제 파일
    `onboard_infer_server.py` 의 "스레드 쓰지 말 것" 경고는 pycuda CUDA 컨텍스트가
    생성 스레드에 묶이는 문제라 여기엔 해당하지 않는다 — 여기선 CUDA 를 안 쓴다.)
    """
    server = ThreadingHTTPServer((host, port), _handler_factory(bus, stream_width, quality))
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, name="station-stream",
                     daemon=True).start()
    return server
