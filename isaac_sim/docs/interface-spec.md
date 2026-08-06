# F.A.S.T. 인터페이스 규격 (초안 v0.1)

작성: 이준서 (F, 디지털트윈·관제) / 2026-07-21
상태: **초안 — 합의 필요.** ⚠️ 표시는 아직 정해지지 않은 항목.

이 문서의 목적은 하나다. **실물 지게차와 시뮬 지게차가 관제 입장에서 구분되지 않게 만드는 것.**
그러려면 양쪽이 같은 규격을 지켜야 하고, 그 규격이 이 문서다.

---

## 0. 누가 무엇을 합의하는가

| 인터페이스 | 당사자 | 상태 |
|---|---|---|
| ① 차량 ↔ 백엔드 (MQTT) | C(실물), F(시뮬) ↔ E | 초안 있음 |
| ② 백엔드 → 관제화면 (WebSocket) | E → F | ⚠️ 미정 |
| ③ 관제화면 → 백엔드 (명령) | F → E | ⚠️ 미정 |
| ④ 화물 인식·적재 위치 → 백엔드 | A, B → E | ⚠️ 미정 |
| ⑤ 차량 내부 ROS2 토픽 | C ↔ F (동일 규격) | 초안 있음 |

---

## 1. 공통 규칙

| 항목 | 규칙 |
|---|---|
| MQTT 토픽 | `fast/v1/vehicle/{vehicleId}/...` |
| 차량 ID | `fk01` (실물), `sim02`(SIM_F02), `sim03`(SIM_F03) |
| 길이 | m |
| 속도 | m/s |
| 각도 | rad, −π~π, x축 기준 반시계(CCW +) |
| 각속도 | rad/s |
| 시각 | `ts` = epoch milliseconds (UTC) |
| 페이로드 | JSON (UTF-8). mqtt_client는 `primitive: true` |
| Retain | 사용 안 함 (오래된 위치가 남으면 위험) |

### ⚠️ 좌표계 — 가장 중요한 미정 사항

실물 지게차는 시뮬의 **1/n 크기**다 (n은 C의 실측값 확정 후 결정).

**제안: MQTT로 나가는 모든 좌표는 시뮬(실물 크기) 기준으로 통일한다.**
실물 차량이 발행 직전에 자기 좌표에 ×n을 적용한다.

이유: 변환이 딱 한 군데(실물의 발행 지점)에서만 일어난다.
관제·트윈·화면이 전부 하나의 좌표계에서 동작하므로 이중 변환 사고가 없다.

---

## 2. 차량 → 백엔드

### 2.1 telemetry
`fast/v1/vehicle/{id}/telemetry` · 10Hz · QoS 0

```json
{
  "vehicleId": "sim02",
  "ts": 1784568107610,
  "pose":     { "x": 12.34, "y": 5.67, "yaw": 1.5708 },
  "velocity": { "linear": 0.85, "angular": 0.12 },
  "forkHeight": 1.2,
  "state": "MOVING",
  "taskId": "T-0042",
  "battery": 87.5
}
```

`state`: `IDLE` | `MOVING` | `LIFTING` | `LOWERING` | `ERROR` | `ESTOPPED`
`taskId`: 작업 없으면 `null`

용도: FR-402 시각화, FR-501 작업 배분(누가 노는지), FR-504 모니터링

### 2.2 path
`fast/v1/vehicle/{id}/path` · 경로 변경 시 · QoS 1

```json
{
  "vehicleId": "sim02",
  "ts": 1784568107610,
  "taskId": "T-0042",
  "waypoints": [ {"x":12.3,"y":5.6}, {"x":15.0,"y":5.6}, {"x":15.0,"y":9.2} ],
  "etaSec": 18.5
}
```

용도: **FR-502 충돌 예측.** 관제가 미래 경로를 알아야 충돌을 *예측*할 수 있다.
현재 위치만으로는 예측이 불가능하므로 이 메시지는 생략할 수 없다.

### 2.3 event
`fast/v1/vehicle/{id}/event` · 발생 시 · QoS 1

```json
{
  "vehicleId": "sim02",
  "ts": 1784568107610,
  "type": "OBSTACLE_DETECTED",
  "severity": "WARN",
  "taskId": "T-0042",
  "detail": "전방 1.2m 장애물 감지, 정지"
}
```

`type`: `TASK_COMPLETED` | `TASK_FAILED` | `OBSTACLE_DETECTED` | `ESTOP_TRIGGERED` | `SENSOR_FAULT` | `LOW_BATTERY`
`severity`: `INFO` | `WARN` | `ERROR`

용도: FR-504 이벤트 기록, 다음 작업 배분 트리거

---

## 3. 백엔드 → 차량

### 3.1 task
`fast/v1/vehicle/{id}/task` · QoS 1

```json
{
  "taskId": "T-0042",
  "ts": 1784568107610,
  "cargoId": "C-0007",
  "pickup":  { "x": 12.3, "y": 5.6, "yaw": 0.0,    "forkHeight": 0.0 },
  "dropoff": { "x": 20.1, "y": 9.2, "yaw": 1.5708, "forkHeight": 1.5 },
  "priority": 5
}
```

**목적지만 준다. 경로는 차량의 Nav2가 만든다.**
백엔드가 경유점을 지정하면 장애물 발생 시 대응이 불가능해진다.

### 3.2 control
`fast/v1/vehicle/{id}/control` · QoS 1
전체 정지는 `fast/v1/control/all`

```json
{ "ts": 1784568107610, "command": "ESTOP" }
```

`command`: `ESTOP` | `RESUME` | `HOLD` | `CANCEL_TASK`

`HOLD`는 FR-502 교차 제어용 — "잠깐 대기하라".

⚠️ **미정: HOLD 해제 방식.** 서버가 `RESUME`을 줄 때까지 대기? 타임아웃 후 자동 해제?

---

## 4. ⚠️ 화물 정보 (A·B → 백엔드) — 확인 필요

`fast/v1/cargo/detected` · 인식 시 · QoS 1

아래는 **F의 추정안**이다. A·B가 실제로 산출 가능한 값인지 확인이 필요하다.

```json
{
  "cargoId": "C-0007",
  "ts": 1784568107610,
  "detectedBy": "fk01",
  "dimensions": { "w": 0.40, "d": 0.30, "h": 0.25 },
  "volume": 0.030,
  "sizeClass": "M",
  "centerOfMass": { "offsetX": 0.02, "offsetY": -0.01, "unbalanced": false },
  "confidence": 0.94
}
```

관련: FR-101 감지, FR-102 크기 구분, FR-103 용적 추정, FR-104 무게중심

⚠️ 적재 위치 추천(FR-201/202, B 담당) 결과를 백엔드에 어떻게 전달할지도 미정.

---

## 5. ⚠️ 백엔드 → 관제화면 (WebSocket/STOMP) — 미정

정해야 할 것:

- STOMP 목적지 이름 (예: `/topic/vehicles`, `/topic/alerts`)
- **스냅샷 vs 델타** — 전 차량 목록을 통째로 보낼지, 바뀐 것만 보낼지
- **주기** — telemetry는 10Hz로 들어오지만 화면은 그만큼 필요 없다.
  **제안: 백엔드가 모아서 2Hz로 밀어준다.** 차량 6대 × 10Hz = 초당 60건은 화면에 과하다.
- 최초 접속 시 현재 상태 전체를 받는 방법

## 6. ⚠️ 관제화면 → 백엔드 (명령) — 미정

관제자가 비상정지 버튼을 누르면 어떤 경로로 가는가?

- REST인지 WebSocket인지 (**제안: REST**)
- 엔드포인트 목록 (예: `POST /api/vehicles/{id}/estop`, `POST /api/tasks`)

**이것이 정해지지 않으면 F가 관제 화면의 버튼을 만들 수 없다.**

---

## 7. 차량 내부 ROS2 토픽 (C ↔ F 공통)

차량 내부에서만 사용한다. **MQTT로 나가지 않는다.**
네임스페이스: `/fk01/...`, `/sim02/...`

| 토픽 | 타입 | 방향 |
|---|---|---|
| `cmd_vel` | `geometry_msgs/Twist` | Nav2 → 차량 |
| `odom` | `nav_msgs/Odometry` | 차량 → |
| `scan` | `sensor_msgs/LaserScan` | 차량 → |
| `fork/cmd` | `std_msgs/Float64` (목표 높이 m) | → 차량 |
| `fork/state` | `std_msgs/Float64` (실측 높이 m) | 차량 → |
| `/tf`, `/tf_static` | | 차량 → |

### MQTT로 보내지 않는 것 (명시적 합의)

`scan`, `joint_states`, `tf`, 카메라 영상, `cmd_vel`

이유: 백엔드가 사용하지 않는다. 경로 계산은 차량의 Nav2가 수행한다.
대역폭은 telemetry의 수백 배다.

판단 기준: **"백엔드가 이 데이터로 무슨 판단을 하는가?"** 답이 없으면 보내지 않는다.

---

## 8. ⚠️ 비상정지 KPI 해석 — 합의 필요

명세서 §5 KPI는 비상정지 반응을 **≤200ms**로 규정한다.
그러나 관제자 버튼 → MQTT 브로커 → 네트워크 → 차량 경로로는 이를 보장할 수 없다.

**제안: 두 경로를 구분하고, 200ms KPI는 로컬 경로에만 적용한다.**

| 종류 | 경로 | 200ms 적용 |
|---|---|---|
| 차량이 스스로 장애물 감지 | 차량 내부 ROS2 | ✅ |
| 관제자가 버튼 조작 | MQTT 경유 | ❌ (사람 반응시간이 이미 수백 ms) |

합의 후 명세서 KPI 표에 반영해야 한다.

---

## 9. 미정 사항 체크리스트

| # | 항목 | 필요한 사람 |
|---|---|---|
| 1 | 축척 n 확정 (서보 최대 조향각, 포크 실제 스트로크 실측) | C |
| 2 | 좌표계를 시뮬 기준으로 통일 (§1) | C, E, F |
| 3 | HOLD 해제 방식 (§3.2) | E |
| 4 | 화물 메시지 필드 확정 (§4) | A, B, E |
| 5 | 적재 위치 추천 전달 방식 (§4) | B, E |
| 6 | WebSocket 규격 (§5) | E, F |
| 7 | 화면→서버 명령 경로 (§6) | E, F |
| 8 | 비상정지 KPI 해석 (§8) | 전원 |
| 9 | MQTT 브로커 위치·주소·인증 | E |
| 10 | ~~Orin의 ROS2 배포판~~ → **humble 로 확정. 서버도 humble 로 통일** | ✅ 완료 |

---

## 부록 A. 왜 ROS2가 아니라 MQTT로 기기 간 통신을 하는가

1. **네트워크를 넘는 ROS2 통신은 신뢰할 수 없다.** 배포판을 humble 로 통일했더라도,
   아래 2번(멀티캐스트) 문제는 그대로 남는다.
2. **DDS는 네트워크를 넘기 어렵다** — 멀티캐스트 디스커버리는 서브넷을 넘지 못한다.

MQTT는 브로커를 경유하므로 두 문제가 모두 사라진다.
따라서 **ROS2는 각 기기 내부에서만, 기기 간은 MQTT**라는 경계를 반드시 지킨다.

## 부록 B. 참고 — 확정된 값

| 항목 | 값 | 출처 |
|---|---|---|
| 시뮬 지게차 | NVIDIA ForkliftB | Isaac Sim 기본 에셋 |
| 축거 | 1.49 m (시뮬 기준) | USD 실측 |
| 구동륜 반경 | 0.16 m (시뮬 기준) | USD 실측 |
| 조향 방식 | **후륜 조향** | 실물 하드웨어 확인 |
| 조향 한계(원본) | ±60° → 실물 사양으로 조일 것 | USD |
| 포크 행정(원본) | 0~2.0 m | USD |

⚠️ **명세서 §2.2의 "앞바퀴 조향(Ackermann형)" 및 §4 EPIC-3 비고의 "자동차형" 서술은 실제 하드웨어(후륜 조향)와 다르다.**
후륜 조향은 운동학 모델이 다르므로 Nav2 설정에 영향을 준다. 명세서 수정 및 D(자율주행) 공유가 필요하다.
