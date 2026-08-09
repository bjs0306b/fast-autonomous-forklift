"""스테이션 라이브 송출 — 카메라 없이 도는 부분만 본다."""
from __future__ import annotations

import sys
import threading
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from perception.load_balance import BBox, Detection  # noqa: E402
from station.livestream import (CaptureThread, FrameBus,  # noqa: E402
                                Overlay, draw_detections)


class FakeCap:
    """`cap.read()` 만 흉내낸다. 프레임마다 내용이 달라 seq 증가를 확인할 수 있다."""

    def __init__(self, fail_after: int | None = None) -> None:
        self.n = 0
        self.fail_after = fail_after

    def read(self):
        self.n += 1
        if self.fail_after is not None and self.n > self.fail_after:
            return False, None
        return True, np.full((4, 4, 3), self.n % 256, dtype=np.uint8)


def test_새_프레임만_돌려준다() -> None:
    """정지 판정이 연속 두 프레임의 차를 본다 — 같은 장을 두 번 주면 차가 0 이라
    흔들리는 중에도 '멎었다'가 된다."""
    bus = FrameBus()
    bus.publish(np.zeros((2, 2, 3), dtype=np.uint8))
    frame, seq = bus.next(-1, timeout=0.1)
    assert frame is not None

    # 새 프레임이 없으면 같은 것을 다시 주지 않고 기다렸다 포기한다.
    again, same_seq = bus.next(seq, timeout=0.05)
    assert again is None
    assert same_seq == seq


def test_대기_중_새_프레임이_오면_깨어난다() -> None:
    bus = FrameBus()

    def publish_later() -> None:
        time.sleep(0.05)
        bus.publish(np.ones((2, 2, 3), dtype=np.uint8))

    threading.Thread(target=publish_later, daemon=True).start()
    frame, seq = bus.next(-1, timeout=2.0)
    assert frame is not None
    assert seq == 1


def test_캡처_스레드를_세우고_join_할_수_있다() -> None:
    """⚠️ 회귀 시험. 정지 이벤트를 `self._stop` 에 넣으면 `threading.Thread` 의 내부
    `_stop()` 메서드를 덮어써 `join()` 이 `TypeError: 'Event' object is not callable`
    로 죽는다. 종료 경로라 평소엔 안 드러나고 Ctrl-C 때 터진다."""
    bus = FrameBus()
    stop = threading.Event()
    th = CaptureThread(FakeCap(), bus, stop, fps=200)
    th.start()
    bus.next(-1, timeout=2.0)          # 한 장은 나왔는지 확인
    stop.set()
    th.join(timeout=2.0)               # 여기서 TypeError 가 났었다
    assert not th.is_alive()


def test_검출을_그리면_원본은_그대로다() -> None:
    """송출용으로 그린 그림이 측정용 프레임을 오염시키면 안 된다 — 같은 배열을
    측정이 이어서 쓴다."""
    frame = np.zeros((200, 200, 3), dtype=np.uint8)
    dets = [Detection(label="box", box=BBox(10, 20, 50, 40), score=0.93)]
    drawn = draw_detections(frame, dets)
    assert frame.max() == 0            # 원본은 여전히 검다
    assert drawn.max() > 0             # 사본에는 상자가 그려졌다
    assert drawn is not frame


def test_검출이_없으면_같은_프레임을_그대로_쓴다() -> None:
    """복사 비용을 매 프레임 내지 않는다."""
    frame = np.zeros((8, 8, 3), dtype=np.uint8)
    assert draw_detections(frame, []) is frame


def test_오래된_검출은_그리지_않는다() -> None:
    """추론이 멎었는데 마지막 상자를 계속 그리면 화면이 '지금 잡고 있다'고 거짓말한다."""
    overlay = Overlay()
    overlay.publish([Detection(label="pallet", box=BBox(0, 0, 4, 4), score=0.9)])
    assert len(overlay.read(max_age_s=5.0)) == 1
    # `max_age_s=0` 으로 재면 시계 해상도에 따라 경과가 0.0 으로 나와 흔들린다.
    # 확실히 지난 뒤에 본다.
    time.sleep(0.05)
    assert overlay.read(max_age_s=0.01) == []


def test_계속_실패하면_상태에_남긴다() -> None:
    """조용히 도는 대신 /health 가 원인을 보고해야 한다."""
    bus = FrameBus()
    stop = threading.Event()
    th = CaptureThread(FakeCap(fail_after=2), bus, stop, fps=0)
    th.start()
    th.join(timeout=5.0)
    stop.set()
    health = bus.health()
    assert health["ok"] is False
    assert "30프레임" in (health["error"] or "")
