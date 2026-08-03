"""측정 요청을 손으로 발행한다 — 리허설·장애 대비용.

    python -m station.send_trigger --cargo-id CARGO-001

정상 흐름에서는 **백엔드가 발행한다**(운반 작업 생성 → 측정 위치 MOVE →
ROS2가 도착을 SUCCESS로 회신 → 백엔드가 발행). 이 도구는 그 앞단 없이
스테이션만 시험하거나, 시연 중 백엔드 흐름이 막혔을 때 쓴다.

⚠️ **이걸로 쏜 신호는 백엔드가 모른다.** 백엔드는 자기가 만든 운반 작업과
세션을 연결하는데, 여기서 쏜 `cargoId`에 대응하는 작업이 없으면 스테이션이
연 세션은 **독립 세션**으로 남는다(백엔드가 그렇게 허용한다 —
`openSession()`이 대기 작업이 없으면 그냥 통과시킨다). 측정·저장은 되지만
"운반 작업이 측정 단계를 지났다"는 상태 전이는 일어나지 않는다.

그래서 **전 구간 시연에는 백엔드 발행을 쓰고**, 이 도구는 스테이션 쪽만
확인할 때 쓴다.

⚠️ 없는 `cargoId`를 주면 백엔드가 그 이름으로 **화물을 새로 만든다**(의도된
설계). 오타를 치면 유령 화물이 생기고 측정이 거기 붙으므로, 실제 화물 ID를
쓰거나 눈에 띄는 이름(`RIG-TEST-...`)을 쓴다.
"""

from __future__ import annotations

import argparse
import json
import ssl
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from station.envfile import load_dotenv, mqtt_settings  # noqa: E402

DEFAULT_TOPIC = "fast/station/measure_request"


def main(argv: list[str] | None = None) -> int:
    load_dotenv()
    cfg = mqtt_settings()

    ap = argparse.ArgumentParser(description="측정 요청 수동 발행 (리허설용)")
    ap.add_argument("--cargo-id", required=True,
                    help="측정할 화물 ID. 백엔드가 아는 값을 쓴다 — "
                         "없는 값이면 그 이름으로 화물이 새로 생긴다")
    ap.add_argument("--topic", default=DEFAULT_TOPIC)
    ap.add_argument("--broker", default=cfg["broker"])
    ap.add_argument("--port", type=int, default=cfg["port"])
    ap.add_argument("--ca", default=cfg["ca"], help="빈 값이면 평문 접속")
    a = ap.parse_args(argv)

    import paho.mqtt.client as mqtt

    payload = json.dumps({"cargoId": a.cargo_id})
    client = mqtt.Client(client_id="fast-station-trigger")

    # TLS는 connect() **전에** 걸어야 적용된다. 인증서가 없으면 조용히 평문으로
    # 떨어지지 않고 죽는다 — 암호화된 줄 알고 평문으로 붙는 게 더 나쁘다.
    if a.ca:
        ca = Path(a.ca).expanduser().resolve()
        if not ca.is_file():
            print(f"❌ CA 인증서가 없다: {ca}", file=sys.stderr)
            return 1
        client.tls_set(ca_certs=str(ca), cert_reqs=ssl.CERT_REQUIRED)
        client.tls_insecure_set(False)
    if cfg["user"]:
        client.username_pw_set(cfg["user"], cfg["password"])

    scheme = "TLS" if a.ca else "평문"
    auth = "인증" if cfg["user"] else "익명"
    print(f"[trigger] {a.broker}:{a.port} ({scheme}·{auth}) → {a.topic}")

    try:
        client.connect(a.broker, a.port, 30)
    except Exception as e:
        print(f"❌ 브로커 연결 실패 — {type(e).__name__}: {e}", file=sys.stderr)
        print("  확인: ① CA 경로(--ca) ② STATION_MQTT_USER/PASSWORD "
              "③ 포트(TLS 8883 / 평문 1883) ④ 방화벽", file=sys.stderr)
        return 1

    client.loop_start()
    # QoS 1 — 계약이 정한 값이다. retained 는 false(새 구독자가 낡은 요청을
    # 받아 엉뚱한 시점에 측정하면 안 된다).
    info = client.publish(a.topic, payload, qos=1, retain=False)
    info.wait_for_publish(timeout=10)
    client.loop_stop()
    client.disconnect()

    if not info.is_published():
        print("❌ 발행 확인(PUBACK) 을 못 받았다", file=sys.stderr)
        return 1
    print(f"✅ 발행됨 {payload}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
