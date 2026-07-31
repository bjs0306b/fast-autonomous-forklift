"""측정 트리거 테스트 (S15P11A304-91).

**정지 판정**은 하드웨어 없이 검증한다 — 프레임을 주는 함수만 갈아끼우면 된다.
흔들리는 프레임으로 측정하면 모션 블러와 가장자리 왜곡이 그대로 치수 오차가 되므로
(캘리브레이션에서 20장 중 14장을 같은 이유로 버렸다) 이 판정이 실제로 동작해야 한다.
"""

from __future__ import annotations

import json

import numpy as np
import pytest

from station.trigger import (MeasureRequest, MeasureTrigger, frame_motion,
                             wait_until_still)

H, W = 60, 80


def still_frame(v: int = 100) -> np.ndarray:
    return np.full((H, W, 3), v, np.uint8)


def noisy_frame(rng, base: int = 100, amp: int = 60) -> np.ndarray:
    n = rng.integers(-amp, amp, (H, W, 3))
    return np.clip(base + n, 0, 255).astype(np.uint8)


# --- 움직임 측정 ---

def test_같은_프레임은_움직임이_0() -> None:
    f = still_frame()
    assert frame_motion(f, f) == 0.0


def test_다른_프레임은_움직임이_크다() -> None:
    assert frame_motion(still_frame(50), still_frame(200)) == pytest.approx(150.0)


def test_shape가_다르면_무한대() -> None:
    """해상도가 바뀌면 비교가 무의미하다 — 조용히 0을 주면 '멈췄다'로 오판한다."""
    assert frame_motion(still_frame(), np.zeros((10, 10, 3), np.uint8)) == float("inf")


def test_프레임이_없으면_무한대() -> None:
    assert frame_motion(None, still_frame()) == float("inf")


# --- 정지 대기 ---

def test_멈춰_있으면_바로_통과한다() -> None:
    frame, still = wait_until_still(lambda: still_frame(), needed=3, timeout_s=2.0)
    assert still is True
    assert frame is not None


def test_계속_흔들리면_타임아웃이다() -> None:
    """무한정 기다리면 시연이 멈춘 것처럼 보인다 — 마지막 프레임이라도 준다."""
    rng = np.random.default_rng(0)
    frame, still = wait_until_still(lambda: noisy_frame(rng), needed=3, timeout_s=0.4)
    assert still is False
    assert frame is not None, "타임아웃이어도 프레임은 줘야 호출자가 판단한다"


def test_흔들리다_멎으면_통과한다() -> None:
    rng = np.random.default_rng(1)
    seq = {"n": 0}

    def grab():
        seq["n"] += 1
        return noisy_frame(rng) if seq["n"] <= 4 else still_frame()

    frame, still = wait_until_still(grab, needed=3, timeout_s=3.0)
    assert still is True


def test_연속으로_조용해야_인정한다() -> None:
    """한 프레임만 보면 우연히 조용한 순간을 잡는다."""
    rng = np.random.default_rng(2)
    seq = {"n": 0}

    def grab():
        seq["n"] += 1
        # 조용-시끄러움을 번갈아 → 연속 조건을 못 채운다
        return still_frame() if seq["n"] % 2 else noisy_frame(rng)

    _, still = wait_until_still(grab, needed=3, timeout_s=0.5)
    assert still is False


def test_프레임이_끊기면_중단한다() -> None:
    _, still = wait_until_still(lambda: None, needed=3, timeout_s=2.0)
    assert still is False


# --- MQTT 페이로드 처리 ---

class Msg:
    def __init__(self, payload: dict | str) -> None:
        raw = payload if isinstance(payload, str) else json.dumps(payload)
        self.payload = raw.encode("utf-8")


def _trigger() -> MeasureTrigger:
    return MeasureTrigger("broker.invalid")      # 연결하지 않고 콜백만 쓴다


def test_cargoId가_있으면_요청이_쌓인다() -> None:
    t = _trigger()
    t._on_message(None, None, Msg({"cargoId": "cargo-1", "forkliftId": "SIM-F01"}))
    req = t._q.get_nowait()
    assert isinstance(req, MeasureRequest)
    assert req.cargo_id == "cargo-1"
    assert req.raw["forkliftId"] == "SIM-F01"


def test_cargoId가_없으면_버리고_이유를_남긴다(capsys) -> None:
    """세션을 못 열기 때문이다. 조용히 넘기면 '신호를 보냈는데 아무 일도 안 일어남'이 된다."""
    t = _trigger()
    t._on_message(None, None, Msg({"forkliftId": "SIM-F01", "x": 1.0}))
    assert t._q.empty()
    err = capsys.readouterr().err
    assert "cargoId" in err and "forkliftId" in err


def test_enter는_멱등하다() -> None:
    """serve.py가 연결 실패를 먼저 확인하려고 `__enter__()`를 부른 뒤 `with`로 감싼다.
    두 번 연결하면 구독이 중복돼 같은 요청이 두 번 온다."""
    t = _trigger()
    t._client = object()                 # 이미 연결된 상태를 흉내
    assert t.__enter__() is t            # 재연결 시도 없이 그대로 반환


def test_필드명을_바꿀_수_있다() -> None:
    """시뮬 쪽 규격이 확정 전이라 코드를 안 고치고 맞출 수 있어야 한다."""
    t = MeasureTrigger("broker.invalid", cargo_field="cargo_id")
    t._on_message(None, None, Msg({"cargo_id": "c-9"}))
    assert t._q.get_nowait().cargo_id == "c-9"


def test_깨진_페이로드는_무시한다(capsys) -> None:
    t = _trigger()
    t._on_message(None, None, Msg("이건 JSON이 아니다"))
    assert t._q.empty()
    assert "파싱 실패" in capsys.readouterr().err


# --- 모드 분기 ---

def test_listen은_cargo_id_없는_1회경로로_새지_않는다(monkeypatch, capsys) -> None:
    """2026-07-31 실측 회귀.

    `--listen`은 cargoId를 MQTT로 받으므로 시작 시점엔 `--cargo-id`가 없다. 분기 조건에
    `not args.listen`이 빠져 있어 그것을 "cargo-id 없이 보내는 1회 측정"으로 오인했고,
    **카메라를 열어 한 번 재고 세션 없이 전송해 400**을 받았다. 상시 모드는 시작도 못 했다.

    여기서는 카메라 대신 이미지로 1회 경로를 유도한다 — 그 경로로 샜다면 "이미지를 읽을
    수 없습니다"가 찍힌다. 상시 모드로 갔다면 브로커 연결에서 실패한다.
    """
    from station import serve

    rc = serve.main(["--listen", "--publish", "--image", "없는파일.jpg",
                     "--broker", "broker.invalid", "--broker-port", "1"])
    out = capsys.readouterr()
    assert "이미지를 읽을 수 없습니다" not in out.err, "1회 측정 경로로 샜다"
    assert rc != 0


def test_listen은_publish_없이_거부된다() -> None:
    """측정만 하고 버리는 실수를 막는다."""
    from station import serve

    with pytest.raises(SystemExit):
        serve.main(["--listen"])
