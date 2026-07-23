"""TF-Nova 프레임 파싱·집계 테스트 (FR-103-1). 하드웨어 불필요."""

from __future__ import annotations

import pytest

from perception.tfnova import (
    INVALID_DISTANCE,
    Frame,
    FrameParser,
    MeasurementUnreliable,
    aggregate,
)


def make_frame(distance: int, peak: int = 500, temp: int = 46, confidence: int = 100) -> bytes:
    """체크섬까지 맞춘 정상 프레임 바이트를 만든다."""
    body = bytes([
        0x59, 0x59,
        distance & 0xFF, (distance >> 8) & 0xFF,
        peak & 0xFF, (peak >> 8) & 0xFF,
        temp, confidence,
    ])
    return body + bytes([sum(body) & 0xFF])


# --- 파싱 ---

def test_정상_프레임을_해석한다() -> None:
    frames = FrameParser().feed(make_frame(150, peak=480, temp=46, confidence=97))

    assert len(frames) == 1
    f = frames[0]
    assert (f.distance_cm, f.peak, f.temperature_c, f.confidence) == (150, 480, 46, 97)
    assert f.valid


def test_실측_캡처_프레임과_일치한다() -> None:
    """2026-07-20 COM3에서 받은 실제 바이트. 회귀 방지용."""
    raw = bytes.fromhex("59 59 13 00 32 02 2e 64 8b".replace(" ", ""))

    f = FrameParser().feed(raw)[0]
    assert (f.distance_cm, f.peak, f.temperature_c, f.confidence) == (19, 562, 46, 100)


def test_프레임이_조각나서_와도_이어_붙인다() -> None:
    """시리얼 read()는 프레임 경계와 무관하게 자른다."""
    parser = FrameParser()
    data = make_frame(100) + make_frame(101)

    collected = []
    for i in range(0, len(data), 4):          # 4바이트씩 잘라서 공급
        collected += parser.feed(data[i:i + 4])

    assert [f.distance_cm for f in collected] == [100, 101]


def test_쓰레기_바이트_속에서_헤더를_찾는다() -> None:
    noise = b"\x00\x80\xff\x59"               # 끝의 0x59는 가짜 헤더 조각
    frames = FrameParser().feed(noise + make_frame(77))

    assert [f.distance_cm for f in frames] == [77]


def test_체크섬이_틀리면_버리고_다음_프레임을_살린다() -> None:
    parser = FrameParser()
    bad = bytearray(make_frame(100))
    bad[8] ^= 0xFF                            # 체크섬 파괴

    frames = parser.feed(bytes(bad) + make_frame(200))

    assert [f.distance_cm for f in frames] == [200]
    assert parser.checksum_failures == 1


def test_측정_실패_프레임은_invalid로_표시된다() -> None:
    frames = FrameParser().feed(make_frame(INVALID_DISTANCE))

    assert len(frames) == 1
    assert not frames[0].valid


def test_거리_0은_대상_없음이므로_invalid다() -> None:
    """매뉴얼: 대상이 없거나 신호가 약하면 Dist=0. 실측에서 신뢰도 100과 함께
    거리 0이 들어오는 것을 확인했다 — 신뢰도만으로는 못 거른다."""
    frames = FrameParser().feed(make_frame(0, confidence=100))

    assert not frames[0].valid

    with pytest.raises(MeasurementUnreliable):
        aggregate([frames[0]] * 50, min_frames=10)


# --- 집계 ---

def _frames(*distances: int, confidence: int = 100) -> list[Frame]:
    return [Frame(d, peak=500, temperature_c=46, confidence=confidence) for d in distances]


def test_중앙값이라_순간_튐에_끌려가지_않는다() -> None:
    """사람이 빔 앞을 지나가면 몇 프레임이 크게 튄다. 평균이면 오염된다."""
    frames = _frames(*([150] * 20), 20, 21)   # 20프레임 정상 + 2프레임 튐

    m = aggregate(frames, min_frames=10)
    assert m.distance_cm == 150


def test_측정_실패와_저신뢰_프레임은_제외한다() -> None:
    frames = (
        _frames(*([150] * 12))
        + _frames(INVALID_DISTANCE)
        + _frames(999, confidence=30)
    )

    m = aggregate(frames, min_confidence=90, min_frames=10)
    assert m.distance_cm == 150
    assert m.frames_used == 12
    assert m.frames_seen == 14


def test_유효_프레임이_모자라면_거부한다() -> None:
    with pytest.raises(MeasurementUnreliable, match="유효 프레임"):
        aggregate(_frames(150, 151), min_frames=10)


def test_캘리브레이션_스케일을_곱한다() -> None:
    """센서가 거리에 비례해 과소하게 읽으면 scale로 보정한다 (원값×scale)."""
    m = aggregate(_frames(*([100] * 15)), min_frames=10, scale=1.5)

    assert m.distance_cm == 150.0


def test_표준편차로_안정성을_보고한다() -> None:
    m = aggregate(_frames(*([150] * 10), *([152] * 10)), min_frames=10)

    assert 0.9 < m.std_cm < 1.1               # pstdev of half/half ±1
