#!/usr/bin/env python3
"""SIM-F01 위치 더미 MQTT 발행기 (통합 테스트용).

실제 ROS2 Orin 차량이나 Isaac Sim 없이 다음 전체 경로를 검증하기 위한 스크립트다.

    이 스크립트
      → forklift/SIM-F01/location  (MQTT)
      → Spring Boot MqttMessageRouter.routeLocation
      → ForkliftLocationService (검증 · 최신 위치 갱신)
      → STOMP /topic/vehicles/location
      → Next.js 관제 화면 → 미니맵 마커 이동

발행 규격(prompt73 확정):

    topic     forklift/SIM-F01/location
    QoS       1
    retained  false          ← 위치는 절대 retain 하지 않는다
    payload   {"vehicleId","position":{"x","y","frameId"},"heading","messageAt"}

    heading   degree (0 이상 360 미만).  0°=+X, 90°=+Y
    messageAt ISO-8601 OffsetDateTime (+09:00)

주의
  * 제어 명령 토픽(forklift/+/command)에는 절대 발행하지 않는다.
  * retained=false 를 유지한다. true 로 두면 재접속한 구독자가 오래된 좌표를 받아
    차량이 과거 위치에 있는 것처럼 보인다.
  * SIM-F01 이 차량 마스터에 등록돼 있어야 백엔드가 메시지를 처리한다.
    등록돼 있지 않으면 백엔드가 "vehicle not registered" 경고 후 폐기한다.
  * **Isaac Sim twin_bridge.py 와 동시에 띄우지 않는다.** 둘 다 SIM-F01 로 위치를 발행하므로
    서로의 좌표를 덮어써 마커가 두 지점 사이를 튄다. 이 스크립트는 Isaac 이 없을 때의 대역이다.

사전 준비
    pip install paho-mqtt

실행
    python scripts/dummy-sim-f01-location-publisher.py

환경변수로 조정
    MQTT_HOST=localhost \
    MQTT_PORT=1883 \
    MQTT_INTERVAL_SECONDS=0.5 \
    VEHICLE_ID=SIM-F01 \
    python scripts/dummy-sim-f01-location-publisher.py

종료
    Ctrl+C  (정상 disconnect 후 종료)
"""

from __future__ import annotations

import json
import os
import signal
import sys
import time
from datetime import datetime, timedelta, timezone

try:
    import paho.mqtt.client as mqtt
except ImportError:
    print("[dummy-publisher] paho-mqtt 가 설치돼 있지 않습니다.", file=sys.stderr)
    print("[dummy-publisher]   pip install paho-mqtt", file=sys.stderr)
    sys.exit(1)


# ── 설정 (환경변수로 덮어쓸 수 있다. 실제 IP·인증정보는 하드코딩하지 않는다) ──────
MQTT_HOST = os.getenv("MQTT_HOST", "localhost")
MQTT_PORT = int(os.getenv("MQTT_PORT", "1883"))
MQTT_USERNAME = os.getenv("MQTT_USERNAME", "")
MQTT_PASSWORD = os.getenv("MQTT_PASSWORD", "")
VEHICLE_ID = os.getenv("VEHICLE_ID", "SIM-F01")
INTERVAL_SECONDS = float(os.getenv("MQTT_INTERVAL_SECONDS", "0.5"))
CLIENT_ID = os.getenv("MQTT_CLIENT_ID", f"dummy-location-{VEHICLE_ID}")

TOPIC = f"forklift/{VEHICLE_ID}/location"
QOS = 1
RETAINED = False          # ★ 위치는 retain 하지 않는다
FRAME_ID = "map"
KST = timezone(timedelta(hours=9))

# 관제 화면에서 이동이 눈에 보이도록 사각형 경로를 반복한다.
# (x, y, heading) — heading 은 degree, 0°=+X / 90°=+Y
WAYPOINTS = [
    (0.0, 0.0, 0.0),
    (1.0, 0.0, 0.0),
    (2.0, 0.0, 0.0),
    (2.0, 1.0, 90.0),
    (2.0, 2.0, 90.0),
    (1.0, 2.0, 180.0),
    (0.0, 2.0, 180.0),
    (0.0, 1.0, 270.0),
]

_running = True


def _handle_sigint(signum, frame):  # noqa: ARG001
    global _running
    _running = False
    print("\n[dummy-publisher] 종료 신호 수신 — 연결을 정리합니다.")


def now_iso() -> str:
    """현재 시각을 ISO-8601 +09:00 문자열로. 백엔드가 OffsetDateTime 으로 파싱한다."""
    return datetime.now(KST).isoformat(timespec="milliseconds")


def build_payload(x: float, y: float, heading: float) -> dict:
    """prompt73 확정 규격 그대로. 필드명·중첩 구조를 바꾸지 않는다."""
    return {
        "vehicleId": VEHICLE_ID,
        "position": {
            "x": x,
            "y": y,
            "frameId": FRAME_ID,
        },
        "heading": heading,
        "messageAt": now_iso(),
    }


def on_connect(client, userdata, flags, rc, properties=None):  # noqa: ARG001
    if rc == 0:
        print(f"[dummy-publisher] 연결 성공: {MQTT_HOST}:{MQTT_PORT}")
    else:
        # rc != 0 이면 브로커가 연결을 거부한 것이다(인증 실패 등).
        print(f"[dummy-publisher] 연결 거부됨 rc={rc}", file=sys.stderr)


def on_disconnect(client, userdata, rc, properties=None):  # noqa: ARG001
    if rc != 0:
        print(f"[dummy-publisher] 예기치 않은 연결 종료 rc={rc} — 자동 재연결을 시도합니다.",
              file=sys.stderr)


def main() -> int:
    signal.signal(signal.SIGINT, _handle_sigint)
    signal.signal(signal.SIGTERM, _handle_sigint)

    # paho 2.x 는 CallbackAPIVersion 을 요구한다. 1.x 와 모두 동작하도록 분기한다.
    try:
        client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=CLIENT_ID,
        )
    except (AttributeError, TypeError):
        client = mqtt.Client(client_id=CLIENT_ID)

    if MQTT_USERNAME:
        client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD or None)

    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.reconnect_delay_set(min_delay=1, max_delay=16)

    print("[dummy-publisher] 설정")
    print(f"  broker   : {MQTT_HOST}:{MQTT_PORT}")
    print(f"  clientId : {CLIENT_ID}")
    print(f"  topic    : {TOPIC}")
    print(f"  QoS      : {QOS}   retained: {RETAINED}")
    print(f"  interval : {INTERVAL_SECONDS}s")
    print(f"  waypoints: {len(WAYPOINTS)}개 (사각형 경로 반복)")
    print()

    try:
        client.connect(MQTT_HOST, MQTT_PORT, keepalive=30)
    except OSError as e:
        print(f"[dummy-publisher] 브로커에 연결할 수 없습니다: {e}", file=sys.stderr)
        print("[dummy-publisher] Mosquitto 가 실행 중인지 확인하세요:", file=sys.stderr)
        print("[dummy-publisher]   cd infra/mqtt && docker compose up -d", file=sys.stderr)
        return 1

    client.loop_start()

    index = 0
    published = 0
    try:
        while _running:
            x, y, heading = WAYPOINTS[index % len(WAYPOINTS)]
            payload = build_payload(x, y, heading)

            info = client.publish(TOPIC, json.dumps(payload), qos=QOS, retain=RETAINED)
            if info.rc != mqtt.MQTT_ERR_SUCCESS:
                print(f"[dummy-publisher] 발행 실패 rc={info.rc}", file=sys.stderr)
            else:
                published += 1
                print(f"[{published:04d}] x={x:5.2f} y={y:5.2f} heading={heading:6.1f}° "
                      f"messageAt={payload['messageAt']}")

            index += 1
            # Ctrl+C 반응이 느려지지 않도록 잘게 나눠 잔다.
            slept = 0.0
            while _running and slept < INTERVAL_SECONDS:
                step = min(0.1, INTERVAL_SECONDS - slept)
                time.sleep(step)
                slept += step
    finally:
        client.loop_stop()
        client.disconnect()
        print(f"[dummy-publisher] 정상 종료. 총 {published}건 발행.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
