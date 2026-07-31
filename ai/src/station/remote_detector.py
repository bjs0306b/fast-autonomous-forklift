"""원격 추론 클라이언트 — 프레임을 온보드 보드로 보내 추론한다 (S15P11A304-91).

`OnnxDetector`와 **같은 인터페이스**(`detect(frame_bgr) → list[Detection]`)라 스테이션
파이프라인의 나머지를 건드리지 않고 갈아끼운다. 서버는 `scripts/onboard_infer_server.py`.

    det = RemoteDetector("http://orin-desktop.local:8877", cfg)
    dets = det.detect(frame)
    print(det.last_path)      # "onboard" 또는 "local"

## ⚠️ 폴백은 감추지 않는다

WiFi가 끊기면 **노트북의 로컬 ONNX로 떨어진다.** 시연이 실패하지 않으려면 필요한
장치지만, "보드에서 추론한다"가 요지인 자리에서 조용히 노트북으로 넘어가면 **시연이
사실과 달라진다.** 그래서 `last_path`로 어느 경로였는지 항상 남기고, 호출자가 화면에
표시하도록 한다. 폴백이 일어나면 stderr에도 찍는다.

## 왜 letterbox까지 하고 보내나

정보 손실은 어차피 letterbox에서 일어난다. 그 결과를 무손실(PNG)로 보내면 보드가 보는
픽셀이 로컬 모델이 볼 픽셀과 **완전히 같다** — 즉 원격/로컬 경로의 검출이 일치한다.
원본 프레임을 JPEG로 보내면 픽셀이 바뀌어 치수(KPI ≤4mm)에 영향을 준다.
덤으로 전송량도 1/4로 준다(2~4MB → 0.5~1MB).
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

import cv2
import numpy as np

from perception.load_balance import BBox, Detection
from perception.preprocess import letterbox

DEFAULT_TIMEOUT = 1.5
"""전송+추론 타임아웃(초). 측정 예산이 1초라 넉넉히 잡되 무한정 기다리지 않는다.
실측: 추론 50ms + PNG 0.5~1MB 전송. 이보다 오래 걸리면 WiFi 문제이므로 폴백이 낫다."""


class RemoteDetector:
    """보드로 보내 추론하고, 실패하면 로컬로 떨어진다.

    `local_detector`를 주지 않으면 폴백 없이 예외를 올린다 — 폴백 여부를 호출자가
    명시하게 해서 "왜 로컬로 돌았는지 모르는" 상태를 만들지 않는다.
    """

    def __init__(self, base_url: str, input_size: int = 800,
                 timeout: float = DEFAULT_TIMEOUT,
                 local_detector=None) -> None:
        self.base_url = base_url.rstrip("/")
        self.input_size = input_size
        self.timeout = timeout
        self.local_detector = local_detector
        self.last_path = "none"
        self.last_error = ""
        self.last_inference_ms: float | None = None

    # --- 상태 확인 ---

    def health(self) -> dict | None:
        """서버가 살아 있나. 시연 전 점검용 — None이면 폴백으로 돌게 된다."""
        try:
            with urllib.request.urlopen(f"{self.base_url}/health", timeout=self.timeout) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception:
            return None

    # --- 추론 ---

    def detect(self, frame_bgr: np.ndarray) -> list[Detection]:
        try:
            dets = self._detect_remote(frame_bgr)
            self.last_path = "onboard"
            self.last_error = ""
            return dets
        except Exception as e:
            self.last_error = f"{type(e).__name__}: {e}"
            if self.local_detector is None:
                self.last_path = "none"
                raise
            # 폴백은 조용히 넘어가지 않는다 — 시연에서 이걸 모르면 사실과 다른 설명이 된다.
            print(f"[remote-infer] ⚠️ 보드 추론 실패 → **로컬 폴백**: {self.last_error}",
                  file=sys.stderr)
            self.last_path = "local"
            self.last_inference_ms = None
            return self.local_detector.detect(frame_bgr)

    def _detect_remote(self, frame_bgr: np.ndarray) -> list[Detection]:
        padded, scale = letterbox(frame_bgr, self.input_size)
        ok, buf = cv2.imencode(".png", padded)      # 무손실
        if not ok:
            raise RuntimeError("PNG 인코딩 실패")
        req = urllib.request.Request(
            f"{self.base_url}/infer", data=buf.tobytes(), method="POST",
            headers={"Content-Type": "image/png", "X-Scale": f"{scale:.10f}"},
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                payload = json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"HTTP {e.code}: {e.read().decode('utf-8')[:120]}") from None
        self.last_inference_ms = payload.get("inference_ms")
        return [
            Detection(label=d["label"], score=float(d["score"]),
                      box=BBox(x=float(d["x"]), y=float(d["y"]),
                               w=float(d["w"]), h=float(d["h"])))
            for d in payload.get("detections", [])
        ]
