# 차량 MQTT 규격 (실물 지게차 구현용)

실물 지게차(오린카)가 관제와 주고받을 MQTT 메시지 규격.
**시뮬 차량도 같은 규격을 쓴다** — 관제는 실물/시뮬을 구분하지 않는다.

`docs/interface-spec.md` 의 형식을 따르고, 실물 구현에 필요한 부분만 자세히
풀었다.

---

## 0. 실물이 해야 할 일 — 3가지

| # | 무엇 | 방향 | 주기 |
|---|---|---|---|
| 1 | **내 위치를 알린다** | 발행 | 10 Hz |
| 2 | **일이 생기면 알린다** (도착·적재·하역·오류) | 발행 | 발생 시 |
| 3 | **관제 명령을 따른다** (목적지·정지) | 구독 | 수신 시 |

3번이 없으면 관제가 교통 규칙을 적용할 수 없다. 1번만 있으면 다른 차량이
피해 다니는 "움직이는 장애물" 로만 취급된다.

---

## 1. 공통 규칙

| 항목 | 값 |
|---|---|
| 브로커 | 팀 공용 (호스트·포트는 별도 공유) |
| 토픽 접두 | `fast/v1/vehicle/{vehicleId}/...` |
| vehicleId | 실물 `fk01`, 시뮬 `sim02`(SIM_F02), `sim03`(SIM_F03) |
| 페이로드 | JSON (UTF-8) |
| `ts` | epoch milliseconds (UTC) |
| 길이 | **m** |
| 각도 | **rad**, −π~π, x축 기준 반시계(CCW +) |
| Retain | 사용 안 함 (오래된 위치가 남으면 위험) |

### 좌표계 — 확정 (2026-08-05)

**MQTT 로 오가는 모든 좌표는 시뮬레이션 좌표계(20 × 30)다.**
실물 세트장은 그 1/10 인 2 × 3 m 이고, **변환은 차량(오린카)이 자기 쪽에서** 한다.

```
    보낼 때   내가 잰 실물 m   × 10  →  MQTT
    받을 때   MQTT 좌표        ÷ 10  →  내가 갈 실물 m
```

길이 단위는 전부 같은 규칙이다 — `pose`, `velocity.linear`, `forkHeight`,
`shelfHeight`, `reverseDist`, `cargo.w/d/h`. **`yaw` 만 예외**로 라디안 그대로.

관제(백엔드)와 시뮬은 아무 변환도 하지 않는다.

상세와 자가 점검 목록은 [orin-pose-spec.md](orin-pose-spec.md) 2장에 있다.

---

## 2. 발행 ① 위치 — `telemetry`

```
fast/v1/vehicle/fk01/telemetry     10 Hz     QoS 0
```

```json
{
  "vehicleId": "fk01",
  "ts": 1785000000000,
  "pose":     { "x": 15.5, "y": 4.0, "yaw": 1.5708 },
  "velocity": { "linear": 1.2, "angular": 0.03 },
  "forkHeight": 1.325,
  "loaded": true,
  "cargoId": "C-0007",
  "cargo":    { "id": "C-0007", "w": 0.75, "d": 0.91, "h": 1.11 },
  "state": "MOVING",
  "taskId": "T-0042",
  "battery": 87.5
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `pose.x`, `pose.y` | **필수** | 지도 원점 기준(m). 이것만 있어도 교통 규칙이 동작한다 |
| `pose.yaw` | 권장 | 차량이 바라보는 방향(rad) |
| `velocity.linear` | 선택 | 전진 속도(m/s). 음수면 후진 |
| `forkHeight` | 권장 | 포크 높이(m). 바닥에서 포크 윗면까지 |
| `loaded` | **필수** | 화물을 들고 있는가 |
| `cargoId` | 권장 | 들고 있는 화물 ID (없으면 `null`) |
| `state` | 권장 | 아래 표 참고 |
| `taskId` | 권장 | 지금 수행 중인 작업 ID (없으면 `null`) |
| `battery` | 선택 | % |

`state` 값: `IDLE` · `MOVING` · `LIFTING` · `LOWERING` · `DOCKING` ·
`HOLDING` · `ESTOPPED` · `ERROR`

> **10 Hz 를 지킬 것.** 관제가 0.5초마다 판단하는데, 위치가 그보다 늦게 오면
> 앞차와의 간격 계산이 어긋난다. **2초 이상 끊기면 관제는 그 차량을 제어
> 불가로 보고 명령을 중단한다.**

---

## 3. 발행 ② 사건 — `event`

```
fast/v1/vehicle/fk01/event     발생 시     QoS 1
```

```json
{
  "vehicleId": "fk01",
  "ts": 1785000000000,
  "type": "CARGO_PLACED",
  "severity": "INFO",
  "taskId": "T-0042",
  "cargoId": "C-0007",
  "detail": "랙 A1 선반에 적재 완료"
}
```

### 반드시 보내야 하는 사건

| `type` | 언제 | `severity` |
|---|---|---|
| `TASK_COMPLETED` | 받은 작업을 끝냈을 때 | INFO |
| `TASK_FAILED` | 작업을 못 끝냈을 때 (경로 실패 등) | ERROR |
| **`CARGO_LOADED`** | **포크에 화물을 실었을 때** | INFO |
| **`CARGO_PLACED`** | **화물을 내려놓았을 때** | INFO |
| `ESTOP_TRIGGERED` | 비상정지가 걸렸을 때 | ERROR |
| `OBSTACLE_DETECTED` | 장애물로 스스로 멈췄을 때 | WARN |
| `SENSOR_FAULT` | 센서 이상 | ERROR |
| `LOW_BATTERY` | 배터리 부족 | WARN |

**`CARGO_LOADED` / `CARGO_PLACED` 가 중요하다.** 관제가 이걸 받아야
"이 차는 이제 랙으로 가야 한다 / 다시 바이로 가야 한다" 를 판단하고,
랙 재고를 갱신한다.

```json
// 적재 완료 — 포크를 올려 화물이 실린 것을 확인한 시점
{"type":"CARGO_LOADED","cargoId":"C-0007","detail":"높이 0.15 m"}

// 하역 완료 — 포크를 내려 화물이 선반에 얹힌 것을 확인한 시점
{"type":"CARGO_PLACED","cargoId":"C-0007","detail":"랙 A1"}
```

---

## 4. 발행 ③ 도착 — `arrived`

입고 바이에 도착해 멈췄을 때. AI(비전)가 이 메시지를 받아 화물 측정을
시작한다.

```
forklift/{vehicleId}/arrived     QoS 1
```

```json
{
  "vehicleId": "fk01",
  "event": "ARRIVED",
  "location": "INBOUND",
  "position": { "x": 1.70, "y": 0.50, "frameId": "map" },
  "heading": 0.0,
  "messageAt": "2026-08-05T10:30:45.304+09:00"
}
```

`location`: `INBOUND`(입고) · `OUTBOUND`(출고) · `RACK`(랙)

---

## 5. 구독 ① 목적지 — `task`

> **좌표는 시뮬 좌표계(0~20 × 0~30)로 옵니다.** 받아서 **÷10** 해야 실제로
> 갈 위치(m)가 됩니다. `shelfHeight` · `reverseDist` 같은 길이도 마찬가지입니다.
> 자세한 것은 [orin-pose-spec.md](orin-pose-spec.md) 2장을 보세요.

```
fast/v1/vehicle/fk01/task     QoS 1
```

### 5.1 단순 이동

```json
{
  "taskId": "L-0042",
  "ts": 1785000000000,
  "action": "GOTO",
  "pickup": { "x": 15.5, "y": 27.0, "yaw": 1.5708 }
}
```

**받으면**: 자기 Nav2 에 `navigate_to_pose` 로 그 좌표를 넣는다.
경로 계획과 장애물 회피는 차량이 알아서 한다. 관제는 목적지만 준다.

> 관제는 순환로를 따라 **다음 모서리를 하나씩** 보낸다. 같은 목표를 반복해서
> 보내지는 않으므로, 받을 때마다 새 목표로 갈아타면 된다.

### 5.2 랙 적재 — `PLACE_RACK`

```json
{
  "taskId": "T-0042",
  "ts": 1785000000000,
  "action": "PLACE_RACK",
  "cargoId": "C-0007",
  "approach":    { "x": 5.00, "y": 9.30, "yaw": 3.1416 },
  "dock":        { "x": 2.60, "y": 9.30, "yaw": 3.1416 },
  "shelfHeight": 1.325,
  "reverseDist": 2.0
}
```

**받으면 아래 순서로 수행한다.**

```
1. approach 까지 Nav2 로 주행
2. shelfHeight 까지 포크 상승
3. approach → dock 까지 저속 직진        ← Nav2 를 쓰지 않는다
4. 포크 하강 (= 화물이 선반에 얹힌다)
5. reverseDist 만큼 후진
6. CARGO_PLACED 이벤트 발행
```

> **3번이 핵심이다.** dock 지점은 차체가 랙과 겹치는 자리라 Nav2 가
> "경로 없음" 을 낸다. 포크를 랙 안에 넣어야 하므로 당연한 일이다.
> 그래서 이 구간만 **저속 직진(오도메트리 또는 거리센서)** 으로 처리한다.

**정밀도 주의.** 실물은 시뮬의 1/10 이라 허용 오차도 1/10 이다.

| | MQTT(시뮬) 값 | 실물로 환산 |
|---|---|---|
| 도킹 직진 거리 | 2.4 | **24 cm** |
| 포크 삽입 허용 오차 | 1.2 | **1.2 cm** |

21 cm 직진에 1.2 cm 오차는 오도메트리만으로는 빠듯하다.
**라이다로 랙 면까지 거리를 재서 보정**하는 방식을 권장한다
(“랙 면에서 X cm 남을 때까지 전진”).

### 5.3 랙에서 꺼내기 — `PICK_RACK`

`PLACE_RACK` 의 역순. 포크를 선반 아래로 넣고 살짝 올려 화물을 든 뒤 후진.

```json
{"taskId":"T-0043","action":"PICK_RACK","cargoId":"C-0007",
 "approach":{...},"dock":{...},"shelfHeight":1.325,"reverseDist":2.0}
```
완료 후 `CARGO_LOADED` 이벤트를 발행한다.

---

## 6. 구독 ② 제어 — `control`

```
fast/v1/vehicle/fk01/control     QoS 1
전체 정지는  fast/v1/control/all
```

```json
{ "ts": 1785000000000, "command": "HOLD" }
```

| `command` | 뜻 | 받으면 |
|---|---|---|
| **`HOLD`** | 교통 대기 (앞차·바이 혼잡) | 즉시 정지. **새 `task` 를 무시**한다 |
| **`RESUME`** | 대기 해제 | 정지 해제. 이후 `task` 를 다시 받는다 |
| `CANCEL_TASK` | 현재 작업 취소 | 목표만 취소 (정지 상태로 들어가지 않음) |
| `ESTOP` | 비상정지 | 즉시 정지 + `ESTOP_TRIGGERED` 발행 |

### HOLD 구현 시 주의

```
HOLD 수신 → 주행 정지 (포크는 현재 높이 유지)
         → state 를 "HOLDING" 으로 바꿔 telemetry 에 반영
         → RESUME 전까지 새 task 무시

RESUME 수신 → 정지 해제. 관제가 곧 새 task 를 보낸다
```

관제는 **RESUME 을 보낸 뒤에 task 를 보낸다.** 순서가 보장되므로
"HOLD 중 task 무시" 로 구현해도 목표를 놓치지 않는다.

---

## 7. 안전 — 반드시 지킬 것

1. **비상정지는 차량 자체 센서로 한다.** MQTT 는 네트워크 지연이 있어
   200 ms 이내 반응을 보장할 수 없다. 관제의 `HOLD` 는 "여유 있는 대기" 용이다.
2. **명령이 끊기면 스스로 멈춘다.** 마지막 명령 후 일정 시간(권장 0.5 s)
   갱신이 없으면 속도를 0 으로 한다. 관제나 네트워크가 죽어도 안전해야 한다.
3. **HOLD 와 ESTOP 을 구분한다.** HOLD 는 교통 대기(정상), ESTOP 은 비상.
   ESTOP 해제는 사람이 확인한 뒤에만.
4. **도킹 중에는 전방 감시를 유지한다.** 저속 직진 구간에서도 사람이
   들어오면 멈춰야 한다.

---

## 8. 구현 체크리스트

- [ ] 좌표계 축척 합의 (실물 기준 m 로 확정)
- [ ] `telemetry` 10 Hz 발행 (`pose`, `loaded` 필수)
- [ ] `event` 발행 — 특히 `CARGO_LOADED`, `CARGO_PLACED`
- [ ] 바이 도착 시 `arrived` 발행
- [ ] `task` 수신 → `GOTO` 를 Nav2 목표로 전달
- [ ] `task` 수신 → `PLACE_RACK` 6단계 수행
- [ ] `control` 수신 → `HOLD` / `RESUME` / `ESTOP`
- [ ] HOLD 중 `task` 무시, `state = HOLDING` 반영
- [ ] 명령 끊김 시 자동 정지 (0.5 s 워치독)
- [ ] 도킹 구간 거리 보정 (라이다 권장)

---

## 9. 테스트 방법

브로커만 있으면 관제 없이도 혼자 확인할 수 있다.

```bash
# 내가 잘 보내고 있나 (다른 터미널에서)
mosquitto_sub -t 'fast/v1/vehicle/fk01/#' -v
mosquitto_sub -t 'forklift/fk01/arrived' -v

# 관제 흉내 — 목적지 주기
mosquitto_pub -t 'fast/v1/vehicle/fk01/task' \
  -m '{"taskId":"T-1","action":"GOTO","pickup":{"x":15.5,"y":27.0,"yaw":1.5708}}'

# 관제 흉내 — 랙 적재
mosquitto_pub -t 'fast/v1/vehicle/fk01/task' -m '{
  "taskId":"T-2","action":"PLACE_RACK","cargoId":"C-0007",
  "approach":{"x":5.00,"y":9.30,"yaw":3.1416},
  "dock":{"x":2.60,"y":9.30,"yaw":3.1416},
  "shelfHeight":1.325,"reverseDist":2.0}'

# 관제 흉내 — 정지 / 해제
mosquitto_pub -t 'fast/v1/vehicle/fk01/control' -m '{"command":"HOLD"}'
mosquitto_pub -t 'fast/v1/vehicle/fk01/control' -m '{"command":"RESUME"}'
```

### 통과 기준

| # | 테스트 | 기대 |
|---|---|---|
| 1 | 가만히 두고 telemetry 관찰 | 10 Hz, `pose` 가 실제 위치와 일치 |
| 2 | `GOTO` 전송 | 그 좌표로 주행, 도착 후 `TASK_COMPLETED` |
| 3 | 주행 중 `HOLD` 전송 | 1초 이내 정지, `state = HOLDING` |
| 4 | HOLD 중 `task` 전송 | **무시** (움직이지 않음) |
| 5 | `RESUME` 후 `task` 전송 | 정상 주행 |
| 6 | `PLACE_RACK` 전송 | 6단계 수행, `CARGO_PLACED` 발행 |
| 7 | 브로커 연결 끊기 | 0.5초 내 자동 정지 |

---

## 10. 참고

| 문서 | 내용 |
|---|---|
| `docs/interface-spec.md` | 메시지 형식 원본 (전체) |
| `docs/traffic-rules-spec.md` | 관제가 어떤 규칙으로 명령을 내는지 |
| `nav2/scripts/cargo_demo.py` | 시뮬의 `place_on_rack()` — 도킹 절차 참고 구현 |
| `nav2/scripts/mqtt_bridge.py` | 시뮬 쪽 MQTT 어댑터 |
