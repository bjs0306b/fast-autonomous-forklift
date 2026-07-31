"""원격 추론 클라이언트 테스트 (S15P11A304-91).

WiFi가 끊기는 상황이 시연 중에 실제로 일어날 수 있으므로 **폴백 동작을 고정**한다.
서버 없이 검증한다 — HTTP 호출부를 갈아끼운다.
"""

from __future__ import annotations

import numpy as np
import pytest

from perception.load_balance import BBox, Detection
from station import remote_detector as rd
from station.remote_detector import RemoteDetector


class FakeLocal:
    """폴백용 로컬 검출기 — 호출 여부를 기록한다."""

    def __init__(self) -> None:
        self.calls = 0

    def detect(self, frame):
        self.calls += 1
        return [Detection(label="box", box=BBox(x=1, y=2, w=3, h=4), score=0.9)]


FRAME = np.zeros((1080, 1920, 3), np.uint8)


def _ok_response(monkeypatch, dets, inference_ms=42.0):
    def fake(self, frame):
        self.last_inference_ms = inference_ms
        return dets
    monkeypatch.setattr(RemoteDetector, "_detect_remote", fake)


def test_성공하면_보드_경로로_기록된다(monkeypatch) -> None:
    want = [Detection(label="pallet", box=BBox(x=5, y=6, w=7, h=8), score=0.95)]
    _ok_response(monkeypatch, want)
    local = FakeLocal()
    det = RemoteDetector("http://board:8877", local_detector=local)

    assert det.detect(FRAME) == want
    assert det.last_path == "onboard"
    assert local.calls == 0, "성공했는데 로컬이 돌면 안 된다"


def test_실패하면_로컬로_떨어지고_경로가_남는다(monkeypatch) -> None:
    """시연 중 WiFi가 끊겨도 측정이 죽지 않아야 한다."""
    def boom(self, frame):
        raise TimeoutError("timed out")
    monkeypatch.setattr(RemoteDetector, "_detect_remote", boom)

    local = FakeLocal()
    det = RemoteDetector("http://board:8877", local_detector=local)
    out = det.detect(FRAME)

    assert local.calls == 1
    assert out[0].label == "box"
    assert det.last_path == "local"
    assert "TimeoutError" in det.last_error


def test_폴백이_stderr에_찍힌다(monkeypatch, capsys) -> None:
    """조용히 떨어지면 '보드에서 추론한다'는 설명이 사실과 달라진다."""
    monkeypatch.setattr(RemoteDetector, "_detect_remote",
                        lambda self, f: (_ for _ in ()).throw(OSError("네트워크 없음")))
    RemoteDetector("http://board:8877", local_detector=FakeLocal()).detect(FRAME)
    assert "로컬 폴백" in capsys.readouterr().err


def test_폴백이_없으면_예외를_올린다(monkeypatch) -> None:
    """폴백 여부를 호출자가 명시하게 한다 — 모르는 사이 로컬로 도는 상태를 막는다."""
    monkeypatch.setattr(RemoteDetector, "_detect_remote",
                        lambda self, f: (_ for _ in ()).throw(OSError("끊김")))
    det = RemoteDetector("http://board:8877")          # local_detector 없음
    with pytest.raises(OSError):
        det.detect(FRAME)
    assert det.last_path == "none"


def test_letterbox해서_보낸다(monkeypatch) -> None:
    """원본이 아니라 letterbox된 uint8을 보내야 크기·정확도가 둘 다 맞는다."""
    sent = {}

    class FakeResp:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return b'{"detections": [], "inference_ms": 12.3}'

    def fake_urlopen(req, timeout=None):
        sent["len"] = len(req.data)
        sent["scale"] = float(req.headers["X-scale"])
        sent["magic"] = req.data[:8]
        return FakeResp()

    monkeypatch.setattr(rd.urllib.request, "urlopen", fake_urlopen)
    det = RemoteDetector("http://board:8877", input_size=800)
    assert det.detect(FRAME) == []

    assert sent["magic"].startswith(b"\x89PNG"), "무손실 PNG여야 한다"
    # 1920x1080 → 800 letterbox 이므로 800/1920
    assert sent["scale"] == pytest.approx(800 / 1920)
    assert det.last_inference_ms == 12.3
