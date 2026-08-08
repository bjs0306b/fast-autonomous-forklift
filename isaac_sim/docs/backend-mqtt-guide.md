# 백엔드 MQTT 연동 안내 (E팀용)

작성 2026-08-05 · 작성자 F(디지털 트윈·관제 프론트)

시뮬레이터 쪽 MQTT 연동이 동작 확인까지 끝났습니다. 이 문서 하나로
**브로커 접속 → 토픽 → 페이로드 → 백엔드가 구현할 것** 까지 정리했습니다.

교통 규칙의 상세 알고리즘은 [traffic-rules-spec.md](traffic-rules-spec.md),
실물 차량(C팀) 규격은 [orin-pose-spec.md](orin-pose-spec.md) 를 봅니다.

---

## 0. 먼저 — 오늘 바뀐 것 (기존 구현이 있다면 확인 필요)

| # | 항목 | 이전 | **현재** | 영향 |
|---|---|---|---|---|
| 1 | 시뮬 차량 ID | `sim01`, `sim02` | **`sim02`, `sim03`** | **깨짐** — 토픽 이름이 바뀝니다 |
| 2 | 좌표 단위 | 실물 m (0~2, 0~3) | **시뮬 좌표 (0~20, 0~30)** | **깨짐** — 값이 10배 |
| 3 | `cargo` 필드 | 없음 | 추가 | 호환 (무시 가능) |
| 4 | `state` 값 | — | `LOADING`, `UNLOADING` 추가 | 호환 (미지의 값 처리만) |

### 1. 차량 ID 를 프림 번호와 맞췄습니다

Isaac 씬의 프림 이름과 MQTT ID 가 하나씩 어긋나 혼란이 있어 정리했습니다.

| Isaac 프림 | MQTT vehicleId | 비고 |
|---|---|---|
| SIM_F02 | **`sim02`** | 이전 `sim01` |
| SIM_F03 | **`sim03`** | 이전 `sim02` |
| REAL_F01 (실물 오린카) | `fk01` | 변경 없음 |

### 2. 좌표는 시뮬 좌표계로 통일했습니다

**MQTT 위의 모든 좌표는 시뮬레이션 좌표계(20 × 30)입니다.**
실물 세트장은 그 1/10 인 2 × 3 m 이고, **변환은 실물 차량(오린카)이 자기 쪽에서**
합니다 (보낼 때 ×10, 받을 때 ÷10).

백엔드는 **아무 변환도 하지 않습니다.** 받은 좌표를 그대로 쓰고,
목표도 시뮬 좌표로 보내면 됩니다.

```
           x: 0 ~ 20.0      y: 0 ~ 30.0      yaw: -π ~ +π (라디안, 반시계 +)
원점 (0,0) = 창고 왼쪽 아래 모서리
```

---

## 1. 브로커 접속

| 항목 | 값 |
|---|---|
| 호스트 | `i15a304.p.ssafy.io` (3.38.178.143) |
| 포트 | **8883 — TLS 전용**. 1883 은 닫혀 있습니다 |
| 인증 | 아이디 / 비밀번호 (별도 전달) |
| 인증서 | 자체 서명 CA `FAST-MQTT-CA` |

인증서 CN 이 호스트명이 아니라 **IP** 라서, 호스트명으로 접속하면 hostname
검증에 실패합니다. 두 가지 중 하나로 처리합니다.

- **권장** — CA 파일(`fast-mqtt-ca.crt`, F팀이 전달)을 truststore 에 넣고
  hostname 검증만 끄기
- **임시** — 인증서 검증 생략 (개발 중에만)

Spring 예시 (Eclipse Paho):

```java
MqttConnectOptions opts = new MqttConnectOptions();
opts.setUserName("아이디");
opts.setPassword("비번".toCharArray());
opts.setAutomaticReconnect(true);
opts.setCleanSession(true);

// CA 를 넣은 truststore 사용 + hostname 검증만 끄기
SSLSocketFactory sf = SslUtil.getSocketFactory("classpath:fast-mqtt-ca.crt");
opts.setSocketFactory(sf);
opts.setHttpsHostnameVerificationEnabled(false);   // CN 이 IP 라서 필요

MqttClient client = new MqttClient("ssl://i15a304.p.ssafy.io:8883",
                                   "fast-backend", new MemoryPersistence());
client.connect(opts);
```

> 근본 해결은 인증서를 **SAN 에 도메인을 넣어 재발급**하는 것입니다.
> 브로커를 운영하는 쪽에서 가능하면 그렇게 하는 편이 낫습니다.

---

## 2. 토픽 한눈에

`{id}` = `sim02` | `sim03` | `fk01`

### 백엔드가 **구독**할 것 (차량 → 관제)

| 토픽 | 주기 | 내용 |
|---|---|---|
| `fast/v1/vehicle/{id}/telemetry` | 10 Hz | 위치·속도·포크·적재·상태 |
| `fast/v1/vehicle/{id}/event` | 변화 시 | 상태 전이, 오류 |
| `forklift/{id}/arrived` | 도착 시 | 입고 바이 도착 (AI 측정 트리거) |

> `arrived` 만 접두사가 `forklift/` 로 다릅니다. 이전 합의를 그대로 둔 것이라,
> 통일이 필요하면 알려주세요.

### 백엔드가 **발행**할 것 (관제 → 차량)

| 토픽 | 내용 |
|---|---|
| `fast/v1/vehicle/{id}/task` | 목적지 지시 |
| `fast/v1/vehicle/{id}/control` | 정지 / 재개 / 작업취소 |
| `fast/v1/vehicle/{id}/cargo` | 화물 적재·랙 적재 지시 |
| `fast/v1/control/all` | 전체 제어 |
| `fast/v1/sim/camera` | 시뮬 화면 시점 전환 |

---

## 2.5 빠른 시작 — 멈추고 움직이기

가장 먼저 해보면 좋은 네 가지입니다. 그대로 복사해 쓰면 됩니다.
(`<아이디>` `<비번>` 은 별도 전달, `fast-mqtt-ca.crt` 도 함께 드립니다)

```bash
B="-h i15a304.p.ssafy.io -p 8883 -u <아이디> -P <비번> --cafile fast-mqtt-ca.crt --insecure"
```

### ① 지금 어디 있나 (구독)

```bash
mosquitto_sub $B -t 'fast/v1/vehicle/+/telemetry' -v
```

### ② 멈춰라

```bash
mosquitto_pub $B -t 'fast/v1/vehicle/sim02/control' -m '{"command":"ESTOP"}'
```

즉시 멈추고, telemetry 의 `state` 가 `ESTOPPED` 로 바뀝니다.
목표 취소만 하는 게 아니라 속도 0 을 20 Hz 로 계속 눌러서 붙잡습니다.

### ③ 다시 움직여라

```bash
mosquitto_pub $B -t 'fast/v1/vehicle/sim02/control' -m '{"command":"RESUME"}'
```

`state` 가 `MOVING` / `IDLE` 로 돌아옵니다.

### ④ 저기로 가라

```bash
mosquitto_pub $B -t 'fast/v1/vehicle/sim02/task' \
  -m '{"taskId":"T-1","x":17.0,"y":5.0,"yaw":0.0}'
```

좌표는 **시뮬 좌표**(0~20 × 0~30). 위 예시는 입고 바이입니다.

### 전체 정지 / 해제

```bash
mosquitto_pub $B -t 'fast/v1/control/all' -m '{"command":"ESTOP"}'
mosquitto_pub $B -t 'fast/v1/control/all' -m '{"command":"RESUME"}'
```

### Java (Paho) 로는

```java
// 멈춤
client.publish("fast/v1/vehicle/sim02/control",
        "{\"command\":\"ESTOP\"}".getBytes(StandardCharsets.UTF_8), 1, false);

// 재개
client.publish("fast/v1/vehicle/sim02/control",
        "{\"command\":\"RESUME\"}".getBytes(StandardCharsets.UTF_8), 1, false);

// 이동
String task = """
    {"taskId":"T-1","x":17.0,"y":5.0,"yaw":0.0}
    """;
client.publish("fast/v1/vehicle/sim02/task",
        task.getBytes(StandardCharsets.UTF_8), 1, false);
```

QoS 는 명령류 **1**, telemetry 구독은 **0** 을 권합니다.

### 주의

- **정지 중에는 `task` 가 무시됩니다.** 먼저 `RESUME` 를 보내세요.
- F팀 데모가 돌고 있으면 데모도 목표를 보내므로 서로 밀어냅니다.
  목적지 지시를 시험할 때는 F팀에 데모를 꺼 달라고 하세요.
  **정지/재개는 데모가 돌아도 그대로 먹습니다** (데모가 관제 정지를 존중하도록
  되어 있습니다).

---

## 3. telemetry (구독)

```json
{
  "vehicleId": "sim02",
  "ts": 1785000000000,
  "pose":     { "x": 12.34, "y": 5.67, "yaw": 1.5708 },
  "velocity": { "linear": 1.0, "angular": 0.0 },
  "forkHeight": 1.325,
  "loaded": true,
  "cargoId": "C0007",
  "cargo":  { "id": "C0007", "w": 0.75, "d": 0.91, "h": 1.11 },
  "state": "UNLOADING",
  "taskId": "T-1042",
  "battery": 100.0
}
```

| 필드 | 형 | 설명 |
|---|---|---|
| `vehicleId` | string | `sim02` / `sim03` / `fk01` |
| `ts` | int | 유닉스 **밀리초** |
| `pose.x` `pose.y` | float | **시뮬 좌표** 0~20 / 0~30 |
| `pose.yaw` | float | 라디안, −π~+π, 반시계 + |
| `velocity.linear` | float | m/s (시뮬 단위). 후진 음수 |
| `velocity.angular` | float | rad/s |
| `forkHeight` | float | 포크 높이 (시뮬 단위). 바닥 0 |
| `loaded` | bool | 화물 소지 여부 |
| `cargoId` | string \| null | 화물 식별자 |
| **`cargo`** | object \| null | **신규** — 화물 크기. 미소지 시 `null` |
| `cargo.w` `d` `h` | float | 가로·세로·**전체 높이**(파레트 포함) |
| `state` | string | 아래 표 |
| `taskId` | string \| null | 수행 중인 task |
| `battery` | float | 0~100 |

### `state` 값

| 값 | 뜻 | 관제 화면 표시 예 |
|---|---|---|
| `IDLE` | 정지, 명령 대기 | 대기 |
| `MOVING` | 주행 중 | 이동 중 |
| **`LOADING`** | **입고 바이에서 화물 받는 중** (신규) | 적재 중 |
| **`UNLOADING`** | **랙에 내려놓는 절차 진행 중** (신규) | 하역 중 |
| `LIFTING` / `LOWERING` | 포크 상승 / 하강 | 포크 동작 |
| `HOLDING` | 관제가 멈춤(HOLD) | 대기(관제) |
| `ESTOPPED` | 비상정지 | 비상정지 |
| `ERROR` | 고장 | 오류 |

`LOADING` / `UNLOADING` 은 **포크가 움직이지 않는 구간까지 포함**합니다.
시뮬은 바이에서 화물이 즉시 생성되어 포크 동작이 없기 때문에, 이게 없으면
"받는 중" 을 표시할 방법이 없어서 추가했습니다. 실물 오린카도 같은 값을
쓰기로 하면 관제는 시뮬·실물을 구별 없이 다룰 수 있습니다.

> **모르는 `state` 값이 오면 무시하거나 원문 그대로 표시**해 주세요.
> 앞으로 값이 늘어날 수 있습니다.

### 끊김 판정

`ts` 가 **3초 이상** 갱신되지 않으면 그 차량을 `LOST` 로 간주하는 것을 권합니다.
`pose` 를 아직 못 구한 차량은 그 주기를 **아예 발행하지 않습니다**
(0,0 이 오는 일은 없습니다).

---

## 4. task (발행) — 목적지 지시

### 4.1 단순 이동

```json
{ "taskId": "T-1042", "x": 17.0, "y": 5.0, "yaw": 0.0 }
```

### 4.2 집기 → 놓기

```json
{
  "taskId": "T-1043",
  "pickup":  { "x": 17.0, "y": 5.0,  "yaw": 0.0,   "forkHeight": 0.0 },
  "dropoff": { "x": 5.0,  "y": 9.3,  "yaw": 3.1416, "forkHeight": 1.325 }
}
```

`forkHeight` 가 있으면 그 지점 도착 후 포크를 그 높이로 맞춥니다.

정지(`HOLD`/`ESTOP`) 상태의 차량에 보낸 task 는 **무시**되고 로그만 남습니다.
먼저 `RESUME` 를 보내야 합니다.

---

## 5. control (발행) — 정지 / 재개

```json
{ "command": "ESTOP" }
```

| command | 동작 |
|---|---|
| `ESTOP` | 비상정지. 목표 취소 + 속도 0 을 20 Hz 로 지속 발행해 붙잡음 |
| `HOLD` | 같은 동작, 이벤트 등급만 WARN |
| `RESUME` | 정지 해제 |
| `CANCEL_TASK` | 현재 목표만 취소 (정지 아님) |

전체 정지는 `fast/v1/control/all` 에 같은 페이로드를 보냅니다.

정지 중에는 `state` 가 `ESTOPPED` / `HOLDING` 으로 바뀌어 telemetry 로
되돌아오므로, 관제 화면은 이 필드만 보면 됩니다.

---

## 6. cargo (발행) — 화물 지시

```json
{ "height": 0.15, "cargoId": "C-0007" }        // 바이에서 화물 받기 (실물 m)
{ "action": "align_bay" }                       // 바이 정면 정렬
{ "action": "place_rack", "rack": "A1" }        // 랙에 적재
{ "action": "drop" }                            // 지금 자리에 내려놓기
```

`height` 만 **실물 미터**입니다 (카메라 측정값을 그대로 전달하기 때문).
나머지 좌표는 모두 시뮬 좌표계입니다.

### 랙 슬롯

`A1`~`A12` (왼쪽 벽), `B1`~`B12` (가운데). 각 슬롯의 좌표:

| 랙 | 접근점 x | 도킹 x | 적재점 x | y (12칸) |
|---|---|---|---|---|
| A | 5.0 | 2.60 | 0.95 | 9.3 / 10.5 / 11.8 / 13.2 / 14.5 / 15.8 / 17.2 / 18.5 / 19.7 / 21.2 / 22.5 / 23.8 |
| B | 14.5 | 11.95 | 10.20 | 위와 동일 |

선반 높이(포크 목표)는 `1.325`, 진입 방향 yaw 는 `3.1416`(서쪽).

**높이 값 환산** — 모두 시뮬 단위이며 실물은 ÷10 입니다.

| 항목 | 시뮬 (MQTT) | 실물 |
|---|---|---|
| 선반 높이 | 1.325 | 132.5 mm |
| 포크 최대 | 1.5 | 150 mm |
| 파레트 두께 | 0.21 | 21 mm |
| 화물 전체 최대 (파레트+상자) | 1.11 | 111 mm |

**접근점까지만 Nav2 로 가고, 그 뒤 도킹·적재·후진은 차량이 자체 수행**합니다.
백엔드는 접근점 도착을 확인한 뒤 `place_rack` 만 보내면 됩니다.

---

## 7. 백엔드가 구현할 것 — 교통 규칙

현재 교통 규칙은 F팀의 파이썬(`demo_loop2.py`)이 **로컬 ROS 로 직접** 돌리고
있습니다. 이걸 백엔드로 옮기면 시뮬과 실물을 한 곳에서 관제할 수 있습니다.

옮겨야 할 것은 **판단 로직뿐**입니다. 명령 전달 수단(위 토픽들)은 이미 전부
열려 있습니다.

| 옮길 것 | 설명 |
|---|---|
| **호장(arc-length) 투영** | 차량 (x,y) 를 순환로 위 거리 `s` 로 변환. `gap = (s_other − s_me) mod 67.0` 로 앞뒤 판정. **유클리드 거리로는 세로 통로에서 오판**합니다 |
| **일방통행** | 반시계 순환. 모서리 4개: (15.5,4) (15.5,27) (5,27) (5,4), 둘레 67.0 |
| **차간 유지** | 앞차와 `gap < 9.0` 이면 HOLD, `> 6.0` 회복 시 재개 |
| **바이 예약** | 입고 바이(17,5)는 한 대만. 다른 차는 **멈추지 않고 순환로를 계속 돎**(차선에 서면 교착) |
| **진입 간격** | 동시 출발 방지. 한 주기에 한 대씩 합류 허가 |
| **정체 감시** | 20초간 진전 없으면 목표 재전송 |

알고리즘 상세와 상태 전이는 [traffic-rules-spec.md](traffic-rules-spec.md)
(13장, 조건표 포함)에 있습니다. 참조 구현은
`nav2/scripts/demo_loop2.py` 와 `nav2/scripts/track.py` 입니다.

### 권하는 순서

1. 먼저 **telemetry 를 받아 관제 화면에 차량을 그리는 것**까지
2. 그다음 **정지/재개** (가장 단순하고 효과가 눈에 보임)
3. 그다음 **목적지 지시**
4. 마지막에 **교통 규칙 전체 이식**

3번까지는 F팀 데모와 병행해도 충돌하지 않습니다. 4번은 F팀 데모를 끄고
전환해야 합니다 (둘 다 목표를 보내면 서로 밀어냅니다).

---

## 8. 시험 방법

브로커에 붙어 직접 확인할 수 있습니다.

```bash
# 지금 흐르는 것 전부 보기
mosquitto_sub -h i15a304.p.ssafy.io -p 8883 -u <아이디> -P <비번> \
  --cafile fast-mqtt-ca.crt --insecure -t '#' -v -W 10

# 차량 하나 세우기
mosquitto_pub -h i15a304.p.ssafy.io -p 8883 -u <아이디> -P <비번> \
  --cafile fast-mqtt-ca.crt --insecure \
  -t 'fast/v1/vehicle/sim02/control' -m '{"command":"ESTOP"}'

# 목적지 주기
mosquitto_pub ... -t 'fast/v1/vehicle/sim02/task' \
  -m '{"taskId":"T-1","x":17.0,"y":5.0,"yaw":0.0}'
```

F팀 시뮬이 켜져 있어야 반응합니다. 필요한 시각을 알려주면 맞춰 띄우겠습니다.

`--insecure` 는 hostname 검증만 끄는 옵션이라 `--cafile` 이 반드시 함께
있어야 합니다 (없으면 `A TLS error occurred`).

---

## 9. 아직 정하지 못한 것

| 항목 | 내용 |
|---|---|
| `arrived` 토픽 접두사 | 혼자 `forklift/` 를 씁니다. `fast/v1/vehicle/{id}/arrived` 로 통일할까요? |
| 인증서 | CN 이 IP 라 hostname 검증을 꺼야 합니다. SAN 에 도메인을 넣어 재발급 가능한지 |
| 화물 정보 | `cargo.w/d/h` 외에 무게·품목코드 등이 필요한지 |
| `battery` | 시뮬은 항상 100 입니다. 실물은 C팀이 채울 예정 |

---

문의는 F(디지털 트윈) 에게 주세요. 위 토픽으로 직접 쏴 보면서 확인하는 게
가장 빠릅니다.
