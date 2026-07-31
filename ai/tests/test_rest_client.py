"""측정 REST 전송 — 요청 변환·세션 수명 테스트 (S15P11A304-91).

**필드 매핑과 단위 변환**이 틀리면 조용히 잘못된 값이 저장된다. **세션을 못 닫으면**
설비가 잠겨 아무도 측정을 못 한다(백엔드에 TTL이 없다). 둘 다 서버 없이 검증한다 —
HTTP 호출은 `_call`을 갈아끼워 가로챈다.
"""

from __future__ import annotations

import pytest

from station import rest_client
from station.rest_client import (SessionNotReleased, StationApiError,
                                 measurement_session, to_request)

FULL = {
    "measurement_id": "station-1-20260731-093748-0001",
    "station_id": "station-1",
    "measured_at": "2026-07-31T09:37:48+09:00",
    "status": "ok",
    "dimensions": {"height_cm": 72.3, "total_height_cm": 84.3, "width_cm": 102.2},
    "tipping": {"level": "safe", "overhang": 0.057},
    "load_balance": {"eccentric": False},
}


def test_필드_7개를_보낸다() -> None:
    r = to_request(FULL, "sess-1")
    assert set(r) == {"sessionId", "measurementId", "status", "cargoHeight",
                      "tippingLevel", "overhangRatio", "measuredAt"}


def test_sessionId를_그대로_싣는다() -> None:
    """2026-07-31 백엔드 변경 — 없으면 400. 활성 세션 조회 방식은 폐기됐다.

    TTL 만료로 세션 A가 풀리고 B가 열린 뒤 도착한 A의 늦은 측정이 B에 오귀속되는
    구멍 때문이다. 요청이 자기 출처 세션을 밝혀야 백엔드가 거른다.
    """
    assert to_request(FULL, "sess-abc")["sessionId"] == "sess-abc"


def test_높이는_cm를_m로_바꾼다() -> None:
    assert to_request(FULL)["cargoHeight"] == 0.723


def test_높이는_화물만_쓴다_총높이가_아니다() -> None:
    """백엔드가 파렛트 0.12m를 따로 더한다 — 총높이를 보내면 두 번 더해진다."""
    r = to_request(FULL)
    assert r["cargoHeight"] == 0.723          # height_cm 72.3
    assert r["cargoHeight"] != 0.843          # total_height_cm 84.3 아님


def test_전복_필드_매핑() -> None:
    r = to_request(FULL)
    assert r["tippingLevel"] == "safe"        # 소문자 그대로(백엔드가 대문자화)
    assert r["overhangRatio"] == 0.057


def test_보내지_않는_필드() -> None:
    """stationId·schemaVersion은 계약에서 빠졌다.

    ⚠️ `sessionId`는 한때 여기 있었으나 2026-07-31에 **필수로 되돌아왔다**
    (TTL 도입 후 늦게 도착한 측정의 오귀속을 막기 위해).
    """
    r = to_request(FULL, "sess-1")
    for k in ("stationId", "schemaVersion", "station_id", "measurement_id"):
        assert k not in r


def test_치수가_없으면_None() -> None:
    """no_detection/unreliable은 dimensions가 null — 임의로 채우지 않는다."""
    r = to_request({"measurement_id": "m1", "status": "no_detection",
                    "dimensions": None, "tipping": None})
    assert r["cargoHeight"] is None
    assert r["tippingLevel"] is None
    assert r["overhangRatio"] is None


def test_dimensions_only는_전복이_없다() -> None:
    r = to_request({"measurement_id": "m2", "status": "dimensions_only",
                    "dimensions": {"height_cm": 30.0},
                    "tipping": {"assessable": False}})
    assert r["cargoHeight"] == 0.3
    assert r["tippingLevel"] is None


# ── 세션 수명 ────────────────────────────────────────────────────────────────

class FakeApi:
    """`_call`을 대신해 호출을 기록한다. `fail_close`로 종료 거부를 흉내 낸다."""

    def __init__(self, fail_close: str | None = None) -> None:
        self.calls: list[tuple[str, str]] = []
        self.fail_close = fail_close

    def __call__(self, method, url, body=None, timeout=None):
        self.calls.append((method, url))
        if method == "POST" and "/sessions" in url:
            return {"sessionId": "sess-1", "cargoId": "cargo-1"}
        if method == "DELETE" and self.fail_close:
            raise StationApiError(409, self.fail_close)
        return {}

    @property
    def methods(self) -> list[str]:
        return [m for m, _ in self.calls]


@pytest.fixture
def api(monkeypatch):
    fake = FakeApi()
    monkeypatch.setattr(rest_client, "_call", fake)
    return fake


def test_정상_종료하면_세션이_닫힌다(api) -> None:
    with measurement_session("cargo-1") as session_id:
        assert session_id == "sess-1"
    assert api.methods == ["POST", "DELETE"]


def test_예외가_나도_세션이_닫힌다(api) -> None:
    """전송이 터져도 설비를 잠근 채 나가면 안 된다."""
    with pytest.raises(RuntimeError, match="측정 실패"):
        with measurement_session("cargo-1"):
            raise RuntimeError("측정 실패")
    assert "DELETE" in api.methods


def test_키보드인터럽트에도_세션이_닫힌다(api) -> None:
    """Ctrl-C·SIGTERM이 같은 경로를 탄다."""
    with pytest.raises(KeyboardInterrupt):
        with measurement_session("cargo-1"):
            raise KeyboardInterrupt
    assert "DELETE" in api.methods


def test_원래_예외를_종료실패가_덮지_않는다(monkeypatch) -> None:
    """측정이 왜 실패했는지가 세션이 왜 안 닫혔는지보다 중요하다."""
    monkeypatch.setattr(rest_client, "_call",
                        FakeApi(fail_close="STATION_MEASUREMENT_NOT_COMPLETED"))
    with pytest.raises(RuntimeError, match="측정 실패"):
        with measurement_session("cargo-1"):
            raise RuntimeError("측정 실패")


def test_닫기_실패는_조용히_넘어가지_않는다(monkeypatch) -> None:
    """측정이 저장되기 전엔 백엔드가 종료를 거부한다 — 잠긴 채 남으므로 알려야 한다."""
    monkeypatch.setattr(rest_client, "_call",
                        FakeApi(fail_close="STATION_MEASUREMENT_NOT_COMPLETED"))
    with pytest.raises(SessionNotReleased) as e:
        with measurement_session("cargo-1"):
            pass
    assert e.value.session_id == "sess-1"


def test_이미_닫힌_세션은_실패가_아니다(monkeypatch) -> None:
    monkeypatch.setattr(rest_client, "_call",
                        FakeApi(fail_close="STATION_SESSION_NOT_ACTIVE"))
    with measurement_session("cargo-1"):
        pass          # 예외 없이 끝나야 한다


def test_세션_열기_실패하면_본문을_실행하지_않는다(monkeypatch) -> None:
    """설비가 점유 중이면 측정을 보내면 안 된다 — 남의 화물에 붙는다."""
    def occupied(method, url, body=None, timeout=None):
        raise StationApiError(409, "STATION_ALREADY_OCCUPIED")

    monkeypatch.setattr(rest_client, "_call", occupied)
    entered = False
    with pytest.raises(StationApiError):
        with measurement_session("cargo-1"):
            entered = True
    assert not entered


def test_세션을_측정_앞에_연다(monkeypatch, capsys) -> None:
    """순서가 바뀌면 이 테스트가 깨진다 (2026-07-31 결정).

    설비가 점유 중이면 **측정을 시작하기도 전에** 튕겨야 한다 — 수 초짜리 촬영·추론을
    다 하고 나서 버리면 헛수고다. 존재하지 않는 이미지를 주고, 그 오류 메시지가
    **안 나오는 것**으로 측정에 진입조차 안 했음을 확인한다.
    """
    from station import serve

    def occupied(method, url, body=None, timeout=None):
        raise StationApiError(409, "STATION_ALREADY_OCCUPIED")

    monkeypatch.setattr(rest_client, "_call", occupied)
    rc = serve.main(["--once", "--publish", "--cargo-id", "cargo-1",
                     "--image", "존재하지-않는-파일.jpg"])
    err = capsys.readouterr().err
    assert rc == 1
    assert "세션을 열 수 없어" in err
    assert "이미지를 읽을 수 없습니다" not in err
