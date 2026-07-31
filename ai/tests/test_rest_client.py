"""측정 REST 전송 — 요청 변환 테스트 (S15P11A304-91).

실제 백엔드 왕복은 서버 주소 확보 후. 여기선 브로커·서버 없이 검증되는
**필드 매핑과 단위 변환**만 본다 — 여기가 틀리면 조용히 잘못된 값이 저장된다.
"""

from __future__ import annotations

from station.rest_client import to_request

FULL = {
    "measurement_id": "station-1-20260731-093748-0001",
    "station_id": "station-1",
    "measured_at": "2026-07-31T09:37:48+09:00",
    "status": "ok",
    "dimensions": {"height_cm": 72.3, "total_height_cm": 84.3, "width_cm": 102.2},
    "tipping": {"level": "safe", "overhang": 0.057},
    "load_balance": {"eccentric": False},
}


def test_필드_6개만_보낸다() -> None:
    r = to_request(FULL)
    assert set(r) == {"measurementId", "status", "cargoHeight",
                      "tippingLevel", "overhangRatio", "measuredAt"}


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
    """sessionId·stationId·schemaVersion은 계약에서 빠졌다."""
    r = to_request(FULL)
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
