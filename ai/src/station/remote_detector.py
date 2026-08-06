"""원격 추론 클라이언트 — 프레임을 온보드 보드로 보내 추론한다 (S15P11A304-91).

`OnnxDetector`와 **같은 인터페이스**(`detect(frame_bgr) → list[Detection]`)라 스테이션
파이프라인의 나머지를 건드리지 않고 갈아끼운다. 서버는 `scripts/onboard_infer_server.py`.

    det = RemoteDetector("http://70.12.247.81:8877", cfg)
    dets = det.detect(frame)
    print(det.last_path)      # "onboard" 또는 "local"

⚠️ **주소는 IP로 준다.** 종전 이 예시가 `http://orin-desktop.local:8877` 이었는데
**mDNS 이름은 해석되지 않는다**(2026-08-03 실측, S15P11A304-183에서 확인). 그대로
복사해 쓰면 연결 실패로 **로컬 ONNX 폴백**이 되고, 보드에서 도는 줄 알고 넘어가게
된다(`last_path`를 보면 드러난다).

젯슨은 DHCP지만 재부팅해도 `70.12.247.81`을 그대로 재발급받았다. 고정 IP는 **일부러
안 하기로 했다**(시연 주간에 네트워크를 건드리는 위험이 더 크다) — 대신 측정 전에
`python -m station.serve --check` 로 연결을 확인한다.

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
                 local_detector=None,
                 score_threshold: float = 0.5,
                 class_thresholds: dict[str, float] | None = None) -> None:
        """`score_threshold`·`class_thresholds`는 **로컬 검출기와 같은 값**을 준다.

        ⚠️ **판정을 서버에 맡기면 경로에 따라 결과가 갈린다.** 2026-07-31 실측: 서버가
        온보드 기본값(`pallet 0.7`)을 쓰는 바람에, 스테이션 임계(0.4)로는 통과할 파렛트
        (score 0.58~0.65)가 보드에서만 버려졌다. 같은 장면이 로컬은 `ok`, 보드는
        `dimensions_only`가 되어 전복·편하중이 통째로 빠졌다.

        그래서 서버는 후보만 돌려주고 **거르는 일은 여기서** 한다 — 로컬 경로와 같은
        코드·같은 값이라 두 경로가 갈릴 수 없다.
        """
        self.base_url = base_url.rstrip("/")
        self.input_size = input_size
        self.timeout = timeout
        self.local_detector = local_detector
        self.score_threshold = score_threshold
        self.class_thresholds = dict(class_thresholds or {})
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
        out = []
        for d in payload.get("detections", []):
            label = d["label"]
            score = float(d["score"])
            # 판정은 여기서 — 로컬 검출기와 같은 임계를 쓴다(위 docstring 참조).
            if score < self.class_thresholds.get(label, self.score_threshold):
                continue
            out.append(Detection(label=label, score=score,
                                 box=BBox(x=float(d["x"]), y=float(d["y"]),
                                          w=float(d["w"]), h=float(d["h"]))))
        return out
