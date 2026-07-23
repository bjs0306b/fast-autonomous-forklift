# F.A.S.T. 통신 규격 (Communication Protocol)

> 이 문서는 **현재 저장소의 실제 운영 코드**(DTO record, `MqttTopics`/`MqttProperties`,
> `MqttMessageRouter`, 각 Service, `VehicleWebSocketBroadcaster`/`AiCargoAnalysisBroadcaster`,
> 각 Controller)를 근거로 작성했다. prompt/answer/README의 과거 설계가 아니라 코드가 기준이다.
> 코드와 문서가 어긋나면 **코드가 옳고 이 문서를 고쳐야 한다.**
>
> 표기 규칙:
> - `미확정` : 코드에는 임시값이 있으나 팀(특히 ROS2=D, 프론트=F) 합의가 필요한 항목.
> - `외부 연동 확인 필요` : 실제 Mosquitto/ROS2/Isaac Sim/브라우저 없이는 저장소만으로 검증 불가.
> - 모든 JSON 시각 필드는 Jackson(`jackson-datatype-jsr310`) 기본 `LocalDateTime` 직렬화 =
>   **타임존 없는 ISO-8601**(예: `2026-07-23T11:20:27`). 타임존/오프셋 처리는 `미확정`.

---

## 0. 전송 계층 개요

```
ROS2 / Isaac Sim / 임베디드(REAL) / AI
        │  (MQTT publish)
        ▼
   MQTT Broker (Eclipse Mosquitto)          ← 외부 연동 확인 필요
        │  (구독)
        ▼
MqttPahoMessageDrivenChannelAdapter → mqttInputChannel
        → MqttMessageReceiver → MqttMessageRouter
        → (도메인별 DTO 역직렬화) → 도메인 Service
        → (선택적 DB 저장) + VehicleWebSocketBroadcaster/AiCargoAnalysisBroadcaster
        ▼
STOMP SimpleBroker (/topic) ── SockJS(/ws) ──► Frontend   ← 저장소에 프론트 없음(외부 연동 확인 필요)

Frontend/REST → Controller → Service → MqttPublisher → MqttGateway
        → mqttOutboundChannel → MqttPahoMessageHandler → forklift/{id}/command → ROS2/임베디드
```

- MQTT 접속·QoS 값은 `application-local.yml`의 `mqtt.*`를 `MqttProperties`로 바인딩(코드 하드코딩 없음).
- **기본 QoS = `mqtt.default-qos`(로컬 기본값 `1`)**. 구독 7토픽 모두 이 QoS로 등록되고, 발행도 이 QoS 사용.
- **retained**: 발행 시 `false`(Isaac/임베디드 명령 Publisher 공통). `미확정` — 토픽별 QoS/retained 정책은
  합의 문서에 명시되지 않음.
- STOMP: endpoint `/ws`(SockJS), 브로커 prefix `/topic`, 앱 prefix `/app`, 허용 Origin
  `websocket.allowed-origin-patterns`(로컬 기본 `*`, 운영은 좁혀야 함).

---

## 1. 전체 데이터 흐름 요약표 (실제 코드 기준)

| 영역 | 입력 토픽/API | 입력 DTO | 처리 Service | DB 저장 | 출력 토픽/WebSocket | 테스트 |
|---|---|---|---|---|---|---|
| ROS2 상태 | `forklift/+/status` (구독) | `ForkliftStatusMessage` | `ForkliftStatusService` → `VehicleStatusService` | `vehicle_current_status` upsert + `vehicle_status_history` insert | `/topic/vehicles/status`(+`/{id}`) `VEHICLE_STATUS_UPDATED` | ServiceTest(Mockito) + **StatusServiceIntegrationTest**/MapperTest(H2) |
| ROS2 위치 | `forklift/+/location` (구독, payload에 `vehicleId` 키) | `ForkliftLocationMessage` | `ForkliftLocationService` | 저장 안 함(중계만) | `/topic/vehicles/location`(+`/{id}`) `VEHICLE_LOCATION_UPDATED` | ForkliftLocationServiceTest(Mockito), LocationEventDataTest(H2 아님) |
| Isaac 상태 | `forklift/+/status` (구독, `forkHeight`/`hasCargo`/`footprint` 有 또는 `battery` 無) | `IsaacForkliftStatusMessage` | `IsaacForkliftStatusService` → `VehicleStatusService` | `vehicle_current_status` upsert + `vehicle_status_history` insert | `/topic/vehicles/status`(+`/{id}`) `VEHICLE_STATUS_UPDATED` | IsaacForkliftStatusServiceTest(Mockito) |
| Isaac 위치 | `forklift/+/location` (구독, payload에 `forkliftId` 키) | `IsaacForkliftLocationMessage` | `IsaacForkliftLocationService` | 저장 안 함(중계만) | `/topic/vehicles/location`(+`/{id}`) `VEHICLE_LOCATION_UPDATED` | IsaacForkliftLocationServiceTest(Mockito) |
| Isaac 경로 | `forklift/+/path` (구독) | `IsaacForkliftPathMessage` | `IsaacForkliftPathService` | 저장 안 함(중계만) | `/topic/vehicles/path`(+`/{id}`) `VEHICLE_PATH_UPDATED` | IsaacForkliftPathServiceTest(Mockito) |
| 임베디드 명령 | `POST /api/vehicles/{forkliftId}/embedded-commands` (REST) | `EmbeddedCommandRequest` → `EmbeddedForkliftCommandMessage` | `EmbeddedCommandService` → `EmbeddedForkliftCommandPublisher` | `embedded_vehicle_command` insert | (발행) `forklift/{id}/command` | EmbeddedCommandServiceTest(Mockito), **EmbeddedCommandIntegrationTest**(H2) |
| 임베디드 명령 결과 | `forklift/+/command-result` (구독) | `EmbeddedCommandResultMessage` | `EmbeddedCommandResultService` | `embedded_vehicle_command` update | `/topic/vehicles/result`(+`/{id}`) `VEHICLE_COMMAND_RESULT_UPDATED` | EmbeddedCommandResultServiceTest(Mockito) |
| 임베디드 포크 상태 | `forklift/+/fork-status` (구독) | `EmbeddedForkStatusMessage` | `EmbeddedForkStatusService` | `vehicle_fork_current_status` upsert | `/topic/vehicles/fork-status`(+`/{id}`) `VEHICLE_FORK_STATUS_UPDATED` | EmbeddedForkStatusServiceTest(Mockito), MapperTest(H2) |
| 임베디드 오류 | `forklift/+/error` (구독) | `EmbeddedErrorMessage` | `EmbeddedErrorService` | `embedded_error_history` insert | `/topic/vehicles/errors`(+`/{id}`) `VEHICLE_ERROR_OCCURRED` | EmbeddedErrorServiceTest(Mockito), MapperTest(H2) |
| AI 화물 분석 | `cargo/detected` (구독) | `AiCargoAnalysisMessage` | `AiCargoAnalysisService` | `ai_cargo_analysis` + `ai_cargo_detection_box` insert | `/topic/ai/cargo-analysis`(+`/{cargoId}`) | AiCargoAnalysisServiceTest(Mockito), **AiCargoAnalysisIntegrationTest**/MapperTest(H2) |

> **주의(테스트)**: "(Mockito)" 표기 서비스 단위 테스트는 현재 개발 PC의 JDK가 21이 아니라(JBR 25)
> Mockito inline mock 계측 실패로 실행되지 못한다(코드 결함 아님, 환경 문제). H2 통합/Mapper 테스트는
> 정상 통과한다. 상세는 `prompt/answer/answer13.md` 참고.

---

## 2. MQTT 토픽 목록 (코드에서 그대로 추출)

`MqttProperties.Topics` / `application-local.yml`의 `mqtt.topics.*` 기준.

| 토픽 패턴 | 방향 | 발행자(Publisher) | 구독자(Subscriber) | QoS | retained |
|---|---|---|---|---|---|
| `forklift/+/status` | 인바운드(구독) | ROS2 / Isaac Sim | 백엔드 `MqttMessageRouter.routeStatus` | 1 (`default-qos`) | — |
| `forklift/+/location` | 인바운드(구독) | ROS2 / Isaac Sim | `routeLocation` | 1 | — |
| `forklift/+/path` | 인바운드(구독) | Isaac Sim | `routePath` | 1 | — |
| `forklift/+/command-result` | 인바운드(구독) | 임베디드(REAL) | `routeCommandResult` | 1 | — |
| `forklift/+/fork-status` | 인바운드(구독) | 임베디드(REAL) | `routeForkStatus` | 1 | — |
| `forklift/+/error` | 인바운드(구독) | 임베디드(REAL) | `routeEmbeddedError` | 1 | — |
| `cargo/detected` | 인바운드(구독) | AI | `routeCargoDetected` | 1 | — |
| `forklift/%s/command` | 아웃바운드(발행) | 백엔드 `Isaac/EmbeddedForkliftCommandPublisher` | ROS2 / 임베디드 | 1 | `false` |
| `forklift/%s/emergency` | (정의만 존재) | — | — | — | — |

- `forklift/%s/emergency`는 `MqttTopics`/설정에 **정의는 되어 있으나 현재 이를 발행/구독하는 코드가 없다**
  (`외부 연동 확인 필요` 이전에, 백엔드 내부적으로도 미사용). 신규 사용 시 별도 합의 필요.
- 토픽의 `{id}`(=`+`) 세그먼트와 payload 안의 식별자(`forkliftId`/`vehicleId`)는 **반드시 일치**해야 한다.
  `MqttMessageRouter.isVehicleIdConsistentWithTopic`가 불일치 시 해당 메시지를 버리고 경고 로그만 남긴다.

### 2.1 같은 토픽의 ROS2 / Isaac 판별 규칙 (실제 Router 코드)

`forklift/+/status`와 `forklift/+/location`은 ROS2와 Isaac이 **같은 토픽 이름을 공유**한다. Router가
payload의 키로 구분한다:

- **status**: `forkHeight` 또는 `hasCargo` 또는 `footprint` 키가 있거나, **`battery` 키가 아예 없으면**
  → Isaac(`IsaacForkliftStatusMessage`). 그 외(`forkliftId`+`battery` 형태) → ROS2(`ForkliftStatusMessage`).
- **location**: `forkliftId` 키가 있으면 → Isaac(`IsaacForkliftLocationMessage`), `vehicleId` 키면 → ROS2
  (`ForkliftLocationMessage`).

> `미확정`/주의: ROS2 **상태** DTO(`ForkliftStatusMessage`)의 식별자 키는 `forkliftId`인데, ROS2 **위치**
> DTO(`ForkliftLocationMessage`)의 식별자 키는 `vehicleId`다. 코드는 이 상태대로 동작하지만, 실물 ROS2
> 규격에서 두 키 이름을 통일할지 여부는 팀 합의 필요.

---

## 3. 인바운드 메시지 규격 (JSON = 각 DTO record 필드)

JSON 키 = record 컴포넌트 이름(카멜케이스). 아래 타입/필수여부는 **DTO 정의 그대로**다. 값 검증(범위·필수)은
각 Service에서 수행하며, 위반 시 해당 메시지만 폐기(앱은 계속 동작)한다.

### 3.1 ROS2 상태 — `forklift/+/status` → `ForkliftStatusMessage`

```json
{ "forkliftId": "REAL-F01", "status": "ACTIVE", "battery": 87, "timestamp": "2026-07-23T11:20:27" }
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| forkliftId | string | 필수 | 토픽 `{id}`와 일치해야 함 |
| status | string | 필수 | `VehicleStatus.fromRaw`로 정규화(§6.1) |
| battery | int(primitive) | 필수 | 이 키의 존재가 ROS2 판별 근거. Service에서 0~100 검증 |
| timestamp | LocalDateTime | 필수 | messageAt으로 사용 |

처리: `VehicleStatusService.updateCurrentStatus`로 위임 → current_status upsert + status_history insert +
`broadcastStatus`. 미등록 차량이면 `VEHICLE_NOT_FOUND`를 잡아 경고 로그로 다운그레이드.

### 3.2 ROS2 위치 — `forklift/+/location` → `ForkliftLocationMessage`

```json
{
  "vehicleId": "REAL-F01", "status": "ACTIVE",
  "position": { "x": 1.2, "y": 3.4, "frameId": "map" },
  "heading": 90.0,
  "quaternion": { "x": 0.0, "y": 0.0, "z": 0.7071, "w": 0.7071 },
  "speed": 0.5, "messageAt": "2026-07-23T11:20:27"
}
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| vehicleId | string | 필수 | 없으면(=`forkliftId`면) Isaac 경로로 감 |
| status | string | 선택 | 위치 이벤트에도 상태를 함께 실어 보냄(중계용) |
| position.x / position.y | double | 필수 | NaN/Infinity면 메시지 폐기 |
| position.frameId | string | 선택 | 없으면 기본 `"map"` |
| heading | double | 선택 | 단위 degree/radian `미확정`. 코드상 [0,360) degree로 정규화 |
| quaternion.x/y/z/w | double | 선택(전부 or 전무) | 일부만 채우면 폐기. 네 값 모두 null이면 "없음" |
| speed | double | 선택 | 음수/비유한이면 폐기 |
| messageAt | LocalDateTime | 필수 | |

처리: `ForkliftLocationService` — **DB 저장 안 함**, `existsByVehicleId` 확인 후 `broadcastLocation`만.
(상태 저장은 상태 토픽만의 책임 — 위치/상태가 같은 행을 upsert하면 서로 값을 지우는 문제 방지.)

### 3.3 Isaac 상태 — `forklift/+/status` → `IsaacForkliftStatusMessage`

```json
{
  "forkliftId": "SIM-F01", "status": "ACTIVE", "battery": 80,
  "forkHeight": 0.35, "hasCargo": true, "cargoId": "C-100",
  "footprint": { "length": 1.2, "width": 0.8 },
  "timestamp": "2026-07-23T11:20:27"
}
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| forkliftId | string | 필수 | |
| status | string | 선택 | LWT OFFLINE 최소 메시지 가능(`battery` 키 없음이 Isaac 판별 근거 중 하나) |
| battery | Integer(nullable) | 선택 | |
| forkHeight | double | 선택 | Isaac 판별 키 |
| hasCargo | boolean | 선택 | Isaac 판별 키 |
| cargoId | string | 선택 | |
| footprint.length / footprint.width | double | 선택 | Isaac 판별 키 |
| timestamp | LocalDateTime | 필수 | |

처리: `IsaacForkliftStatusService` → `VehicleStatusService.updateCurrentStatus`(current+history 저장) +
`broadcastIsaacStatus`. `미확정`: forkHeight/hasCargo/cargoId/footprint를 담을 DB 컬럼이 현재
`vehicle_current_status`에 없어 **상태값·배터리만 저장되고 나머지는 WebSocket으로만 중계**된다.

### 3.4 Isaac 위치 — `forklift/+/location` → `IsaacForkliftLocationMessage`

```json
{ "forkliftId": "SIM-F01", "x": 1.2, "y": 3.4, "direction": 90.0, "speed": 0.5, "timestamp": "2026-07-23T11:20:27" }
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| forkliftId | string | 필수 | 이 키가 Isaac 판별 근거 |
| x / y | double | 선택 | 평면 좌표 |
| direction | double | 선택 | heading에 대응(단위 `미확정`) |
| speed | double | 선택 | |
| timestamp | LocalDateTime | 필수 | |

처리: `IsaacForkliftLocationService` — DB 저장 안 함, `broadcastIsaacLocation`만.

### 3.5 Isaac 경로 — `forklift/+/path` → `IsaacForkliftPathMessage`

```json
{
  "forkliftId": "SIM-F01",
  "waypoints": [ { "x": 1.0, "y": 2.0 }, { "x": 1.5, "y": 2.5 } ],
  "goal": { "x": 5.0, "y": 6.0, "direction": 180.0 },
  "timestamp": "2026-07-23T11:20:27"
}
```

처리: `IsaacForkliftPathService` — DB 저장 안 함, `broadcastPath`만. (ROS2엔 이 토픽 사용 사례 없어 판별 없이
곧바로 Isaac 처리.)

### 3.6 임베디드 명령 결과 — `forklift/+/command-result` → `EmbeddedCommandResultMessage`

```json
{
  "commandId": "CMD-001", "forkliftId": "REAL01", "command": "FORK_UP", "result": "SUCCESS",
  "forkState": "BOTTOM", "limitBottom": true, "emergencyStopApplied": false,
  "stoppedActions": ["DRIVE","FORK"], "requiresReset": false,
  "errorCode": null, "message": "완료", "completedAt": "2026-07-23T11:20:27"
}
```

| 필드 | 타입 | 비고 |
|---|---|---|
| commandId | string | 발행했던 명령의 ID와 매칭해 상태 전이 |
| forkliftId | string | 토픽 `{id}`와 일치 |
| command | string | `EmbeddedCommandType`(§6.2) |
| result | string | 성공/실패 결과 → `EmbeddedCommandStatus`(§6.3)로 반영 |
| forkState | string | `EmbeddedForkState`(§6.4) |
| limitBottom | boolean | 하강 리미트 스위치 |
| emergencyStopApplied | boolean | |
| stoppedActions | string[] | `EmbeddedStoppedAction`(§6.6) 값들 |
| requiresReset | boolean | E-STOP 리셋 필요 여부 |
| errorCode | string(nullable) | |
| message | string(nullable) | |
| completedAt | LocalDateTime | 이벤트 발생 시각 |

처리: `EmbeddedCommandResultService` → `embedded_vehicle_command` update + `broadcastEmbeddedCommandResult`.

### 3.7 임베디드 포크 상태 — `forklift/+/fork-status` → `EmbeddedForkStatusMessage`

```json
{ "forkliftId": "REAL01", "forkState": "MOVING_UP", "limitBottom": false, "errorCode": null, "timestamp": "2026-07-23T11:20:27" }
```

처리: `EmbeddedForkStatusService` → `vehicle_fork_current_status` upsert + `broadcastForkStatus`.

### 3.8 임베디드 오류 — `forklift/+/error` → `EmbeddedErrorMessage`

```json
{ "forkliftId": "REAL01", "errorCode": "E-DRV-01", "errorSource": "DRIVE", "severity": "CRITICAL", "message": "모터 과전류", "timestamp": "2026-07-23T11:20:27" }
```

| 필드 | 타입 | 비고 |
|---|---|---|
| forkliftId | string | |
| errorCode | string | |
| errorSource | string | `EmbeddedErrorSource`(§6.5) |
| severity | string | `EmbeddedErrorSeverity`(§6.7) |
| message | string | |
| timestamp | LocalDateTime | |

처리: `EmbeddedErrorService` → `embedded_error_history` insert + `broadcastEmbeddedError`.

### 3.9 AI 화물 분석 — `cargo/detected` → `AiCargoAnalysisMessage`

```json
{
  "schemaVersion": "1.0", "analysisId": "A-1001", "vehicleId": "SIM-F01", "cargoId": "C-100",
  "status": "OK",
  "detection": { "boxes": [ { "className": "pallet", "confidence": 0.98, "bboxPx": [10,20,100,200] } ] },
  "distance": { "valueCm": 123.4, "stdCm": 1.2 },
  "dimensions": { "widthCm": 80.0, "heightCm": 60.0, "depthCm": 120.0, "volumeCm3": 576000.0, "scale": "REAL" },
  "loadBalance": { "direction": ["LEFT","FRONT"], "message": "좌측 편중" },
  "ratios": { "horizontal": 0.6, "vertical": 0.4 },
  "message": null, "capturedAt": "2026-07-23T11:20:26", "processedAt": "2026-07-23T11:20:27"
}
```

| 필드 | 타입 | 비고 |
|---|---|---|
| schemaVersion | string | 지원하지 않으면 `AI_ANALYSIS_SCHEMA_VERSION_UNSUPPORTED` |
| analysisId | string | 중복 저장 방지(UNIQUE) |
| vehicleId / cargoId | string(nullable) | FK 없이 느슨한 문자열 |
| status | string | `AiAnalysisStatus`(§6.8): OK / NO_DETECTION / UNRELIABLE |
| detection.boxes[].className | string | |
| detection.boxes[].confidence | double | |
| detection.boxes[].bboxPx | int[] | [x, y, width, height] |
| distance.valueCm / stdCm | double | cm |
| dimensions.widthCm/heightCm/depthCm/volumeCm3 | double | cm / cm³ |
| dimensions.scale | string | `DimensionScale`(§6.9): REAL / MINIATURE |
| loadBalance.direction | string[] | `LoadBalanceDirection`(§6.10). DB엔 쉼표 join 문자열로 저장 |
| loadBalance.message | string | |
| ratios.horizontal / vertical | double | |
| capturedAt / processedAt | LocalDateTime | |

처리: `AiCargoAnalysisService.process` → `ai_cargo_analysis` + `ai_cargo_detection_box` insert(같은
트랜잭션) + `AiCargoAnalysisBroadcaster.broadcast`.

---

## 4. 아웃바운드(발행) 명령 규격

### 4.1 `forklift/{id}/command` — 임베디드/실물 명령 `EmbeddedForkliftCommandMessage`

REST `POST /api/vehicles/{forkliftId}/embedded-commands` (body `EmbeddedCommandRequest{command, reason}`)
→ `EmbeddedCommandService.issueCommand` → `EmbeddedForkliftCommandPublisher.publish`가 발행:

```json
{ "commandId": "CMD-001", "forkliftId": "REAL01", "command": "FORK_UP", "reason": "적재 준비", "timestamp": "2026-07-23T11:20:27" }
```

- `command`는 `EmbeddedCommandType.fromRaw`로 검증, 미지원 값이면 `EMBEDDED_COMMAND_TYPE_INVALID`.
- 저장: `embedded_vehicle_command` insert(PENDING) → 발행 성공 시 PUBLISHED, 실패 시 PUBLISH_FAILED(롤백 안 함).
- QoS = `default-qos`(1), retained = `false`.

### 4.2 `forklift/{id}/command` — Isaac 명령 `IsaacForkliftCommandMessage`

`IsaacForkliftCommandPublisher`가 같은 토픽 형식으로 발행(같은 `MqttPublisher`/`MqttTopics.forkliftCommand`
재사용). JSON:

```json
{ "forkliftId": "SIM-F01", "command": "MOVE", "destination": { "x": 5.0, "y": 6.0, "direction": 180.0 }, "timestamp": "2026-07-23T11:20:27" }
```

> `미확정`: 같은 `forklift/{id}/command` 토픽을 Isaac용/임베디드용이 **서로 다른 JSON 스키마**로 공유한다
> (Isaac=destination 좌표, 임베디드=commandId+reason). 실제 수신 측(ROS2 브리지/임베디드 펌웨어)이 이를
> 어떻게 구분할지는 `외부 연동 확인 필요`.

---

## 5. WebSocket(STOMP) 아웃바운드 규격

- **엔드포인트**: `/ws` (SockJS). 프론트는 `new SockJS("http(s)://<host>/ws")`로 연결(순수 WebSocket URL 아님).
- **브로커 prefix**: `/topic` (SimpleBroker). **앱 prefix**: `/app`.
- 모든 이벤트는 "전체 destination"과 "차량별 destination(`/{id}`)" **두 곳에 동시 전송**된다.
- 전송 실패는 Broadcaster가 흡수(로그만) — DB 트랜잭션 결과에 영향 없음.

### 5.1 차량 이벤트 봉투(envelope) — `VehicleWebSocketEvent<T>`

```json
{ "eventType": "VEHICLE_STATUS_UPDATED", "vehicleId": "REAL-F01", "occurredAt": "2026-07-23T11:20:27", "data": { /* T */ } }
```

| destination(전체 / 차량별) | eventType | data 페이로드 타입 | 출처 |
|---|---|---|---|
| `/topic/vehicles/status` / `…/{id}` | `VEHICLE_STATUS_UPDATED` | `VehicleStatusResponse`(ROS2) / `IsaacVehicleStatusEventData`(Isaac) | 상태 저장 후 |
| `/topic/vehicles/location` / `…/{id}` | `VEHICLE_LOCATION_UPDATED` | `VehicleLocationEventData`(ROS2) / `IsaacVehicleLocationEventData`(Isaac) | 위치 중계 |
| `/topic/vehicles/path` / `…/{id}` | `VEHICLE_PATH_UPDATED` | `VehiclePathEventData` | Isaac 경로 |
| `/topic/vehicles/result` / `…/{id}` | `VEHICLE_COMMAND_RESULT_UPDATED` | `EmbeddedCommandResultEventData` | 명령 결과 |
| `/topic/vehicles/fork-status` / `…/{id}` | `VEHICLE_FORK_STATUS_UPDATED` | `EmbeddedForkStatusEventData` | 포크 상태 |
| `/topic/vehicles/errors` / `…/{id}` | `VEHICLE_ERROR_OCCURRED` | `EmbeddedErrorEventData` | 임베디드 오류 |

> 참고: `VehicleWebSocketBroadcaster`에는 `broadcastCommandResult`(`VehicleCommandResultEventData`,
> `RESULT_ALL` 재사용)도 있으나 이를 호출하는 프로덕션 코드는 현재 없다(임베디드 결과는
> `broadcastEmbeddedCommandResult` 사용).

### 5.2 AI 분석 이벤트 — `AiCargoAnalysisBroadcaster`

- destination: `/topic/ai/cargo-analysis` (전체), `/topic/ai/cargo-analysis/{cargoId}` (cargoId 있을 때).
- payload: `AiCargoAnalysisResponse`(봉투 없이 그대로 전송).

> `미확정`/합의 필요(프론트=F): 차량 이벤트는 `VehicleWebSocketEvent` 봉투로 감싸는데 AI 이벤트는 봉투
> 없이 응답 객체를 그대로 보낸다 — 프론트 구독 규격 통일 여부.

---

## 6. Enum 값 (코드 정의 그대로)

### 6.1 VehicleStatus
`UNKNOWN, IDLE, ACTIVE, ERROR, OFFLINE` — `fromRaw`: null/빈값/미정의 → `UNKNOWN`, `"MOVING"` → `ACTIVE`
(대소문자 무시). `미확정`: 최종 상태 값 목록(MOVING/LOADING/UNLOADING/CHARGING/EMERGENCY_STOP 후보)은 합의 필요.

### 6.2 EmbeddedCommandType
`STOP, FORK_UP, FORK_DOWN, LOAD, UNLOAD, EMERGENCY_STOP, RESET_ESTOP`

### 6.3 EmbeddedCommandStatus
`PENDING, PUBLISHED, PUBLISH_FAILED, ACCEPTED, IN_PROGRESS, SUCCESS, FAILED, REJECTED, CANCELLED`

### 6.4 EmbeddedForkState
`MOVING_UP, MOVING_DOWN, STOPPED, BOTTOM, ERROR, UNKNOWN`

### 6.5 EmbeddedErrorSource
`DRIVE, STEERING, FORK, LIMIT_SWITCH, UART, SYSTEM`

### 6.6 EmbeddedStoppedAction
`DRIVE, STEERING, FORK`

### 6.7 EmbeddedErrorSeverity
`WARNING, ERROR, CRITICAL`

### 6.8 AiAnalysisStatus
`OK, NO_DETECTION, UNRELIABLE`

### 6.9 DimensionScale
`REAL, MINIATURE`

### 6.10 LoadBalanceDirection
`LEFT, RIGHT, FRONT, BACK`

---

## 7. REST API 요약 (통신 관련)

| Method | URL | 설명 | 활성 조건 |
|---|---|---|---|
| GET | `/api/health` | 헬스체크 | 항상 |
| POST | `/api/vehicles` | 차량 등록 | 항상 |
| GET | `/api/vehicles` | 활성 차량 목록 | 항상 |
| GET | `/api/vehicles/status-counts` | 상태별 집계 | 항상 |
| GET | `/api/vehicles/{vehicleId}` | 차량 상세 | 항상 |
| GET | `/api/vehicles/{vehicleId}/status-history?limit=` | 상태 이력(기본 50, 1~200) | 항상 |
| PUT | `/api/vehicles/{vehicleId}/status` | 상태 갱신(테스트용) | `vehicle.status-test-api.enabled`(로컬만 true) |
| POST | `/api/vehicles/{forkliftId}/embedded-commands` | 명령 발행 | 항상 |
| GET | `/api/vehicles/{forkliftId}/embedded-commands` / `/{commandId}` | 명령 조회 | 항상 |
| GET | `/api/vehicles/{forkliftId}/fork-status` | 포크 현재 상태 | 항상 |
| GET | `/api/vehicles/{forkliftId}/embedded-errors?limit=` | 오류 이력 | 항상 |
| GET | `/api/ai/cargo-analysis/{analysisId}` | 분석 단건 | 항상 |
| GET | `/api/cargos/{cargoId}/ai-analysis/latest` | 최신 분석 | 항상 |
| POST | `/api/mqtt/test` | MQTT 임시 발행(검증용) | `mqtt.test-api.enabled`(로컬만 true) |

공통 응답 형식: `{ "success": bool, "data": {...}|null, "error": { "code", "message" }|null }`.

---

## 8. 팀 합의 필요(미확정) 및 외부 연동 확인 필요 정리

**미확정 (팀 합의 필요, 특히 D=ROS2 / F=프론트)**
1. 토픽별 QoS/retained 정책(현재 전부 `default-qos=1`, 명령 retained=false 임시).
2. 위치 `heading`/Isaac `direction`의 단위(degree/radian)와 좌표계/frameId 표준.
3. ROS2 상태 식별자 키 `forkliftId` vs ROS2 위치 식별자 키 `vehicleId` 불일치 통일 여부.
4. `VehicleStatus` 최종 상태 값 목록.
5. Isaac 상태의 forkHeight/hasCargo/cargoId/footprint DB 저장 여부(현재 미저장, 중계만).
6. `forklift/{id}/command` 토픽을 Isaac/임베디드가 서로 다른 JSON으로 공유하는 방식.
7. WebSocket payload 봉투 통일(차량=`VehicleWebSocketEvent` vs AI=응답 객체 그대로).
8. LocalDateTime 타임존/오프셋 처리 규칙.
9. `forklift/%s/emergency` 토픽의 실제 사용 주체·JSON 규격(현재 미사용).

**외부 연동 확인 필요 (저장소만으로 검증 불가)**
- 실제 Mosquitto Broker 송수신·자동 재연결, 로컬/EC2 포트 연결.
- 실제 ROS2/Isaac Sim의 발행 JSON이 위 DTO와 정확히 일치하는지.
- 임베디드(REAL) 펌웨어가 `forklift/{id}/command` 임베디드 스키마를 수신·해석하는지.
- 프론트엔드(React, 현재 저장소에 없음)의 STOMP 구독·수신.

---

## Measurement Station v1.0

> 이 절은 실제 구현된 스테이션 도메인 코드(`com.fast.backend.station.*`)를 근거로 작성했다.

### 아키텍처 배경

- **MR !36** 기준. 제안자: **방지섭**. 스테이션 서비스는 **FR-101-5**.
- 측정 **추론·판정은 스테이션 PC**에서 수행한다(거리/치수/편하중 계산 완료).
- **EC2 백엔드는 측정 결과만 수신**해 검증·저장·중계한다(추론하지 않음).
- 기존 `cargo/detected`(AI 화물 분석) 흐름은 그대로 유지하고, 스테이션 전용 규격은 **별도 DTO
  (`StationMeasurementMessage`) + Adapter(`StationMeasurementAdapter`) + Service/Mapper/테이블**로 완전히
  분리했다 — 두 규격을 섞지 않는다.

### MQTT 토픽

| 항목 | 값 |
|---|---|
| 토픽 | `fast/station/{station_id}/measurement` (구독 패턴 `fast/station/+/measurement`) |
| 방향 | 인바운드(백엔드가 구독) |
| Publisher | 측정 스테이션 PC |
| Subscriber | 백엔드 `MqttMessageRouter.routeStationMeasurement` → `StationMeasurementService.process` |
| QoS | `mqtt.default-qos`(로컬 기본 1) — `미확정`(최종값 합의 필요) |
| retained | `false`(기본) — `미확정` |
| station_id 검증 | 토픽 `{station_id}`와 payload `station_id` 불일치 시 메시지 폐기 + 경고 로그, 앱은 계속 동작 |

### 전체 JSON 예시 (수신, snake_case)

```json
{
  "schema_version": "1.0",
  "measurement_id": "st1-20260722-130501-0007",
  "station_id": "station-1",
  "measured_at": "2026-07-22T13:05:01+09:00",
  "status": "ok",
  "detection": {
    "box_count": 1,
    "boxes": [ { "bbox_px": [412, 180, 350, 310], "score": 0.97 } ],
    "pallet": { "bbox_px": [380, 460, 520, 140], "score": 0.99 }
  },
  "distance": { "front_cm": 152.3, "std_cm": 0.42, "frames_used": 48 },
  "dimensions": {
    "height_cm": 30.2, "width_cm": 34.1, "depth_cm": null,
    "miniature_scale": 10, "miniature_height_mm": 30.2, "miniature_width_mm": 34.1
  },
  "load_balance": {
    "eccentric": true, "direction": ["right"],
    "ratio_x": 0.40, "ratio_y": 0.02, "magnitude": 0.40, "threshold": 0.3,
    "message": "오른쪽 편하중 (치우침 0.40 > 0.3)"
  }
}
```

> **snake_case 수신**: `StationMeasurementMessage`의 각 필드에 `@JsonProperty("snake_case")`를 명시해
> 정확히 수신한다(전역 naming 전략을 바꾸지 않아 다른 도메인 DTO에 영향 없음).

### 필드 정의

| 필드(snake) | DTO 필드(camel) | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| schema_version | schemaVersion | String | 필수 | `"1.0"`만 허용 |
| measurement_id | measurementId | String | 필수 | UNIQUE, 공백 불가 |
| station_id | stationId | String | 필수 | 토픽 {station_id}와 일치 |
| measured_at | measuredAt | **OffsetDateTime** | 필수 | `+09:00` 오프셋 보존(아래 저장 방식) |
| status | status | String→enum | 필수 | ok / no_detection / unreliable |
| detection.box_count | boxCount | Integer | ok 필수 | boxes 개수와 일치해야 함 |
| detection.boxes[].bbox_px | bboxPx | List\<Integer\> | 선택(nullable) | 존재 시 정확히 4개([x,y,w,h]), 각 ≥ 0 |
| detection.boxes[].score | score | Double | 선택 | 0.0~1.0 |
| detection.pallet.bbox_px | pallet.bboxPx | List\<Integer\> | 선택(nullable) | 존재 시 4개, 각 ≥ 0 |
| detection.pallet.score | pallet.score | Double | 선택 | 0.0~1.0 |
| distance.front_cm | frontCm | Double | ok 필수 | > 0 |
| distance.std_cm | stdCm | Double | ok 필수 | ≥ 0 |
| distance.frames_used | framesUsed | Integer | ok 필수 | > 0 |
| dimensions.height_cm | heightCm | Double | ok 필수 | > 0 |
| dimensions.width_cm | widthCm | Double | ok 필수 | > 0 |
| dimensions.depth_cm | depthCm | Double | 항상 null | 값이 오면 거부 |
| dimensions.miniature_scale | miniatureScale | Integer | ok 필수 | 반드시 10 |
| dimensions.miniature_height_mm | miniatureHeightMm | Double | ok 필수 | height_cm과 수치 동일(±0.001) |
| dimensions.miniature_width_mm | miniatureWidthMm | Double | ok 필수 | width_cm과 수치 동일(±0.001) |
| load_balance.eccentric | eccentric | Boolean | ok 필수 | max(abs(ratio_x),abs(ratio_y)) > threshold와 일치 |
| load_balance.direction | direction | List\<String\>→enum | ok 필수 | 0~2개, 중복 금지, left/right/front/back |
| load_balance.ratio_x | ratioX | Double | ok 필수 | finite |
| load_balance.ratio_y | ratioY | Double | ok 필수 | finite |
| load_balance.magnitude | magnitude | Double | ok 필수 | max(abs(ratio_x),abs(ratio_y))와 일치(±0.001), ≥ 0 |
| load_balance.threshold | threshold | Double | ok 필수 | ≥ 0 |
| load_balance.message | message | String | 선택 | 표시용, 검증 안 함 |

### 정책

- **status 정책**: `ok`이면 detection/distance/dimensions/load_balance가 모두 필요. `no_detection`/
  `unreliable`이면 **dimensions·load_balance는 반드시 null**(강제). detection·distance는 **선택**으로
  구현했다(제안 규격이 이 경우를 명시하지 않아 모호 → 선택 처리, 이 문서에 명시).
- **depth_cm 항상 null**(2026-07-22 확정): 정면 단일 카메라로 깊이 측정 불가, 적재 단위가 파렛트(T-11)라
  불필요. 값이 오면 거부한다.
- **miniature 계산**: 실물 cm ÷ 10 = miniature cm, 이를 mm로 환산하면 원 실물 cm 수치와 같다
  (예: 30.2cm ÷10 = 3.02cm = 30.2mm). 따라서 `miniature_height_mm == height_cm`,
  `miniature_width_mm == width_cm`(허용 오차 0.001), `miniature_scale == 10`.
- **direction enum**: left/right/front/back. 대각선이면 최대 2개. 중복 금지. (두 방향이 서로 수직이어야
  한다는 제약은 두지 않았다 — `미확정`/`F 담당 합의 필요`.)
- **ratio_x/y**: 파렛트 중심 대비 화물 중심 밀림. 화물 반폭 기준 정규화(0=중앙, ±1=반폭 이동). 부호 유지.
- **eccentric/magnitude/threshold**: `magnitude = max(abs(ratio_x), abs(ratio_y))`,
  `eccentric = (magnitude > threshold)`. 값이 이 규칙과 어긋나면 거부(스테이션이 계산해 보낸 값의 자기정합성 검증).
- **bbox_px nullable**: 관제 오버레이 미사용 시 생략 가능. 존재하면 정수 4개, 각 ≥ 0.
- **pallet 의미**: detection box와 별도 객체로 수신·저장한다(적재 파렛트). 일반 box로 합치지 않고 부모
  테이블의 단일 컬럼 세트(`pallet_*`)로 보존한다.

### OffsetDateTime 저장 방식

MySQL/H2 공용 DATETIME은 타임존을 담지 못하므로, `measured_at`을 **UTC 변환 시각(`measured_at_utc`)과
오프셋 분(`measured_at_offset_minutes`, 예: `+09:00` → 540)** 으로 분리 저장한다. 응답 시 두 값으로 원래
`OffsetDateTime`을 손실 없이 복원한다(`+09:00`이 그대로 응답됨). 전역 Jackson 설정
`spring.jackson.deserialization.adjust-dates-to-context-time-zone=false`로 수신 시 오프셋이 UTC로
변환되지 않게 한다.

### DB 저장 테이블

- `station_measurement`(부모, 1건) + `station_measurement_box`(자식 detection box, 1:N). pallet은 부모의
  `pallet_*` 컬럼에 보존. 상세는 `docs/backend-db/database-schema.md` 참고.

### WebSocket destination

| destination | eventType | payload |
|---|---|---|
| `/topic/stations/measurements`(전체) | `STATION_MEASUREMENT_COMPLETED` | `StationMeasurementEvent`(envelope) |
| `/topic/stations/{stationId}/measurements`(스테이션별) | 동일 | 동일 |

envelope: `{ "eventType", "stationId", "occurredAt", "data": StationMeasurementResponse }`. 기존
`/topic/ai/cargo-analysis`를 재사용하지 않고 스테이션 전용 도메인 경로를 쓴다.

### 조회 REST API

| Method | URL | 설명 | 오류 |
|---|---|---|---|
| GET | `/api/stations/measurements/{measurementId}` | measurement 단건 | 없음 → 404 `STATION_MEASUREMENT_NOT_FOUND` |
| GET | `/api/stations/{stationId}/measurements/latest` | 스테이션 최신(measured_at 기준) | 없음 → 404 |

수신용 HTTP POST는 이번 범위에서 **미구현**(MQTT 기본). 목록 API도 이번 범위 밖(후속 항목).

### 중복 measurement_id 정책

DB UNIQUE + Service 사전 확인(`existsByMeasurementId`)으로 이중 방어. 동일 measurement_id 재수신 시
**기존 저장값을 덮어쓰지 않고 무시**(경고 로그 후 정상 종료).

### 오류 처리

검증 실패는 `STATION_MEASUREMENT_*` ErrorCode(기존 예외 체계)로 표현하고, `process()` 내부에서 잡아
경고 로그만 남긴 뒤 **해당 메시지만 폐기**한다(MQTT Receiver 전체는 계속 동작). 부모 insert 이후 자식
insert 실패 등 예상치 못한 예외는 `@Transactional`로 함께 롤백된다.

### `cargo/detected`(AI 화물 분석) 규격과의 차이

| 항목 | cargo/detected (AI) | fast/station (측정 스테이션) |
|---|---|---|
| 토픽 | `cargo/detected` | `fast/station/{station_id}/measurement` |
| JSON 키 | camelCase | **snake_case**(@JsonProperty) |
| 시각 | `capturedAt`/`processedAt` LocalDateTime | `measured_at` **OffsetDateTime**(+09:00 보존) |
| 식별자 | analysisId | measurementId + **station_id** |
| 상태 값 | ok/no_detection/unreliable(동일) | ok/no_detection/unreliable |
| pallet | 개념 없음(일반 박스) | **별도 객체·별도 저장** |
| 치수 scale | `scale`(REAL/MINIATURE) | `miniature_scale`=10 + mm 필드 + 자기정합성 검증 |
| 편하중 | direction+message, ratios(별도) | eccentric/direction/ratio_x·y/magnitude/threshold |
| depth | 허용(값 가능) | **항상 null 강제** |
| 저장 테이블 | ai_cargo_analysis / ai_cargo_detection_box | station_measurement / station_measurement_box |
| WebSocket | `/topic/ai/cargo-analysis` | `/topic/stations/measurements` |

### 외부 연동 확인 필요 / 합의 필요

- `외부 연동 확인 필요`: 실제 스테이션 PC의 MQTT 발행, Mosquitto Broker 송수신, 프론트 STOMP 구독·표시.
- 합의 필요(특히 D=ROS2 아님, **F=프론트 / AI 담당**):
  1. direction enum이 프론트 표시 로직과 일치하는지(수직 2개 제약 여부 포함)
  2. bbox_px를 최종적으로 유지할지(관제 오버레이 사용 여부)
  3. QoS/retained 최종값
  4. HTTP POST 대안 도입 여부 — 이번 구현은 MQTT만, HTTP POST는 **대안 검토 가능**으로만 남김

---

_기준 커밋/코드 스냅샷: 이 문서는 현재 워킹트리의 운영 코드를 근거로 작성됨. DTO/토픽/destination이 코드에서
바뀌면 이 문서도 함께 갱신할 것._
