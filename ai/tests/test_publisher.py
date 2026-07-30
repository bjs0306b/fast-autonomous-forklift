"""측정 MQTT 발행 — 토픽 생성·페이로드 정합 테스트 (S15P11A304-91).

실제 브로커 왕복은 배포 브로커(GPU서버) 접속값 확보 후. 여기선 브로커 없이
검증 가능한 순수 로직(토픽 규약)만 본다.
"""

from __future__ import annotations

from station.publisher import topic_for


def test_토픽은_station_id로_만든다() -> None:
    p = {"station_id": "station-1", "status": "ok"}
    assert topic_for(p) == "fast/station/station-1/measurement"


def test_다른_스테이션_id() -> None:
    assert topic_for({"station_id": "station-7"}) == "fast/station/station-7/measurement"


def test_station_id_없으면_기본값() -> None:
    # 백엔드 와일드카드 fast/station/+/measurement에 걸리려면 세그먼트가 비면 안 된다.
    assert topic_for({}) == "fast/station/station-1/measurement"
    assert topic_for({"station_id": None}) == "fast/station/station-1/measurement"
