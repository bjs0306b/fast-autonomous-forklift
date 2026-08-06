# 오린카 좌표 전송 명세 (C팀용)

세트장이 완성되어 좌표계가 확정됐다. 이 문서 하나만 보고 구현하면 된다.
토픽 전체 규격은 [vehicle-mqtt-spec.md](vehicle-mqtt-spec.md),
관제(백엔드) 쪽 규격은 [backend-mqtt-guide.md](backend-mqtt-guide.md) 를 본다.
**이 문서와 백엔드 문서의 필드·상태값은 서로 맞춰져 있다.** 한쪽만 고치지 말 것.

---

## 0. 세 줄 요약

1. 브로커 `i15a304.p.ssafy.io:8883` (TLS, 아이디/비번 필요)
2. 토픽 `fast/v1/vehicle/fk01/telemetry` 에 **10 Hz** 로 발행 (+ `event`, `arrived` — 6장)
3. 좌표는 **내가 잰 실물 m × 10** (MQTT 는 시뮬 좌표계). 원점은 창고 왼쪽 아래

---

## 1. 브로커 접속

| 항목 | 값 |
|---|---|
| 호스트 | `i15a304.p.ssafy.io` (3.38.178.143) |
| 포트 | **8883** — TLS 전용. 1883 은 닫혀 있다 |
| 인증 | 아이디/비번 (F 팀에 문의) |
| 인증서 | 자체 서명 CA `FAST-MQTT-CA`. **CN 이 호스트명이 아니라 IP** 라서 시스템 CA 로는 검증이 실패한다 |
| QoS | telemetry 는 0 (최신값이 중요하지 재전송은 무의미) |

인증서 검증은 둘 중 하나로 한다.

- **권장** — F 팀이 뽑아 둔 CA 파일(`fast-mqtt-ca.crt`)을 받아 지정한다
- **임시** — 검증을 생략한다 (개발 중에만. 도청·위장에 취약)

```python
import ssl, json, time
import paho.mqtt.client as mqtt

c = mqtt.Client(client_id="fk01")
c.username_pw_set("아이디", "비번")

c.tls_set(ca_certs="/path/to/fast-mqtt-ca.crt")   # 권장
# c.tls_set(cert_reqs=ssl.CERT_NONE)              # 임시 (검증 생략)
# c.tls_insecure_set(True)

c.connect("i15a304.p.ssafy.io", 8883, keepalive=30)
c.loop_start()
```

---

## 2. 좌표계 ← 가장 중요

**MQTT 위의 모든 좌표는 시뮬레이션 좌표계다.** 창고는 20 × 30 이고,
실물 세트장은 그 1/10 인 2 × 3 m 다. 변환은 **오린카가 한다.**

```
    보낼 때   내가 잰 실물 m   × 10  →  MQTT
    받을 때   MQTT 좌표        ÷ 10  →  내가 갈 실물 m
```

예) 세트장에서 (1.55, 0.40) m 에 있으면 → `{"x": 15.5, "y": 4.0}` 로 발행.
관제가 `{"x": 17.0, "y": 5.0}` 을 주면 → 실제로는 (1.70, 0.50) m 로 간다.

```
        y
        ▲
   30.0 │  ┌─────────────────┐      MQTT 좌표계 (시뮬)
        │  │                 │      실물 세트장은 이것의 1/10
        │  │   창고           │      → 2.0 x 3.0 m
        │  └─────────────────┘
      0 └────────────────────▶ x
        0                  20.0
```

| 항목 | 값 |
|---|---|
| 원점 (0, 0) | 창고 **왼쪽 아래 모서리** (맵 이미지의 좌하단) |
| x 축 | 오른쪽이 + , 범위 **0 ~ 20.0** (실물 0 ~ 2.0 m) |
| y 축 | 위쪽이 + , 범위 **0 ~ 30.0** (실물 0 ~ 3.0 m) |
| yaw | +x 가 0, **반시계가 +**, **라디안** (−π ~ +π). **변환하지 않는다** |
| 기준점 | 차체의 **base_link** — 뒷바퀴 축 중심 |
| forkHeight | 이것도 **×10** 해서 보낸다 (실물 0.02 m → `0.2`) |

`yaw` 는 각도라 축척과 무관하다. 그대로 보낸다.

### 주요 지점 (MQTT 좌표 / 실물 m)

| 지점 | MQTT x, y | 실물 m |
|---|---|---|
| 입고 바이 | 17.0, 5.0 | 1.70, 0.50 |
| A랙 접근점 | 5.0, 9.3 ~ 23.8 | 0.50, 0.93 ~ 2.38 |
| A랙 적재점 | 0.95, 9.3 ~ 23.8 | 0.095, 0.93 ~ 2.38 |
| B랙 접근점 | 14.5, 9.3 ~ 23.8 | 1.45, 0.93 ~ 2.38 |
| B랙 적재점 | 10.2, 9.3 ~ 23.8 | 1.02, 0.93 ~ 2.38 |
| 순환로 | x = 5.0 / 15.5 | 0.50 / 1.55 |

### 차량 제원 (실물)

| 항목 | 실물 | 시뮬(×10) |
|---|---|---|
| 차체 | 0.380 × 0.190 m | 3.8 × 1.9 |
| 축거 | 0.140 m | 1.4 |
| 최대 조향 | 60° | 60° |
| 최소 회전반경 | 0.081 m | 0.81 |
| 통로 폭 | 0.820 m | 8.2 |

---

## 3. 발행 — `fast/v1/vehicle/fk01/telemetry`

**10 Hz**, QoS 0.

```json
{
  "vehicleId": "fk01",
  "ts": 1785936000123,
  "pose":     {"x": 17.0, "y": 5.0, "yaw": 0.0},
  "velocity": {"linear": 1.2, "angular": 0.05},
  "forkHeight": 0.2,
  "loaded": false,
  "cargoId": null,
  "cargo": null,
  "state": "IDLE",
  "taskId": null,
  "battery": 87.5
}
```

| 필드 | 형 | 설명 |
|---|---|---|
| `vehicleId` | string | 고정 `"fk01"` |
| `ts` | int | 유닉스 시각 **밀리초** |
| `pose.x` `pose.y` | float | **시뮬 좌표** = 실물 m × 10. 소수점 2자리면 실물 mm 정밀도 |
| `pose.yaw` | float | **라디안**, −π ~ +π |
| `velocity.linear` | float | m/s **× 10**. 후진은 음수 |
| `velocity.angular` | float | rad/s. 반시계가 + |
| `forkHeight` | float | 실물 m **× 10**. 바닥이 0 |
| `loaded` | bool | 화물을 들고 있는가 |
| `cargoId` | string \| null | 없으면 null |
| `cargo` | object \| null | 싣고 있는 화물의 크기. 미소지 시 `null`. `{"id","w","d","h"}` — 크기도 **×10** |
| `state` | string | 아래 표 |
| `taskId` | string \| null | 수행 중인 task 의 id. 없으면 null |
| `battery` | float | 0~100 |

### `state` 값

| 값 | 뜻 | 언제 |
|---|---|---|
| `IDLE` | 정지, 명령 대기 | |
| `MOVING` | 주행 중 | |
| **`LOADING`** | **화물을 받는 절차 중** | 입고 바이에 정렬·진입·들어올리기까지 전 구간 |
| **`UNLOADING`** | **화물을 내리는 절차 중** | 랙 정렬·진입·내려놓기·후진까지 전 구간 |
| `LIFTING` / `LOWERING` | 포크만 상승 / 하강 | 위 절차 밖에서 포크만 움직일 때 |
| `HOLDING` | 관제가 멈추라고 해서 멈춤 | `control` 로 HOLD 수신 |
| `ESTOPPED` | 비상정지 | `control` 로 ESTOP 수신 |
| `ERROR` | 고장 | |

`LOADING` / `UNLOADING` 은 **포크가 안 움직이는 구간까지 포함**합니다. 관제
화면이 "적재 중 / 하역 중" 을 표시하는 데 씁니다. 시뮬 차량도 같은 값을
쓰므로, 실물이 이 값을 보내면 관제는 시뮬·실물을 구분 없이 다룰 수 있습니다.

pose 를 아직 못 구하면 (AMCL 미수렴 등) **그 주기는 발행하지 않는다.**
`null` 이나 `0,0` 을 보내면 관제 화면에서 차량이 원점으로 순간이동한다.

---

## 4. 최소 구현 예제

```python
import json, math, ssl, time
import paho.mqtt.client as mqtt

BROKER, PORT = "i15a304.p.ssafy.io", 8883
TOPIC = "fast/v1/vehicle/fk01/telemetry"

c = mqtt.Client(client_id="fk01")
c.username_pw_set("아이디", "비번")
c.tls_set(cert_reqs=ssl.CERT_NONE)      # CA 파일을 받으면 ca_certs= 로 바꿀 것
c.tls_insecure_set(True)
c.connect(BROKER, PORT, keepalive=30)
c.loop_start()

while True:
    x, y, yaw = read_pose()             # AMCL /amcl_pose 등에서 (실물 m, rad)
    if x is None:
        time.sleep(0.1)                 # 위치를 모르면 보내지 않는다
        continue

    K = 10.0                            # 실물 m -> MQTT(시뮬) 좌표
    c.publish(TOPIC, json.dumps({
        "vehicleId": "fk01",
        "ts": int(time.time() * 1000),
        "pose": {"x": round(x * K, 3), "y": round(y * K, 3),
                 "yaw": round(math.atan2(math.sin(yaw), math.cos(yaw)), 4)},
        "velocity": {"linear": round(v * K, 3), "angular": round(w, 3)},
        "forkHeight": round(fork * K, 3),
        "loaded": bool(loaded),
        "cargoId": cargo_id,
        "state": state,
        "taskId": task_id,
        "battery": battery,
    }), qos=0)

    time.sleep(0.1)                     # 10 Hz
```

ROS2 노드로 만든다면 `/amcl_pose` 와 `/odom` 을 구독해 위 값을 채우면 된다.

목표를 받을 때는 반대로 나눈다.

```python
def on_task(client, userdata, msg):
    t = json.loads(msg.payload)
    gx, gy = t["x"] / 10.0, t["y"] / 10.0     # MQTT -> 실물 m
    yaw = t.get("yaw", 0.0)                   # 각도는 그대로
    send_goal(gx, gy, yaw)                    # Nav2 navigate_to_pose 등
```

---

## 5. 검증 — 우리 쪽에서 이렇게 확인한다

오린카가 발행을 시작하면 F 팀이 이걸 돌린다.

```bash
source /home/ubuntu/forklift_ws/nav2/config/mqtt.env
mosquitto_sub -h $MQTT_HOST -p $MQTT_PORT -u $MQTT_USER -P $MQTT_PASS \
  --cafile /home/ubuntu/forklift_ws/nav2/config/fast-mqtt-ca.crt --insecure \
  -t 'fast/v1/vehicle/fk01/telemetry' -v
```

그리고 Isaac 의 `REAL_F01` 이 세트장의 실제 위치와 같은 자리로 움직이는지 본다.

### 자가 점검 목록

- [ ] 10 Hz 로 꾸준히 나가는가 (끊기면 관제가 LOST 로 본다)
- [ ] x 가 0 ~ 20.0, y 가 0 ~ 30.0 범위 안인가 — **벗어나면 ×10 을 안 했거나 두 번 한 것**
- [ ] 세트장 한가운데(1.0, 1.5)에서 `{"x":10.0,"y":15.0}` 이 나가는가
- [ ] 차를 손으로 오른쪽으로 밀면 x 가 커지는가
- [ ] 차를 위(랙 안쪽)로 밀면 y 가 커지는가
- [ ] +x 를 향할 때 yaw ≈ 0, +y 를 향할 때 yaw ≈ +1.571 인가
- [ ] `ts` 가 밀리초인가 (초 단위면 1000배 작다)
- [ ] 위치를 모를 때 0,0 을 보내지 않는가

### 흔한 실수

| 증상 | 원인 |
|---|---|
| 원점 근처에 붙어 있다 | **×10 을 안 함**. 실물 m 을 그대로 보냄 |
| 창고 밖 멀리 있다 | ×10 을 **두 번** 했거나, mm/cm 단위로 잼 |
| 목표를 받고 창고 밖으로 나간다 | 받을 때 **÷10 을 안 함** |
| 각도만 이상하다 | yaw 를 degree 로 보냄. **라디안** |
| 좌우가 뒤집힌다 | y 축 방향 반대. 맵 좌하단이 원점 |
| 원점에서 깜빡인다 | 위치 미확정일 때 0,0 을 보냄 |

---

## 6. 다음 단계

좌표 발행이 확인되면 아래를 순서대로 붙인다.

### 6.1 추가 발행 — 사건과 도착

telemetry 만으로는 관제가 "언제 무슨 일이 있었는지" 를 놓친다.

| 토픽 | 언제 | 예시 |
|---|---|---|
| `fast/v1/vehicle/fk01/event` | 상태가 바뀔 때 | `{"ts":..., "type":"LOAD_DONE", "level":"INFO", "message":"적재 완료"}` |
| `forklift/fk01/arrived` | **입고 바이에 도착해 멈췄을 때** | `{"ts":..., "vehicleId":"fk01", "position":{"x":17.0,"y":5.0}}` |

`arrived` 는 **AI 팀의 화물 측정 트리거**다. 이게 없으면 카메라가 언제 재야
할지 모른다. 접두사가 `forklift/` 로 다른 점에 주의 (이전 합의를 유지).

반드시 보내야 하는 `event.type`: `LOAD_DONE`, `UNLOAD_DONE`,
`ESTOP_TRIGGERED`, `RESUMED`, `TASK_FAILED`, `ERROR`.

### 6.2 구독 — 목적지를 받아 움직이기

```
fast/v1/vehicle/fk01/task       QoS 1
```

**받은 좌표는 시뮬 좌표다. ÷10 해야 실제로 갈 위치(m)가 된다.**

#### 단순 이동

```json
{ "taskId": "T-0042", "ts": 1785000000000, "action": "GOTO",
  "pickup": { "x": 17.0, "y": 5.0, "yaw": 0.0 } }
```

받으면 → `(1.70, 0.50) m` 로 환산해 자기 Nav2 에 `navigate_to_pose` 를 넣는다.
경로 계획과 장애물 회피는 차량이 알아서 한다. 관제는 목적지만 준다.

> 관제는 순환로를 따라 **다음 지점을 하나씩** 보낸다. 같은 목표를 반복해서
> 보내지 않으므로, 받을 때마다 새 목표로 갈아타면 된다.

#### 랙 적재 — `PLACE_RACK`

```json
{ "taskId": "T-0043", "action": "PLACE_RACK", "cargoId": "C-0007",
  "approach":    { "x": 5.00, "y": 9.30, "yaw": 3.1416 },
  "dock":        { "x": 2.60, "y": 9.30, "yaw": 3.1416 },
  "shelfHeight": 13.25,
  "reverseDist": 2.0 }
```

모든 길이가 시뮬 값이다. ÷10 하면 실물 값이 된다.

```
1. approach (0.500, 0.930) 까지 Nav2 로 주행
2. 포크를 shelfHeight (1.325 m) 로 상승
3. approach → dock (0.260, 0.930) 까지 저속 직진    ← Nav2 를 쓰지 않는다
4. 포크 하강 (= 화물이 선반에 얹힌다)
5. reverseDist (0.20 m) 만큼 후진
6. `event` 로 UNLOAD_DONE 발행
```

**3번이 핵심이다.** dock 지점은 차체가 랙과 겹치는 자리라 Nav2 가 "경로 없음"
을 낸다. 포크를 랙 안에 넣어야 하니 당연한 일이다. 이 구간만 **저속 직진**
으로 처리한다 — 오도메트리보다 **라이다로 랙 면까지 거리를 재서 보정**하는
편이 안전하다 (“랙 면에서 X cm 남을 때까지 전진”).

절차 전 구간의 `state` 는 **`UNLOADING`** 으로 보낸다.
바이에서 화물을 받는 절차는 **`LOADING`** 이다.

### 6.3 구독 — 정지 / 재개

```
fast/v1/vehicle/fk01/control    QoS 1
fast/v1/control/all             전체 제어 (같은 페이로드)
```

```json
{ "ts": 1785000000000, "command": "HOLD" }
```

| `command` | 받으면 |
|---|---|
| **`HOLD`** | 즉시 정지(포크 높이는 유지). `state` 를 `HOLDING` 으로. **새 `task` 를 무시** |
| **`ESTOP`** | 즉시 정지. `state` 를 `ESTOPPED` 로. `event` 로 `ESTOP_TRIGGERED` 발행 |
| **`RESUME`** | 정지 해제. 이후 `task` 를 다시 받는다 |
| `CANCEL_TASK` | 현재 목표만 취소 (정지 상태로 들어가지 않음) |

관제는 **`RESUME` 을 보낸 뒤에 `task` 를 보낸다.** 순서가 보장되므로
"정지 중에는 task 무시" 로 구현해도 목표를 놓치지 않는다.

이걸 구현하면 실물도 시뮬 차량과 **똑같은 교통 규칙**을 받게 된다
(일방통행, 앞차 적재 중 대기, 바이 한 대 예약).
규격은 [vehicle-mqtt-spec.md](vehicle-mqtt-spec.md) 5·6장에 있다.

---

## 7. 아직 확인 못 한 위험

**라이다 최소 측정거리.** 통로 폭이 0.820 m 라 벽까지 10~20 cm 인데, 소형
라이다는 `range_min` 이 0.15 m 인 경우가 많다. 그러면 벽이 안 잡혀 AMCL 이
발산한다. 쓰는 라이다 모델과 `range_min` 을 알려주면 F 팀이 맵·설정을 맞춘다.
