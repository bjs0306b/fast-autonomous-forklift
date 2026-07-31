"""측정 REST 전송 — 요청 변환 테스트 (S15P11A304-91).

실제 백엔드 왕복은 서버 주소 확보 후. 여기선 브로커·서버 없이 검증되는
**필드 매핑과 단위 변환**만 본다 — 여기가 틀리면 조용히 잘못된 값이 저장된다.
"""

from __future__ import annotations

import pytest

from station.rest_client import (
    STALE_ERROR_CODES,
    StationApiError,
    is_stale_rejection,
    to_request,
)

SESSION = "session-uuid-A"

FULL = {
    "measurement_id": "station-1-20260731-093748-0001",
    "station_id": "station-1",
    "measured_at": "2026-07-31T09:37:48+09:00",
    "status": "ok",
    "dimensions": {"height_cm": 72.3, "total_height_cm": 84.3, "width_cm": 102.2},
    "tipping": {"level": "safe", "overhang": 0.057},
    "load_balance": {"eccentric": False},
}


def test_필드_7개를_보낸다_sessionId_포함() -> None:
    r = to_request(FULL, SESSION)
    assert set(r) == {"sessionId", "measurementId", "status", "cargoHeight",
                      "tippingLevel", "overhangRatio", "measuredAt"}


def test_높이는_cm를_m로_바꾼다() -> None:
    assert to_request(FULL, SESSION)["cargoHeight"] == 0.723


def test_높이는_화물만_쓴다_총높이가_아니다() -> None:
    """백엔드가 파렛트 0.12m를 따로 더한다 — 총높이를 보내면 두 번 더해진다."""
    r = to_request(FULL, SESSION)
    assert r["cargoHeight"] == 0.723          # height_cm 72.3
    assert r["cargoHeight"] != 0.843          # total_height_cm 84.3 아님


def test_전복_필드_매핑() -> None:
    r = to_request(FULL, SESSION)
    assert r["tippingLevel"] == "safe"        # 소문자 그대로(백엔드가 대문자화)
    assert r["overhangRatio"] == 0.057


def test_보내지_않는_필드() -> None:
    """sessionId·stationId·schemaVersion은 계약에서 빠졌다."""
    r = to_request(FULL, SESSION)
    for k in ("sessionId", "stationId", "schemaVersion", "session_id", "station_id"):
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


# ── sessionId 계약 (2026-07-31) ──────────────────────────────────────────────


def test_sessionId는_인자값을_그대로_싣는다() -> None:
    """전역/환경변수에서 다시 읽지 않는다 — 측정 시점의 세션이 그대로 가야 한다."""
    assert to_request(FULL, SESSION)["sessionId"] == SESSION


def test_다른_세션으로_만들면_그_값이_실린다() -> None:
    """재전송 시 호출자가 원래 sessionId 를 넘기면 그대로 유지된다."""
    assert to_request(FULL, "session-uuid-B")["sessionId"] == "session-uuid-B"


@pytest.mark.parametrize("bad", [None, "", "   "])
def test_sessionId가_없으면_요청을_만들지_않는다(bad) -> None:
    """서버에서 400 을 받기 전에 여기서 막는다."""
    with pytest.raises(ValueError):
        to_request(FULL, bad)


def test_stale_판정은_세션_오류만_해당한다() -> None:
    assert is_stale_rejection(StationApiError(409, '{"code":"STATION_SESSION_MISMATCH"}'))
    assert is_stale_rejection(StationApiError(409, '{"code":"STATION_SESSION_NOT_ACTIVE"}'))


def test_중복과_서버오류는_stale_이_아니다() -> None:
    """중복은 이미 저장된 것이고, 5xx·네트워크 오류는 재시도 대상이다."""
    assert not is_stale_rejection(StationApiError(409, '{"code":"STATION_MEASUREMENT_ID_DUPLICATED"}'))
    assert not is_stale_rejection(StationApiError(500, "boom"))
    assert not is_stale_rejection(StationApiError(0, "연결 실패"))


def test_stale_코드_목록() -> None:
    assert set(STALE_ERROR_CODES) == {"STATION_SESSION_MISMATCH", "STATION_SESSION_NOT_ACTIVE"}
