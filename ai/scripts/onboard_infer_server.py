"""온보드 추론 서버 — 스테이션이 보낸 프레임을 젯슨 TensorRT로 추론한다 (S15P11A304-91).

시연에서 카메라·거리계는 의자 위 노트북에 붙어 있지만 **모델은 지게차 보드에서 돈다.**
실사용 시 온보드 유닛이 하는 일을 그대로 보이기 위한 배치다.

    # 젯슨에서
    PYTHONPATH=src python3 scripts/onboard_infer_server.py \\
        --engine ~/calib/station_m800_fp32.engine \\
        --plugin ~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so

## 프로토콜

    POST /infer      body = letterbox된 800x800 uint8 PNG, 헤더 X-Scale = letterbox 배율
                     → {"detections": [{"label","score","x","y","w","h"}, ...]}
    GET  /health     → {"ok": true, "engine": "...", "input_size": 800}

**클라이언트가 letterbox까지 하고 uint8을 보낸다.** 이유가 둘이다:

1. **정확도가 안 변한다.** 정보 손실은 어차피 letterbox에서 일어난다. 그 결과를 무손실
   (PNG)로 보내면 젯슨이 보는 픽셀이 노트북 모델이 볼 픽셀과 **완전히 같다.**
   원본 프레임을 JPEG로 보내면 픽셀이 바뀌어 치수에 영향을 준다.
2. **작다.** 1920x1080 원본 PNG는 2~4MB인데 letterbox된 800x800은 0.5~1MB다.
   WiFi로 오가야 하므로 이 차이가 크다.

정규화(`(x-mean)/std`)는 서버가 한다 — 같은 uint8 입력이면 어느 기계에서 해도 결과가
같은 원소별 float32 연산이라 정확도에 영향이 없다.

⚠️ **단일 스레드 서버다.** `ThreadingHTTPServer`를 쓰면 안 된다 — **pycuda CUDA 컨텍스트는
   생성한 스레드에 묶여** 있어서, 요청마다 새 스레드에서 실행하면
   `enqueueV3`가 `Cuda Runtime (invalid resource handle)`로 실패한다.
   (2026-07-31 실측. 더 나쁜 건 그 실패가 **예외가 아니라 검출 0개**로 나가서, 서버는
   200을 응답하고 클라이언트는 "물체가 없나 보다" 하고 넘어간다는 점이다.)
   측정은 1회성이라 직렬 처리로 충분하다.
"""
from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

import numpy as _np

import cv2
import numpy as np

from perception.trt_detector import TrtDetector

MAX_BODY = 32 * 1024 * 1024      # 32MB — 800x800 PNG는 1MB 남짓이라 넉넉하다

_detector: TrtDetector | None = None
_lock = threading.Lock()
_engine_path = ""
_input_size = 0


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path.rstrip("/") == "/health":
            self._json(200, {"ok": _detector is not None,
                             "engine": _engine_path, "input_size": _input_size})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path.rstrip("/") != "/infer":
            self._json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self._json(400, {"error": "Content-Length 없음"})
            return
        if not 0 < length <= MAX_BODY:
            self._json(400, {"error": f"본문 크기 이상: {length}"})
            return
        try:
            scale = float(self.headers.get("X-Scale") or 0)
        except ValueError:
            scale = 0.0
        if scale <= 0:
            # scale이 없으면 bbox를 원본 좌표로 되돌릴 수 없다. 조용히 1.0으로
            # 넘어가면 치수가 통째로 틀리므로 거부한다.
            self._json(400, {"error": "X-Scale 헤더 필요(letterbox 배율)"})
            return

        raw = self.rfile.read(length)
        img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            self._json(400, {"error": "이미지 디코딩 실패"})
            return
        if img.shape[:2] != (_input_size, _input_size):
            self._json(400, {"error": f"letterbox 크기 불일치: {img.shape[:2]} "
                                      f"!= ({_input_size}, {_input_size})"})
            return

        t0 = time.perf_counter()
        try:
            with _lock:                  # 실행 컨텍스트가 하나뿐이라 직렬화
                dets = _detector.detect_letterboxed(img, scale)
        except Exception as e:
            # 추론 실패를 200 + 빈 목록으로 돌려주면 클라이언트가 "물체 없음"으로 오해한다.
            # 측정 시스템에서 그건 조용히 틀린 답이므로 500으로 명확히 실패시킨다.
            print(f"  ❌ 추론 실패: {type(e).__name__}: {e}", flush=True)
            self._json(500, {"error": f"추론 실패: {e}"})
            return
        ms = (time.perf_counter() - t0) * 1000

        self._json(200, {
            "detections": [{"label": d.label, "score": d.score,
                            "x": d.box.x, "y": d.box.y,
                            "w": d.box.w, "h": d.box.h} for d in dets],
            "inference_ms": round(ms, 1),
        })
        print(f"  추론 {ms:6.1f}ms  검출 {len(dets)}개  scale={scale:.4f}", flush=True)

    def log_message(self, fmt, *args) -> None:
        pass                              # 기본 액세스 로그는 시끄러워 끈다


def main(argv=None) -> int:
    global _detector, _engine_path, _input_size
    ap = argparse.ArgumentParser(description="온보드 추론 서버")
    ap.add_argument("--engine", required=True)
    ap.add_argument("--plugin", required=True)
    ap.add_argument("--host", default="0.0.0.0")
    # 1024 미만은 root 권한이 필요하다 — 젯슨에서 일반 계정으로 띄우므로 그 위로 잡는다.
    ap.add_argument("--port", type=int, default=8877)
    ap.add_argument("--input-size", type=int, default=800,
                    help="스테이션 모델 입력. 클라이언트 letterbox 크기와 같아야 한다")
    ap.add_argument("--score", type=float, default=0.5,
                    help="기본 임계. 클래스별 임계는 스테이션 config가 최종 판정에서 적용")
    a = ap.parse_args(argv)

    print(f"엔진 로드: {a.engine}", flush=True)
    _detector = TrtDetector(a.engine, a.plugin, input_size=a.input_size,
                            score_threshold=a.score,
                            class_names=("box", "pallet"),
                            rotate180=False,          # 스테이션 카메라는 정립
                            geometry_filter=False)    # hole 클래스가 없다
    _engine_path = str(a.engine)
    _input_size = a.input_size

    # 기동 자가진단 — 서버가 뜬 뒤에야 추론이 안 된다는 걸 알면 늦다. 합성 입력으로
    # 한 번 돌려서 실행 경로가 살아 있는지부터 확인한다(2026-07-31: 스레드-컨텍스트
    # 문제로 매 요청이 조용히 검출 0개를 내던 사고 재발 방지).
    probe = _np.full((a.input_size, a.input_size, 3), 114, dtype=_np.uint8)
    try:
        _detector.detect_letterboxed(probe, 1.0)
    except Exception as e:
        print(f"❌ 기동 자가진단 실패 — 추론이 동작하지 않는다: {e}", flush=True)
        return 1
    print("기동 자가진단 통과 (추론 경로 정상)", flush=True)

    srv = HTTPServer((a.host, a.port), Handler)
    print(f"대기: http://{a.host}:{a.port}/infer  (health: /health)", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n종료", flush=True)
    finally:
        srv.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
