"""측정 결과 MQTT 발행 — 스테이션 측정 JSON → 백엔드 브로커 (S15P11A304-91).

`build_payload`가 낸 측정 JSON(규격 `station-measurement-handoff.md`)을 백엔드가 구독하는
토픽으로 발행한다. 백엔드는 이미 받는 쪽이 완성돼 있다 —
`fast/station/+/measurement` 구독 → `StationMeasurementService` → DB(`station_measurement`)
→ WebSocket(`/topic/stations/measurements`). 비어 있던 **발행 측**만 여기서 채운다.

접속·QoS 규약은 `scripts/dummy-real-f01-location-publisher.py`(검증된 발행 예제)와
동일하게 맞춘다:

- 브로커는 **환경변수**로만(`MQTT_HOST`/`MQTT_PORT`/`MQTT_USERNAME`/`MQTT_PASSWORD`).
  실제 IP·인증정보는 하드코딩하지 않는다 — 백엔드가 GPU서버로 이전됐으므로 배포처의
  값을 주입한다(팀 확인 필요).
- 토픽 `fast/station/{station_id}/measurement`, **QoS 1 · retained false**
  (`communication-protocol.md` §2 인바운드 규약).

⚠️ 규격 정합은 백엔드와 맞춘다. 현재 백엔드 DTO(`StationMeasurementMessage`)에는
`tipping`·`dimensions.total_height_cm`·`box_measurements`가 아직 없다 — 우리는 규격
(handoff v1.0+)대로 전량 발행하고, 백엔드 DTO 확장은 백엔드 담당 몫이다. 미지원 필드는
백엔드가 무시하므로 발행이 깨지지는 않는다.
"""

from __future__ import annotations

import json
import os
import sys

DEFAULT_QOS = 1
DEFAULT_RETAINED = False


def topic_for(payload: dict) -> str:
    """측정 payload → 백엔드 구독 토픽. station_id가 없으면 station-1로 떨어진다.

    백엔드 규약 `fast/station/{station_id}/measurement`(communication-protocol.md §2).
    """
    station_id = payload.get("station_id") or "station-1"
    return f"fast/station/{station_id}/measurement"


def _new_client(client_id: str):
    import paho.mqtt.client as mqtt
    # paho 2.x는 CallbackAPIVersion을 요구한다. 1.x와 모두 동작하도록 분기한다.
    try:
        return mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
                           client_id=client_id)
    except (AttributeError, TypeError):
        return mqtt.Client(client_id=client_id)


class MeasurementPublisher:
    """측정 JSON을 백엔드 토픽으로 발행. 커넥션을 유지해 여러 번 쏠 수 있다."""

    def __init__(
        self,
        host: str | None = None,
        port: int | None = None,
        username: str | None = None,
        password: str | None = None,
        client_id: str | None = None,
        keepalive: int = 30,
    ) -> None:
        self.host = host or os.getenv("MQTT_HOST", "localhost")
        self.port = int(port or os.getenv("MQTT_PORT", "1883"))
        username = username if username is not None else os.getenv("MQTT_USERNAME", "")
        password = password if password is not None else os.getenv("MQTT_PASSWORD", "")
        client_id = client_id or os.getenv("MQTT_CLIENT_ID", "station-measurement")

        self._client = _new_client(client_id)
        if username:
            self._client.username_pw_set(username, password or None)
        self._client.reconnect_delay_set(min_delay=1, max_delay=16)
        self._connected = False

    def connect(self) -> None:
        self._client.connect(self.host, self.port, keepalive=30)
        self._client.loop_start()
        self._connected = True

    def publish(self, payload: dict, qos: int = DEFAULT_QOS,
                retained: bool = DEFAULT_RETAINED) -> bool:
        """측정 JSON 한 건 발행. 토픽은 payload의 station_id로 만든다."""
        import paho.mqtt.client as mqtt
        if not self._connected:
            self.connect()
        topic = topic_for(payload)
        info = self._client.publish(topic, json.dumps(payload, ensure_ascii=False),
                                    qos=qos, retain=retained)
        info.wait_for_publish(timeout=5)
        ok = info.rc == mqtt.MQTT_ERR_SUCCESS
        if not ok:
            print(f"[station-publisher] 발행 실패 rc={info.rc} topic={topic}",
                  file=sys.stderr)
        return ok

    def close(self) -> None:
        if self._connected:
            self._client.loop_stop()
            self._client.disconnect()
            self._connected = False


def publish_once(payload: dict, **kwargs) -> bool:
    """단발 발행 헬퍼 — 연결·발행·종료를 한 번에. serve.py --once 경로용."""
    pub = MeasurementPublisher(**kwargs)
    try:
        pub.connect()
        return pub.publish(payload)
    finally:
        pub.close()
