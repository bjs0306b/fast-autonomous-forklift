# 차량 카메라 영상 규격 (C팀용)

관제 화면에서 실물 지게차 시점을 보기 위한 규격. 선택 기능이고, 좌표 발행
(`orin-pose-spec.md`)이 먼저다.

---

## 토픽

```
fast/v1/vehicle/fk01/camera        JPEG 바이트 (base64 아님)   QoS 0
fast/v1/vehicle/fk01/camera/info   JSON 메타 (선택)            QoS 0
```

`retain` 은 쓰지 않는다. 지난 프레임이 되살아나면 관제가 옛 화면을 보게 된다.

## 권장값

| 항목 | 값 | 이유 |
|---|---|---|
| 해상도 | **320 x 240** | MQTT 는 영상용이 아니다. 크면 브로커가 버겁다 |
| 프레임률 | **5 fps** | 관제 확인용으론 충분 |
| JPEG 품질 | 60~70 | 장당 15~30 KB |
| 대역폭 | 약 150 KB/s | 이 정도면 무리 없다 |

더 크게·부드럽게 필요하면 MQTT 말고 **별도 스트리밍**(RTSP → MediaMTX)을
쓰는 게 맞다. 시뮬 화면은 그 방식으로 내보내고 있다.

## 페이로드

**`camera`** — JPEG 원본 바이트를 그대로 싣는다. MQTT 는 바이너리를 지원하니
base64 로 감싸지 않는다 (감싸면 33% 커진다).

**`camera/info`** — 바뀔 때만, 또는 1초에 한 번.

```json
{ "vehicleId": "fk01", "ts": 1786100000000,
  "w": 320, "h": 240, "fps": 5, "format": "jpeg" }
```

## 발행 예제 (Python)

```python
import cv2, ssl, time
import paho.mqtt.client as mqtt

c = mqtt.Client(client_id="fk01-cam")
c.username_pw_set("아이디", "비번")
c.tls_set(ca_certs="fast-mqtt-ca.crt")
c.tls_insecure_set(True)           # 인증서 CN 이 IP 라서
c.connect("i15a304.p.ssafy.io", 8883, 30)
c.loop_start()

cap = cv2.VideoCapture(0)
TOPIC = "fast/v1/vehicle/fk01/camera"
while True:
    ok, frame = cap.read()
    if not ok:
        time.sleep(0.1); continue
    frame = cv2.resize(frame, (320, 240))
    ok, buf = cv2.imencode(".jpg", frame,
                           [cv2.IMWRITE_JPEG_QUALITY, 65])
    if ok:
        c.publish(TOPIC, buf.tobytes(), qos=0, retain=False)
    time.sleep(0.2)                # 5 fps
```

ROS2 노드로 만든다면 `/camera/image_raw` 를 구독해
`cv_bridge` 로 변환한 뒤 같은 방식으로 발행하면 된다.

## 확인

F 팀에서 이걸로 봅니다.

```bash
source nav2/config/mqtt.env
python3 nav2/scripts/view_camera.py fk01
```

창이 뜨고 영상이 보이면 성공. 수신 통계(fps, KB/s)도 5초마다 찍힌다.

## 주의

- **부하**: 브로커는 팀 전체가 같이 쓴다. 해상도·fps 를 올리기 전에 알려달라
- **전송 실패 시**: QoS 0 이라 유실돼도 재전송하지 않는다. 그게 맞다 (옛 프레임은 쓸모없다)
- **필수 아님**: 좌표·상태 발행이 먼저다. 카메라는 여유가 생기면 붙이면 된다
