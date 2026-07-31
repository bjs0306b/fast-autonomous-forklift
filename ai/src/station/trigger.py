"""측정 트리거 — 시뮬 신호를 받아 스테이션이 스스로 측정하게 한다 (S15P11A304-91).

시연에서 스테이션(의자 위 노트북)은 지게차와 함께 움직이고, **Isaac Sim이 적재 위치에
도착하면 신호를 쏜다.** 사람이 버튼을 누르는 방식은 "무인 스마트팩토리"와 맞지 않는다.

    from station.trigger import MeasureTrigger, wait_until_still

    with MeasureTrigger(broker="70.12.130.106") as trig:
        for req in trig.requests():          # 신호가 올 때까지 블록
            wait_until_still(grab_frame)     # 흔들림이 멎을 때까지
            measure(req.cargo_id)

## ⚠️ `cargoId`가 반드시 실려 와야 한다

백엔드 세션은 `POST /api/stations/sessions?cargoId=...`로 열고, `cargo_id`는 `cargo`
테이블에 FK로 걸려 있다. **위치 좌표만으로는 측정을 시작할 수 없다** — 차량 위치
메시지(`forklift/+/location`)에는 화물 정보가 없다.

## 왜 좌표 존 판정이 아니라 이벤트인가

"도착했다"는 **시뮬이 이미 아는 사실**이다. 스테이션이 좌표로 다시 판정하면 판단이 두
벌이 되고, 시뮬 쪽 정지 위치가 조금만 바뀌어도 스테이션 존을 같이 고쳐야 한다.
게다가 위 이유로 `cargoId`를 어차피 따로 받아야 한다.

토픽·필드 이름은 **설정으로 뺐다** — 시뮬 쪽 규격이 확정되기 전에도 붙일 수 있고,
바뀌어도 코드를 안 고친다.
"""

from __future__ import annotations

import json
import queue
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Callable, Iterator

import numpy as np

DEFAULT_TOPIC = "fast/station/measure_request"
DEFAULT_BROKER_PORT = 1883

# --- 정지 판정 ---
STILL_DIFF = 2.0
"""연속 프레임의 평균 밝기 차(0~255). 이보다 작으면 멈춘 것으로 본다.

사람이 의자를 밀다 멈춘 직후엔 아직 흔들린다. 그 프레임으로 측정하면 두 가지가 깨진다:
**① 모션 블러**로 bbox 경계가 뭉개지고, **② 화물이 프레임 가장자리**에 걸려 렌즈 왜곡
오차가 커진다(실측 +6.2mm, CLAUDE.md 측정 조건). 캘리브레이션에서 같은 이유로 20장 중
14장을 버린 전례가 있다."""

STILL_FRAMES = 3
"""연속 이만큼이 조용해야 멈춘 것으로 인정한다. 한 프레임만 보면 우연히 조용한 순간을
잡는다."""

STILL_TIMEOUT_S = 5.0
"""이 시간 안에 안 멎으면 포기한다. 무한정 기다리면 시연이 멈춘 것처럼 보인다."""


@dataclass(frozen=True)
class MeasureRequest:
    """측정 요청 한 건."""

    cargo_id: str
    raw: dict = field(default_factory=dict)


def frame_motion(a: np.ndarray, b: np.ndarray) -> float:
    """두 프레임의 평균 절대 차이. 흔들릴수록 커진다.

    그레이스케일 변환 없이 채널 평균으로 충분하다 — 상대 비교만 하면 되고, 색 변환은
    프레임마다 비용이 든다.
    """
    if a is None or b is None or a.shape != b.shape:
        return float("inf")
    return float(np.mean(np.abs(a.astype(np.int16) - b.astype(np.int16))))


def wait_until_still(grab: Callable[[], np.ndarray | None],
                     diff_threshold: float = STILL_DIFF,
                     needed: int = STILL_FRAMES,
                     timeout_s: float = STILL_TIMEOUT_S,
                     on_progress: Callable[[float, int], None] | None = None
                     ) -> tuple[np.ndarray | None, bool]:
    """흔들림이 멎을 때까지 기다렸다가 그 프레임을 돌려준다.

    `grab()`은 프레임을 하나 주는 함수다(카메라든 테스트용 가짜든). 반환은
    `(프레임, 안정여부)` — 타임아웃이면 마지막 프레임과 `False`를 준다. **측정을 아예
    거르지 않고 마지막 프레임이라도 주는 이유**는, 시연 중 "아무 일도 안 일어남"보다
    "흔들린 채로 쟀고 그렇게 기록됨"이 낫기 때문이다. 호출자가 플래그를 보고 판단한다.
    """
    deadline = time.monotonic() + timeout_s
    prev = grab()
    calm = 0
    frame = prev
    while time.monotonic() < deadline:
        cur = grab()
        if cur is None:
            break
        motion = frame_motion(prev, cur)
        calm = calm + 1 if motion <= diff_threshold else 0
        if on_progress:
            on_progress(motion, calm)
        prev, frame = cur, cur
        if calm >= needed:
            return frame, True
    return frame, False


class MeasureTrigger:
    """MQTT로 측정 요청을 받는다. `with` 안에서 `requests()`를 돌린다.

    브로커·토픽·필드명이 전부 인자다 — 시뮬 쪽 규격이 확정 전이라 코드를 안 고치고
    맞출 수 있어야 한다.
    """

    def __init__(self, broker: str, port: int = DEFAULT_BROKER_PORT,
                 topic: str = DEFAULT_TOPIC, cargo_field: str = "cargoId",
                 client_id: str = "fast-station", keepalive: int = 60) -> None:
        self.broker = broker
        self.port = port
        self.topic = topic
        self.cargo_field = cargo_field
        self.client_id = client_id
        self.keepalive = keepalive
        self._q: queue.Queue[MeasureRequest] = queue.Queue()
        self._client = None
        self._stop = threading.Event()

    # --- 수명 ---

    def __enter__(self) -> "MeasureTrigger":
        import paho.mqtt.client as mqtt

        self._client = mqtt.Client(client_id=self.client_id)
        self._client.on_connect = self._on_connect
        self._client.on_message = self._on_message
        self._client.connect(self.broker, self.port, self.keepalive)
        self._client.loop_start()
        return self

    def __exit__(self, *exc) -> None:
        self._stop.set()
        if self._client is not None:
            self._client.loop_stop()
            self._client.disconnect()
        return None

    # --- 콜백 ---

    def _on_connect(self, client, userdata, flags, rc) -> None:
        if rc != 0:
            print(f"[trigger] ❌ 브로커 연결 실패 rc={rc}", file=sys.stderr)
            return
        client.subscribe(self.topic)
        print(f"[trigger] 구독: {self.topic} @ {self.broker}:{self.port}", flush=True)

    def _on_message(self, client, userdata, msg) -> None:
        try:
            data = json.loads(msg.payload.decode("utf-8"))
        except Exception as e:
            print(f"[trigger] ⚠️ 페이로드 파싱 실패 — 무시: {e}", file=sys.stderr)
            return
        cargo = data.get(self.cargo_field)
        if not cargo:
            # cargoId 없이는 세션을 못 연다. 조용히 넘기면 "신호를 보냈는데 아무 일도
            # 안 일어난다"가 되므로 이유를 남긴다.
            print(f"[trigger] ⚠️ '{self.cargo_field}' 없음 — 측정 불가. 받은 키: "
                  f"{sorted(data)}", file=sys.stderr)
            return
        self._q.put(MeasureRequest(cargo_id=str(cargo), raw=data))
        print(f"[trigger] 측정 요청 수신: cargoId={cargo}", flush=True)

    # --- 소비 ---

    def requests(self, poll_s: float = 0.5) -> Iterator[MeasureRequest]:
        """요청이 올 때까지 기다렸다가 하나씩 내준다. Ctrl-C로 빠져나온다."""
        while not self._stop.is_set():
            try:
                yield self._q.get(timeout=poll_s)
            except queue.Empty:
                continue
