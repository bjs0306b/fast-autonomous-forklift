"""온보드 카메라 MJPEG 스트림 — 젯슨 USB 카메라를 관제 화면에 띄운다.

관제 화면의 "AI 측정 영상" 패널은 `<img>` 태그로 MJPEG(multipart/x-mixed-replace)을
받도록 이미 만들어져 있다(`frontend/.../AiMeasurementVideo.tsx`,
`NEXT_PUBLIC_AI_MEASUREMENT_STREAM_URL`). 그래서 **화면 코드를 건드리지 않고** 이 서버만
띄우면 영상이 붙는다. WebRTC/RTSP 로 가면 화면을 iframe 으로 바꿔야 해서 더 크게 번진다.

    # 젯슨에서
    python3 scripts/onboard_camera_stream.py --camera 0 --port 8878

    # 확인
    curl -s -o /dev/null -w '%{http_code}\\n' http://localhost:8878/health
    #  브라우저: http://<젯슨IP>:8878/stream

## ⚠️ 카메라는 한 프로세스만 잡는다 — 추론 스크립트와 동시에 못 쓴다

V4L2 캡처는 배타적이다. `onboard_live.py`·`onboard_fork_align_node.py` 가 `/dev/video0`
를 물고 있으면 이 서버는 카메라를 못 연다(그 반대도 같다). 스테이션 노트북에서
COM 포트가 배타적이라 라이브 뷰와 측정을 동시에 못 돌리는 것과 같은 제약이다
(`station_live_view.py` 주석 참고).

**먼저 쓰는 쪽이 이긴다.** 시연 중 포크정렬을 돌려야 하면 이 서버를 먼저 내려야 한다.

    fuser -v /dev/video0        # 누가 잡고 있는지

> 참고: `onboard_infer_server.py`(8877)는 카메라를 읽지 않는다 — 프레임을 HTTP 로
> 받아 추론만 한다. 그래서 **8877 과는 함께 떠 있어도 충돌하지 않는다.**

## ThreadingHTTPServer 를 여기서는 써도 되는 이유

형제 파일 `onboard_infer_server.py` 에는 "ThreadingHTTPServer 를 쓰면 안 된다"는 경고가
크게 붙어 있다. 그건 **pycuda CUDA 컨텍스트가 생성 스레드에 묶이기** 때문인데, 이 서버는
CUDA 를 전혀 쓰지 않는다(카메라 읽기 + JPEG 인코딩뿐). 오히려 MJPEG 은 클라이언트가 접속을
길게 유지하므로, 단일 스레드면 **관제 화면 한 명이 붙는 순간 `/health` 조차 막힌다.**

## 캡처 스레드를 따로 두는 이유

클라이언트마다 `cap.read()` 를 부르면 카메라 핸들이 여러 개 필요해 애초에 불가능하고,
한 핸들을 요청마다 돌려쓰면 느린 클라이언트 하나가 캡처 전체를 멈춘다. 그래서 캡처
스레드가 최신 프레임 한 장만 계속 덮어쓰고, 각 클라이언트는 **그 시점의 최신 프레임**을
자기 속도로 가져간다. 프레임을 쌓아두지 않으므로 느린 클라이언트가 있어도 지연이
누적되지 않는다(관제 화면은 "지금 모습"이 중요하지 모든 프레임이 필요하지 않다).
"""
from __future__ import annotations

import argparse
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import cv2

BOUNDARY = "frame"

_frame_lock = threading.Lock()
_latest_jpeg: bytes | None = None
_latest_seq = 0
"""프레임 일련번호. 클라이언트가 같은 프레임을 두 번 보내지 않도록 비교하는 데 쓴다."""

_capture_alive = False
_capture_error: str | None = None
_camera_label = ""

_stop = threading.Event()
"""종료 신호.

⚠️ **이게 없으면 Ctrl+C 에서 코어 덤프가 난다**(실측 2026-08-06):

    ^C종료합니다.
    FATAL: exception not rethrown
    Aborted (core dumped)

캡처 스레드가 `cap.read()`(GStreamer C 코드) 안에 블록돼 있는 상태로 인터프리터가 끝나면,
네이티브 스레드가 강제 취소되면서 glibc 가 abort 한다. daemon=True 라 "알아서 죽겠지"로
두면 안 되는 이유다. 더 나쁜 건 그 경로에서 `cap.release()` 를 못 타 **카메라가 깨끗이
반납되지 않는다**는 점이다 — 다음 실행이 "Device or resource busy" 로 실패할 수 있다.
"""


def _capture_loop(index: int, width: int, height: int, fps: float,
                  quality: int, rotate180: bool, flip_h: bool, flip_v: bool) -> None:
    """카메라를 계속 읽어 최신 JPEG 한 장을 갱신한다."""
    global _latest_jpeg, _latest_seq, _capture_alive, _capture_error, _camera_label

    # CAP_DSHOW 를 쓰지 않는다 — 그건 Windows(DirectShow) 전용이고 여기는 리눅스다.
    # 스테이션 노트북 코드(serve.py)와 다른 부분이니 복사해 오지 말 것.
    cap = cv2.VideoCapture(index)
    if not cap.isOpened():
        _capture_error = f"카메라 {index} 를 열 수 없습니다. /dev/video* 와 fuser 확인"
        print(_capture_error, file=sys.stderr, flush=True)
        return

    cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
    got_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    got_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    _camera_label = f"/dev/video{index} {got_w}x{got_h}"
    print(f"카메라 {index}: 요청 {width}x{height} → 실제 {got_w}x{got_h}", flush=True)

    _capture_alive = True
    encode_params = [int(cv2.IMWRITE_JPEG_QUALITY), quality]
    min_interval = 1.0 / fps if fps > 0 else 0.0
    fail_streak = 0

    try:
        while not _stop.is_set():
            started = time.monotonic()
            ok, frame = cap.read()
            if not ok:
                fail_streak += 1
                # 일시적인 read 실패는 흔하다. 다만 계속 실패하면 카메라가 빠진 것이므로
                # 조용히 도는 대신 상태를 남긴다 — /health 가 이 값을 보고한다.
                if fail_streak >= 30:
                    _capture_alive = False
                    _capture_error = "카메라에서 30프레임 연속으로 읽지 못했습니다(연결 확인)"
                    print(_capture_error, file=sys.stderr, flush=True)
                    return
                # time.sleep 대신 _stop.wait — 종료 신호에 즉시 반응한다.
                if _stop.wait(0.1):
                    break
                continue
            fail_streak = 0

            if rotate180:
                frame = cv2.rotate(frame, cv2.ROTATE_180)
            # 회전 뒤에 적용한다 — 순서를 바꾸면 결과가 달라진다(회전과 반전은 교환되지 않는다).
            if flip_h:
                frame = cv2.flip(frame, 1)
            if flip_v:
                frame = cv2.flip(frame, 0)

            ok, buf = cv2.imencode(".jpg", frame, encode_params)
            if not ok:
                continue

            with _frame_lock:
                _latest_jpeg = buf.tobytes()
                _latest_seq += 1

            if min_interval:
                remain = min_interval - (time.monotonic() - started)
                if remain > 0:
                    _stop.wait(remain)
    finally:
        _capture_alive = False
        # 여기까지 반드시 와야 카메라가 반납된다(위 _stop 주석 참고).
        cap.release()
        print("카메라를 반납했습니다.", flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # TCP_NODELAY. 기본값(False)이면 Nagle 알고리즘이 작은 쓰기를 모아 보내려고 기다리는데,
    # 그 대기가 지연 응답과 맞물리면 최대 수백 ms 가 그냥 붙는다. 관제 영상은 "지금 모습"이
    # 중요하므로 대역폭 효율보다 지연을 택한다.
    disable_nagle_algorithm = True

    def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler 규약)
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        if path in ("/", "/stream"):
            self._serve_stream()
        elif path == "/health":
            self._serve_health()
        elif path == "/snapshot":
            self._serve_snapshot()
        else:
            self.send_error(404, "not found")

    def _serve_health(self) -> None:
        import json

        body = json.dumps({
            "ok": _capture_alive,
            "camera": _camera_label,
            "frames": _latest_seq,
            "error": _capture_error,
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _serve_snapshot(self) -> None:
        """단일 JPEG 한 장. MJPEG 이 안 뜰 때 카메라 자체를 가르는 데 쓴다."""
        with _frame_lock:
            jpeg = _latest_jpeg
        if jpeg is None:
            self.send_error(503, "no frame yet")
            return
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(jpeg)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(jpeg)

    def _serve_stream(self) -> None:
        if not _capture_alive and _latest_jpeg is None:
            self.send_error(503, _capture_error or "camera not ready")
            return

        self.send_response(200)
        self.send_header("Content-Type", f"multipart/x-mixed-replace; boundary={BOUNDARY}")
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        self.send_header("Pragma", "no-cache")
        # Content-Length 를 모르는 무한 응답이라 keep-alive 를 쓰지 않는다.
        self.send_header("Connection", "close")
        self.end_headers()

        sent_seq = -1
        try:
            while True:
                with _frame_lock:
                    jpeg, seq = _latest_jpeg, _latest_seq
                # 같은 프레임을 다시 보내지 않는다 — 브라우저가 같은 그림을 다시 디코딩하고
                # 대역폭만 쓴다. 새 프레임이 올 때까지 짧게 잔다.
                if jpeg is None or seq == sent_seq:
                    if not _capture_alive and jpeg is None:
                        return
                    time.sleep(0.005)
                    continue
                sent_seq = seq

                # 한 번에 쓴다. 나눠 쓰면 wfile 이 버퍼링하지 않는 탓에 조각마다 TCP 세그먼트가
                # 생기고, 헤더만 먼저 나간 뒤 본문이 다음 왕복으로 밀릴 수 있다.
                self.wfile.write(
                    b"--" + BOUNDARY.encode() + b"\r\n"
                    b"Content-Type: image/jpeg\r\n"
                    + f"Content-Length: {len(jpeg)}\r\n\r\n".encode()
                    + jpeg
                    + b"\r\n"
                )
        except (BrokenPipeError, ConnectionResetError):
            # 관제 화면을 닫거나 새로고침하면 매번 일어난다. 정상이므로 조용히 끝낸다.
            return

    def log_message(self, fmt: str, *args) -> None:
        """접속 1건마다 한 줄만 남긴다(MJPEG 은 연결이 길어 기본 로그가 시끄럽지 않다)."""
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="온보드 카메라 MJPEG 스트림")
    ap.add_argument("--camera", type=int, default=0, help="/dev/videoN 의 N")
    ap.add_argument("--port", type=int, default=8878,
                    help="8877(추론 서버)과 겹치지 않게 기본 8878")
    ap.add_argument("--host", default="0.0.0.0",
                    help="기본은 전체 인터페이스 — 관제 화면(다른 PC)이 붙어야 한다")
    ap.add_argument("--width", type=int, default=1280)
    # 실측: 이 USB 카메라(Suyin HD Camera)는 720 을 요청해도 800 으로 잡힌다(2026-08-06).
    # onboard_live.py 가 쓰는 학습·평가 기준 해상도와도 같은 값이라 기본값을 800 으로 둔다.
    ap.add_argument("--height", type=int, default=800)
    ap.add_argument("--fps", type=float, default=15.0,
                    help="캡처 상한. 관제 화면 표시용이라 30 까지 갈 이유가 없다")
    ap.add_argument("--quality", type=int, default=70,
                    help="JPEG 품질(1~100). 높이면 WiFi 대역폭을 그만큼 더 쓴다")
    ap.add_argument("--rotate180", action="store_true",
                    help="카메라를 뒤집어 장착했다면 지정(onboard_live.py 와 같은 규약)")
    # ⚠️ 반전은 "카메라가 미러링해서 내보내는" 경우에만 쓴다. 화면 속 글자가 뒤집혀 보인다고
    #    무조건 켜면 안 된다 — 간판·표지의 뒷면을 보고 있는 것일 수도 있고, 그때 반전을 걸면
    #    좌우가 실제와 반대가 되어 관제 화면에서 방향을 오판하게 만든다.
    ap.add_argument("--flip-h", action="store_true", help="좌우 반전(회전 적용 뒤)")
    ap.add_argument("--flip-v", action="store_true", help="상하 반전(회전 적용 뒤)")
    a = ap.parse_args(argv)

    worker = threading.Thread(
        target=_capture_loop,
        args=(a.camera, a.width, a.height, a.fps, a.quality,
              a.rotate180, a.flip_h, a.flip_v),
        daemon=True,
    )
    worker.start()

    # 기동 시점에 "정말 영상이 흐르는가"를 확인한다.
    #
    # ⚠️ cap.isOpened() 만 믿으면 안 된다. GStreamer 는 **open 은 성공시키고 재생 단계에서
    #    실패**할 수 있다 — 실측(2026-08-06): 카메라가 지원하지 않는 해상도(640x400)를
    #    요청하니 isOpened()=True 인데 실제 해상도가 0x0 이고 모든 read 가 실패했다.
    #    그때 서버가 그대로 떠 버려서 "주소는 살아 있는데 영상이 없는" 상태가 됐다.
    #    그러니 프레임 한 장이 실제로 인코딩될 때까지 기다린 뒤에만 서버를 연다.
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        if _capture_error:
            break
        with _frame_lock:
            if _latest_seq > 0:
                break
        time.sleep(0.1)

    if _capture_error:
        print(f"카메라를 열지 못해 종료합니다: {_capture_error}", file=sys.stderr, flush=True)
        return 1
    with _frame_lock:
        first_frame_ok = _latest_seq > 0
    if not first_frame_ok:
        _stop.set()
        worker.join(timeout=3.0)
        print("10초 안에 프레임을 한 장도 받지 못해 종료합니다. "
              "요청한 해상도를 카메라가 지원하는지 확인하세요 "
              "(v4l2-ctl --list-formats-ext -d /dev/video0).",
              file=sys.stderr, flush=True)
        return 1

    server = ThreadingHTTPServer((a.host, a.port), Handler)
    server.daemon_threads = True
    print(f"MJPEG 스트림: http://{a.host}:{a.port}/stream  (health: /health)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("종료합니다.", flush=True)
    finally:
        server.server_close()
        # 캡처 스레드를 먼저 정리하고 나간다. 순서가 중요하다 — 그냥 return 하면
        # cap.read() 안에 있던 네이티브 스레드가 강제 취소되며 코어 덤프가 난다
        # (_stop 주석의 실측 참고). join 타임아웃은 read 한 번이 끝나기를 기다리는
        # 시간이라 짧아도 충분하다.
        _stop.set()
        worker.join(timeout=3.0)
        if worker.is_alive():
            # 여기 오면 read 가 3초 넘게 안 돌아온 것이다. 알리고 나간다 —
            # 조용히 나가면 다음 실행의 "Device busy" 원인을 찾을 단서가 없다.
            print("경고: 캡처 스레드가 제때 끝나지 않았습니다(카메라 반납 확인 필요)",
                  file=sys.stderr, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
