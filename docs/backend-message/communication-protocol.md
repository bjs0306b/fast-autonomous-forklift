# F.A.S.T. 통신 규격 (Communication Protocol)

> 이 문서는 **현재 저장소의 실제 운영 코드**(DTO record, `MqttTopics`/`MqttProperties`,
> `MqttMessageRouter`, 각 Service, `VehicleWebSocketBroadcaster`/`AiCargoAnalysisBroadcaster`/
> `StationMeasurementBroadcaster`, 각 Controller)를 근거로 작성했다.
> 코드와 문서가 어긋나면 **코드가 옳고 이 문서를 고쳐야 한다.**
>
> **2026-07-24 갱신**: prompt32.md의 팀 확정 통신 규격을 코드에 반영하면서 이 문서를 함께 갱신했다.
> 이전 버전에 있던 `미확정` 항목 9개 중 8개가 확정되어 표기를 제거했다(남은 항목은 §8 참고).
>
> 표기 규칙:
> - `외부 연동 확인 필요` : 실제 Mosquitto/ROS2/Isaac Sim/브라우저 없이는 저장소만으로 검증 불가.
> - `팀 확인 필요` : 확정 규격 문서가 명시하지 않아 이 저장소가 판단해 구현한 항목.
> - 모든 통신 시각은 **Asia/Seoul(+09:00) ISO-8601 `OffsetDateTime`** (예: `2026-07-23T11:20:27+09:00`).

---


> ## ⚠️ station measurement 는 MQTT 를 쓰지 않는다 (prompt95·96)
>
> `fast/station/{station_id}/measurement` 토픽의 **구독·라우팅·수신 DTO·MQTT 전용 검증이 모두 제거**됐고
> 설정 키 `mqtt.topics.station-measurement` 도 없어졌다. 이 문서에서 그 토픽을 "현재 규격"으로 서술하는
> 부분은 **폐기된 내용**이다.
>
> 현재 수신 경로는 REST 하나뿐이다.
>
> | 동작 | API | 성공 |
> |---|---|---|
> | 세션 시작 | `POST /api/stations/sessions?cargoId=...` | 201 |
> | 측정 등록 | `POST /api/stations/measurements` | 201 |
> | 세션 종료 | `DELETE /api/stations/sessions/{sessionId}` | 200 |
> | 활성 세션 조회 | `GET /api/stations/sessions/active` | 200 |
> | 세션 최신 측정 | `GET /api/stations/sessions/{sessionId}/measurements/latest` | 200 |
>
> 요청 본문(camelCase, `sessionId`·`stationId` 는 보내지 않는다):
>
> ```json
> { "sessionId": "<세션 생성 응답의 sessionId>",
>   "measurementId": "station-1-20260731-093748-0001", "status": "ok",
>   "cargoHeight": 0.723, "tippingLevel": "safe", "overhangRatio": 0.057,
>   "measuredAt": "2026-07-31T09:37:48+09:00" }
> ```
>
> - **`sessionId` 필수(2026-07-31).** 활성 세션과 다르면 409 `STATION_SESSION_MISMATCH`,
>   활성 세션이 없으면 409 `STATION_SESSION_NOT_ACTIVE`, 누락·공백이면 400.
>   재전송에서도 **원래 값을 유지**한다(새 세션 값으로 바꾸면 늦은 측정이 새 세션에 귀속된다).
>
> - `cargoHeight` 는 **meter, 화물만**(팔레트 제외). cm → m 변환은 측정 데스크탑 책임이며 백엔드는
>   단위를 추측해 변환하지 않는다. 팔레트 높이는 `storage.placement.pallet-height-m`(0.12)을
>   `PlacementService` 가 **정확히 한 번** 더한다.
> - `tippingLevel` 은 소문자 입력을 받아 **대문자로 정규화해 저장**한다.
> - `status` 는 `ok` / `dimensions_only` / `no_detection` / `unreliable`.
> - `sessionId` 는 필수이며 현재 활성 세션과 일치해야 한다(불일치 시 409 `STATION_SESSION_MISMATCH`).
> - **동시에 활성 세션은 1개**, **세션당 최종 측정 결과는 1건**, **측정 결과 저장 전에는 세션 종료 불가**.
>   측정 결과가 저장되면 status 와 무관하게 세션을 종료할 수 있다.
> - 적재 추천은 `status=OK` + 높이 유효 + `SAFE` + `overhangRatio < 0.05` 를 **모두** 만족할 때만 실행된다.
>
> 상세는 `docs/backend-api/optimal-placement.md` §6-bis 참고.
> (차량 위치 MQTT `forklift/+/location` 은 그대로 유지된다.)


## 0. 전송 계층 개요

```
ROS2 / Isaac Sim / 임베디드(REAL) / AI / 측정 스테이션
        │  (MQTT publish)
        ▼
   MQTT Broker (Eclipse Mosquitto)          ← 외부 연동 확인 필요
        │  (구독)
        ▼
MqttPahoMessageDrivenChannelAdapter → mqttInputChannel
        → MqttMessageReceiver → MqttMessageRouter
        → (도메인별 DTO 역직렬화) → 도메인 Service
        → (선택적 DB 저장) + Broadcaster
        ▼
STOMP SimpleBroker (/topic) ── SockJS(/ws) ──► Frontend   ← 저장소에 프론트 없음(외부 연동 확인 필요)

Frontend/REST → VehicleCommandController → VehicleCommandService → VehicleCommandPublisher
        → MqttPublisher → MqttGateway → mqttOutboundChannel → MqttPahoMessageHandler
        → forklift/{vehicleId}/command → ROS2 / 임베디드
```

- MQTT 접속값은 `application-local.yml`의 `mqtt.*`를 `MqttProperties`로 바인딩(코드 하드코딩 없음).
- **명령·명령 결과 QoS = 1, retained = false** (확정). 명령 QoS/retained는 안전 관련 계약이라
  설정값이 아니라 `VehicleCommandPublisher`의 상수(`COMMAND_QOS`, `COMMAND_RETAINED`)로 고정했다.
- **상태·위치·오류 등 인바운드 구독 QoS = 1** (`mqtt.default-qos`, 로컬 기본 1).
- `MqttMessageReceiver`는 전체 payload를 로그에 남기지 않고 topic/QoS/retained/byte 길이만 기록한다.
  `MqttMessageRouter`는 null·blank·JSON object가 아닌 payload와 잘못된 JSON을 해당 메시지 단위로
  폐기하며, 예상하지 못한 RuntimeException도 Receiver까지 이중으로 격리한다.
- 명령 gateway 호출 실패는 `MqttPublishException`으로 변환되어 `VehicleCommandService`가
  `PUBLISH_FAILED`로 기록한다. gateway 호출 성공(`PUBLISHED`)은 broker delivery 또는 차량 실행 성공을
  의미하지 않는다.
- STOMP: endpoint `/ws`(SockJS), 브로커 prefix `/topic`, 앱 prefix `/app`, 허용 Origin
  `websocket.allowed-origin-patterns`(로컬 기본 `*`, 운영은 반드시 좁혀야 함).

---

## 1. 전체 데이터 흐름 요약표 (실제 코드 기준)

| 영역 | 입력 토픽/API | 입력 DTO | 처리 Service | DB 저장 | 출력 destination | 테스트 |
|---|---|---|---|---|---|---|
| ROS2 상태 | `forklift/+/status` (구독) | `ForkliftStatusMessage` | `ForkliftStatusService` → `VehicleStatusService` | `vehicle_current_status` upsert + `vehicle_status_history` insert | `/topic/vehicles/status`(+`/{id}`) | ServiceTest + StatusServiceIntegrationTest/MapperTest(H2) |
| ROS2 위치 | `forklift/+/location` (payload에 `vehicleId` 키) | `ForkliftLocationMessage` | `ForkliftLocationService` | 저장 안 함(중계만) | `/topic/vehicles/location`(+`/{id}`) | ForkliftLocationServiceTest, **ForkliftLocationFrameIdTest** |
| Isaac 상태 | `forklift/+/status` (`forkHeight`/`hasCargo`/`footprint` 有 또는 `battery` 無) | `IsaacForkliftStatusMessage` | `IsaacForkliftStatusService` → `VehicleStatusService` | current + history (**Isaac 확장 5필드 포함**) | `/topic/vehicles/status`(+`/{id}`) | IsaacForkliftStatusServiceTest, **VehicleStatusIsaacExtrasIntegrationTest**(H2) |
| Isaac 위치 | `forklift/+/location` (payload에 `forkliftId` 키) | `IsaacForkliftLocationMessage` | `IsaacForkliftLocationService` | 저장 안 함(중계만) | `/topic/vehicles/location`(+`/{id}`) | IsaacForkliftLocationServiceTest |
| Isaac 경로 | `forklift/+/path` (구독) | `IsaacForkliftPathMessage` | `IsaacForkliftPathService` | 저장 안 함(중계만) | `/topic/vehicles/path`(+`/{id}`) | IsaacForkliftPathServiceTest |
| **통합 명령** | `POST /api/vehicles/{vehicleId}/commands` (REST) | `VehicleCommandRequest` → `VehicleCommandMessage` | `VehicleCommandService` → `VehicleCommandPublisher` | `embedded_vehicle_command` insert | (발행) `forklift/{vehicleId}/command` | VehicleCommandServiceTest, **VehicleCommandIntegrationTest**(H2) |
| **통합 명령 결과** | `forklift/+/command-result` (구독) | `VehicleCommandResultMessage` | `VehicleCommandResultService` | `embedded_vehicle_command` update | `/topic/vehicles/result`(+`/{id}`) | VehicleCommandResultServiceTest |
| 임베디드 포크 상태 | `forklift/+/fork-status` (구독) | `EmbeddedForkStatusMessage` | `EmbeddedForkStatusService` | `vehicle_fork_current_status` upsert | `/topic/vehicles/fork-status`(+`/{id}`) | EmbeddedForkStatusServiceTest, MapperTest(H2) |
| 임베디드 오류 | `forklift/+/error` (구독) | `EmbeddedErrorMessage` | `EmbeddedErrorService` | `embedded_error_history` insert | `/topic/vehicles/errors`(+`/{id}`) | EmbeddedErrorServiceTest, MapperTest(H2) |
| AI 화물 분석 | `cargo/detected` (구독) | `AiCargoAnalysisMessage` | `AiCargoAnalysisService` | `ai_cargo_analysis` + `ai_cargo_detection_box` | `/topic/ai/cargo-analysis`(+`/{cargoId}`) | AiCargoAnalysisServiceTest, IntegrationTest(H2) |
| 측정 스테이션 | **MQTT 아님 — `POST /api/stations/measurements`(REST)** | `StationMeasurementCreateRequest` | `StationMeasurementService.create` | `station_measurement` | `/topic/stations/measurements`, `/topic/stations/{sessionId}/measurements` | StationMeasurementControllerIntegrationTest(H2), StationMeasurementServiceTest, StationMeasurementMapperTest |
| **적재 화물 안전** | `forklift/+/load-safety` (구독) | `LoadSafetyMessage` | `LoadSafetyService` | `vehicle_load_safety` upsert(차량당 1행) | `/topic/vehicles/load-safety`(+`/{id}`) | LoadSafetyServiceIntegrationTest(H2), LoadSafetyRiskLevelTest |

---

## 2. MQTT 토픽 목록 (코드에서 그대로 추출)

`MqttProperties.Topics` / `application-local.yml`의 `mqtt.topics.*` 기준. **2026-07-24 재점검**
(prompt33.md) — 아래 표는 QoS는 항상 **MQTT QoS**(0/1/2 정수, iOS와 무관)로 표기하고, 코드에서
확인할 수 없는 값은 추측하지 않고 "확인 불가"로 표시한다.

| 토픽 패턴 | 방향 | 발행자 | 구독자 | Payload DTO | 발행 MQTT QoS | 구독 MQTT QoS | retained | 설정 위치 | 코드 위치 |
|---|---|---|---|---|---:|---:|---|---|---|
| `forklift/+/status` | ROS2/Isaac → Backend | ROS2 / Isaac Sim | `MqttMessageRouter.routeStatus` | `ForkliftStatusMessage` / `IsaacForkliftStatusMessage` | 확인 불가(외부 발행자) | **1** | 구독 전용(발행자가 결정, 백엔드는 확인 불가) | `mqtt.default-qos` | `MqttConfig.mqttInboundAdapter` |
| `forklift/+/location` | ROS2/Isaac → Backend | ROS2 / Isaac Sim | `routeLocation` | `ForkliftLocationMessage` / `IsaacForkliftLocationMessage` | 확인 불가 | **1** | 구독 전용 | 동일 | 동일 |
| `forklift/+/path` | Isaac → Backend | Isaac Sim | `routePath` | `IsaacForkliftPathMessage` | 확인 불가 | **1** | 구독 전용 | 동일 | 동일 |
| `forklift/{vehicleId}/command` | Backend → ROS2/Embedded | 백엔드 `VehicleCommandPublisher` | ROS2 **및** 임베디드 | `VehicleCommandMessage` | **1**(코드 상수) | 해당 없음(발행 전용) | **false**(코드 상수) | 코드 상수(설정값 미참조) | `VehicleCommandPublisher.COMMAND_QOS/COMMAND_RETAINED` |
| `forklift/+/command-result` | ROS2/Embedded → Backend | ROS2 / 임베디드 | `routeCommandResult` | `VehicleCommandResultMessage` | 확인 불가 | **1** | 구독 전용 | `mqtt.default-qos` | `MqttConfig` |
| `forklift/+/fork-status` | Embedded → Backend | 임베디드(REAL) | `routeForkStatus` | `EmbeddedForkStatusMessage` | 확인 불가 | **1** | 구독 전용 | 동일 | 동일 |
| `forklift/+/error` | Embedded → Backend | 임베디드(REAL) | `routeEmbeddedError` | `EmbeddedErrorMessage` | 확인 불가 | **1** | 구독 전용 | 동일 | 동일 |
| `cargo/detected` | AI → Backend | AI | `routeCargoDetected` | `AiCargoAnalysisMessage` | 확인 불가 | **1** | 구독 전용 | 동일 | 동일 |
| `forklift/+/load-safety` | Vision/Sensor → Backend | 비전·센서 노드 | `routeLoadSafety` | `LoadSafetyMessage` | 확인 불가 | **1** | 구독 전용 | 동일 | 동일 |

### 백엔드가 실제로 보장하는 범위 vs 외부 연동 확인 필요

| 구분 | 내용 |
|---|---|
| **① 백엔드 발행 시 보장** | `forklift/{vehicleId}/command` 발행은 **MQTT QoS 1, retained false**를 코드 상수로 고정 보장한다(`VehicleCommandPublisherTest`로 회귀 검증). |
| **② 백엔드 구독 시 사용** | **9개** 인바운드 토픽 전부 `mqtt.default-qos`(현재 1) 하나를 균등 적용한다(`MqttConfigTest`로 회귀 검증 — `forklift/+/load-safety` 추가로 8 → 9개). retained는 구독자가 정하는 값이 아니라 발행자가 정하므로 백엔드가 보장할 수 없다. |
| **③ 외부 발행 측이 맞춰야 함(백엔드 코드만으로 보장 불가)** | ROS2/Isaac/임베디드가 상태·위치·경로·명령 결과를 실제로 MQTT QoS 1로 발행하는지, AI가 `cargo/detected`를 QoS 1·retained false로 발행하는지 — **외부 연동 확인 필요**. (측정 스테이션은 MQTT를 쓰지 않는다 — REST로 전환됐다, §Measurement Station v2.0) `forklift/+/load-safety`는 **발행 주체 자체가 아직 없다**(§8.3). |

**QoS/retained의 실제 실행 시 적용값(코드 근거)**: `MqttConfig.mqttOutboundHandler()`는
`handler.setDefaultQos(mqttProperties.defaultQos())` / `handler.setDefaultRetained(false)`로 **폴백 기본값**을
설정하지만, Spring Integration MQTT의 `DefaultPahoMessageConverter.fromMessage()`(spring-integration-mqtt
6.3.4 소스로 직접 확인)는 메시지에 `MqttHeaders.QOS`/`MqttHeaders.RETAINED` 헤더가 있으면 **그 헤더값을
우선** 쓰고, 헤더가 없을 때만 이 폴백을 쓴다. `MqttGateway.publish(...)`는 두 헤더를 모든 호출에 강제하므로
(`@Header(MqttHeaders.QOS)`/`@Header(MqttHeaders.RETAINED)`), 현재 코드의 유일한 발행 경로인
`VehicleCommandPublisher`(QoS 1/retained false)와 검증용 `MqttTestController`(호출자 지정값)는 **항상
헤더값이 그대로 적용**된다 — `mqttOutboundHandler()`의 설정값 기반 폴백은 지금 코드에서 도달 불가능하다.

- **`forklift/{id}/emergency` 토픽은 제거됐다.** 확정 규격(1장 7번)에 따라 비상 정지도 공통 command
  토픽을 쓴다. 제거 전 영향도를 확인한 결과 이 토픽을 발행·구독하는 프로덕션 코드가 하나도 없어
  (설정과 `MqttTopics`에 정의만 존재) 안전하게 삭제할 수 있었다.
- **명령 토픽이 하나로 통합됐다.** 이전에는 같은 토픽을 Isaac용/임베디드용 두 DTO가 서로 다른 스키마로
  공유해 수신 측이 구분할 수 없었다(구 문서 §4.2의 `미확정`). 이제 하나의 envelope에
  `targetSystem`/`commandCategory`가 실려 수신 측이 payload를 깊게 파싱하기 전에 분기할 수 있다.
- 토픽의 `{id}`(=`+`) 세그먼트와 payload 안의 식별자는 **반드시 일치**해야 한다.
  `MqttMessageRouter.isVehicleIdConsistentWithTopic`가 불일치 시 메시지를 버리고 경고 로그만 남긴다.

### 2.1 같은 토픽의 ROS2 / Isaac 판별 규칙 (실제 Router 코드)

`forklift/+/status`와 `forklift/+/location`은 ROS2와 Isaac이 **같은 토픽 이름을 공유**한다.
확정 규격(1장 2번)은 **이 판별 구조를 그대로 유지**하기로 했다 — 상태·위치 메시지의 식별자 키를 임의로
통일하지 않는다.

- **status**: `forkHeight` 또는 `hasCargo` 또는 `footprint` 키가 있거나, **`battery` 키가 아예 없으면**
  → Isaac(`IsaacForkliftStatusMessage`). 그 외 → ROS2(`ForkliftStatusMessage`).
- **location**: `forkliftId` 키가 있으면 → Isaac, `vehicleId` 키면 → ROS2.

| 메시지 | 식별자 키 | 확정 |
|---|---|---|
| ROS2 상태 | `forkliftId` | 유지 |
| ROS2 위치 | `vehicleId` | 유지 |
| Isaac 상태 | `forkliftId` | 유지 |
| Isaac 위치 | `forkliftId` | 유지 |
| **명령 / 명령 결과 / WebSocket envelope** | **`vehicleId`** | **신규 규격** |

> 새로 정의한 명령 메시지와 WebSocket envelope만 Java JSON 표기 관례에 따라 `vehicleId`를 쓴다
> (`vehicleID`가 아니다). 기존 상태·위치 메시지의 키 차이는 Router의 판별 근거라 그대로 둔다.

---

## 3. 인바운드 메시지 규격 (JSON = 각 DTO record 필드)

JSON 키 = record 컴포넌트 이름(카멜케이스). 값 검증은 각 Service가 수행하며, 위반 시 해당 메시지만
폐기한다(앱은 계속 동작).

**시각 필드 공통 규칙**: 전부 `OffsetDateTime`이며 `+09:00`을 붙여 보낸다.
오프셋이 없는 값도 과도기 호환으로 **수신은 되지만**(Asia/Seoul로 간주) 경고 로그가 남는다
(`CommunicationTimeModule`, §9 참고).

### 3.1 ROS2 상태 — `forklift/+/status` → `ForkliftStatusMessage`

```json
{ "forkliftId": "REAL-F01", "status": "MOVING", "battery": 87, "timestamp": "2026-07-23T11:20:27+09:00" }
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| forkliftId | string | 필수 | 토픽 `{id}`와 일치해야 함 |
| status | string | 필수 | `VehicleStatus.fromRaw`로 정규화(§6.1) |
| battery | int(primitive) | 필수 | 이 키의 존재가 ROS2 판별 근거. 0~100 검증 |
| timestamp | OffsetDateTime | 필수 | messageAt으로 사용 |

처리: `VehicleStatusService.updateCurrentStatus` → current upsert + history insert → **트랜잭션 커밋 후**
`broadcastStatus`. 롤백 시 WebSocket을 발행하지 않으며, 전송 실패는 Broadcaster가 흡수한다.
미등록 차량이면 `VEHICLE_NOT_FOUND`를 잡아 경고 로그로 다운그레이드.

### 3.2 ROS2 위치 — `forklift/+/location` → `ForkliftLocationMessage`

```json
{
  "vehicleId": "REAL-F01", "status": "MOVING",
  "position": { "x": 1.2, "y": 3.4, "frameId": "map" },
  "heading": 90.0,
  "quaternion": { "x": 0.0, "y": 0.0, "z": 0.7071, "w": 0.7071 },
  "speed": 0.5, "messageAt": "2026-07-23T11:20:27+09:00"
}
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| vehicleId | string | 필수 | 없으면(=`forkliftId`면) Isaac 경로로 감 |
| status | string | 선택 | 표시용(상태 저장은 상태 토픽만의 책임) |
| position.x / position.y | double | 필수 | **단위 m**. NaN/Infinity면 메시지 폐기 |
| position.frameId | string | 선택 | **`map` 또는 `odom`만 허용**, 생략 시 `map`. 그 외는 메시지 폐기 + 경고 로그 |
| heading | double | 선택 | **단위 degree**, [0,360)으로 정규화해 중계 |
| quaternion.x/y/z/w | double | 선택(전부 or 전무) | 일부만 채우면 폐기. 원본 그대로 중계 |
| speed | double | 선택 | 음수/비유한이면 폐기 |
| messageAt | OffsetDateTime | 필수 | |

처리: `ForkliftLocationService` — **DB 저장 안 함**, `existsByVehicleId` 확인 후 `broadcastLocation`만.

### 3.3 Isaac 상태 — `forklift/+/status` → `IsaacForkliftStatusMessage`

```json
{
  "forkliftId": "SIM-F01", "status": "LIFTING", "battery": 80,
  "forkHeight": 0.35, "hasCargo": true, "cargoId": "C-100",
  "footprint": { "length": 1.2, "width": 0.8 },
  "timestamp": "2026-07-23T11:20:27+09:00"
}
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| forkliftId | string | 필수 | |
| status | string | 필수 | Isaac 어휘 7종(§6.2) → 공통 상태로 **1:1 매핑** |
| battery | Integer | 일반 상태 필수 | LWT OFFLINE이면 생략 가능 |
| forkHeight | Double | 일반 상태 필수 | Isaac 판별 키. **DB 저장됨** |
| hasCargo | Boolean | 일반 상태 필수 | Isaac 판별 키. **DB 저장됨** |
| cargoId | string | 선택 | **DB 저장됨** |
| footprint.length / width | Double | 일반 상태 필수 | Isaac 판별 키. **DB 저장됨** |
| timestamp | OffsetDateTime | 선택 | LWT면 없을 수 있음(수신 시각으로 대체) |

**확장 필드가 DB에 저장된다(확정 1장 4번)**: `forkHeight`/`hasCargo`/`cargoId`/`footprint.length`/
`footprint.width`가 `vehicle_current_status`와 `vehicle_status_history`의
`fork_height`/`has_cargo`/`cargo_id`/`footprint_length`/`footprint_width` 컬럼에 저장된다.

**보존 정책(중요)**: ROS2 상태 메시지에는 이 필드가 없다. 그런 메시지가 같은 행을 갱신할 때
**기존 Isaac 값을 null로 덮어쓰지 않고 그대로 보존**한다(`VehicleStatusService`의
`applyIsaacExtras`). 반대로 Isaac 메시지에서는 null도 유효한 갱신값이다 — 화물을 내려놓아 `cargoId`가
사라진 경우를 표현할 수 있어야 하기 때문이다. 상태 이력에는 **병합이 끝난 유효 상태**가 한 행으로
저장되므로, 이력의 각 행은 "그 메시지를 받은 시점에 시스템이 알고 있던 전체 상태"를 나타낸다.

### 3.4 Isaac 위치 — `forklift/+/location` → `IsaacForkliftLocationMessage`

```json
{ "forkliftId": "SIM-F01", "x": 1.2, "y": 3.4, "heading": 90.0, "speed": 0.5, "timestamp": "2026-07-23T11:20:27+09:00" }
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| forkliftId | string | 필수 | 이 키가 Isaac 판별 근거 |
| x / y | double | 필수 | **단위 m**. ROS2와 **동일 원점** 가정 |
| heading | double | 필수 | **단위 degree**, [0,360) 정규화. 구 필드명 `direction`은 읽기 alias로 허용 |
| speed | double | 필수 | m/s, 음수 불가 |
| timestamp | OffsetDateTime | 필수 | |

> **`direction` alias 주의(외부 연동 확인 필요)**: alias는 **이름**만 호환할 뿐 **단위를 변환하지 않는다**.
> 옛 브리지가 rad 값을 `direction`으로 보내면 그 값이 degree로 해석된다. 단위 전환은 Isaac 브리지 쪽에서
> 함께 이뤄져야 한다.

### 3.5 Isaac 경로 — `forklift/+/path` → `IsaacForkliftPathMessage`

```json
{
  "forkliftId": "SIM-F01",
  "waypoints": [ { "x": 1.0, "y": 2.0 }, { "x": 1.5, "y": 2.5 } ],
  "goal": { "x": 5.0, "y": 6.0, "heading": 180.0 },
  "timestamp": "2026-07-23T11:20:27+09:00"
}
```

좌표 단위 m, `goal.heading`은 degree([0,360) 정규화). `goal.direction`은 읽기 alias.
처리: `IsaacForkliftPathService` — DB 저장 안 함, `broadcastPath`만.

### 3.6 통합 명령 결과 — `forklift/+/command-result` → `VehicleCommandResultMessage`

```json
{
  "commandId": "CMD-003", "vehicleId": "REAL-F01",
  "targetSystem": "ALL", "commandCategory": "SAFETY", "command": "EMERGENCY_STOP",
  "result": "SUCCESS", "message": "주행과 포크 정지 완료",
  "completedAt": "2026-07-23T11:20:28+09:00"
}
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| commandId | string | 필수 | 발행했던 명령의 ID와 매칭 |
| vehicleId | string | 필수 | 토픽 `{id}`와 일치. 구 `forkliftId` 키도 alias로 수신 |
| targetSystem | string | 권장 | 없으면 구 형식으로 간주(경고 로그 후 이 검증만 생략) |
| commandCategory | string | 권장 | 동일 |
| command | string | 필수 | `VehicleCommandType`(§6.3) |
| result | string | 필수 | ACCEPTED / IN_PROGRESS / SUCCESS / FAILED / REJECTED / CANCELLED |
| completedAt | OffsetDateTime | 필수 | |
| forkState / limitBottom / emergencyStopApplied / stoppedActions / requiresReset / errorCode | 선택 | | 임베디드 결과 전용. 비상정지 결과의 핵심이라 규격 예시에 없어도 유지 |

**필수 검증 순서**: ① 토픽 vehicleId ↔ payload vehicleId ② commandId 존재 ③ 기존 명령과 vehicleId 일치
④ targetSystem 일치 ⑤ commandCategory 일치 ⑥ command 일치 ⑦ 허용된 상태 전이 ⑧ 중복 종료 결과.
⑦·⑧은 `VehicleCommandStatus.canTransitionTo`가 함께 처리한다(종료 상태에서는 어떤 전이도 거부되므로
중복 결과가 자동으로 걸러진다).

처리: `VehicleCommandResultService` → `embedded_vehicle_command` update + `broadcastCommandResult`.

### 3.7 임베디드 포크 상태 — `forklift/+/fork-status` → `EmbeddedForkStatusMessage`

```json
{ "forkliftId": "REAL-F01", "forkState": "MOVING_UP", "limitBottom": false, "errorCode": null, "timestamp": "2026-07-23T11:20:27+09:00" }
```

처리: `EmbeddedForkStatusService` → `vehicle_fork_current_status` upsert + `broadcastForkStatus`.

### 3.8 임베디드 오류 — `forklift/+/error` → `EmbeddedErrorMessage`

```json
{ "forkliftId": "REAL-F01", "errorCode": "E-DRV-01", "errorSource": "DRIVE", "severity": "CRITICAL", "message": "모터 과전류", "timestamp": "2026-07-23T11:20:27+09:00" }
```

처리: `EmbeddedErrorService` → `embedded_error_history` insert + `broadcastEmbeddedError`.

### 3.9 AI 화물 분석 — `cargo/detected` → `AiCargoAnalysisMessage`

이전 버전과 규격이 동일하다(시각 필드는 이 도메인 내부에서 `LocalDateTime`을 유지). 상세는 이전 문서
내용과 같으며 변경된 것은 **WebSocket 출력이 공통 envelope로 감싸진다**는 점뿐이다(§5.2).

### 3.10 적재 화물 안전 — `forklift/+/load-safety` → `LoadSafetyMessage`

```json
{
  "vehicleId": "REAL-F01",
  "cargoId": "CARGO-001",
  "forkHeight": 0.86,
  "cargoHeight": 1.42,
  "roll": 7.4,
  "pitch": 3.1,
  "loadOffsetX": -0.18,
  "loadOffsetY": 0.04,
  "riskLevel": "WARNING",
  "riskCode": "LOAD_TILT_EXCEEDED",
  "message": "화물이 좌측으로 과도하게 기울었습니다.",
  "source": "VISION",
  "detectedAt": "2026-07-29T10:30:00+09:00"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `vehicleId` | string | **필수** | `forkliftId` alias로도 수신 가능(기존 실물/Isaac 메시지 호환). 정식 키는 `vehicleId` |
| `cargoId` | string | 선택 | 화물 미인식이면 null |
| `forkHeight` | number | 선택 | 포크 높이(m) |
| `cargoHeight` | number | 선택 | 화물 높이(m) |
| `roll` | number | 선택 | 좌우 기울기(degree). DB 컬럼명은 `roll_deg` |
| `pitch` | number | 선택 | 앞뒤 기울기(degree). DB 컬럼명은 `pitch_deg` |
| `loadOffsetX` / `loadOffsetY` | number | 선택 | 화물 중심 편향(m) |
| `riskLevel` | string | **필수** | §6.12 참고. **백엔드는 이 값을 계산하지 않고 그대로 저장한다** |
| `riskCode` | string | 선택 | 예: `LOAD_TILT_EXCEEDED` |
| `message` | string | 선택 | 사람이 읽는 경고 문구 |
| `source` | string | 선택 | §6.13 참고. 없으면 `UNKNOWN` |
| `detectedAt` | OffsetDateTime | **필수** | 센서 감지 시각(+09:00) |

처리: `LoadSafetyService` → `vehicle_load_safety` **upsert(차량당 1행)** + `broadcastLoadSafety`.

**검증·폐기 규칙**
- 필수 3종(`vehicleId`/`riskLevel`/`detectedAt`) 누락 → 경고 로그 후 폐기
- 미등록 차량 → 폐기(차량을 임의로 생성하지 않음)
- 토픽 `{id}`와 payload `vehicleId` 불일치 → 폐기(다른 차량 상태를 덮어쓰지 않기 위함)
- **정의되지 않은 `riskLevel`** → `UNKNOWN`으로 저장하되 **메시지는 버리지 않는다**(높이·기울기 등
  나머지 측정값은 여전히 유효하다). 계약 불일치를 드러내기 위해 경고 로그를 남긴다

> **⚠ 이 규격은 팀 확정 규격이 아니다.** prompt63.md가 §5 payload 예시 중간에서 잘려 필드별 필수/선택·
> 단위·값 집합 표가 존재하지 않는다. 위 표의 필수/선택 구분, 단위(m·degree), `riskLevel`/`source` 값
> 집합은 **백엔드 구현이 현재 채택한 값**이며 비전·센서 팀과 확정이 필요하다(answer63.md §9·§13 참고).
> 또한 **이 토픽을 발행하는 코드는 아직 이 저장소에 없다** — 현재는 수신 준비만 된 상태다.

---

## 4. 아웃바운드(발행) 명령 규격 — `forklift/{vehicleId}/command`

**이동·포크/적재·비상정지 명령이 전부 이 토픽 하나, 이 envelope 하나를 쓴다.**

REST `POST /api/vehicles/{vehicleId}/commands` (body `VehicleCommandRequest`)
→ `VehicleCommandService.issueCommand` → `VehicleCommandPublisher.publish`.

### 4.1 공통 envelope — `VehicleCommandMessage`

```json
{
  "commandId": "CMD-001",
  "vehicleId": "REAL-F01",
  "targetSystem": "ROS2",
  "commandCategory": "MOVE",
  "command": "MOVE",
  "payload": {},
  "reason": null,
  "timestamp": "2026-07-23T11:20:27+09:00"
}
```

- **필수**: `commandId`, `vehicleId`, `targetSystem`, `commandCategory`, `command`, `timestamp`
- **선택**: `payload`, `reason`
- `payload`는 destination이 없는 명령에서도 **`null`이 아니라 빈 객체 `{}`** 로 나간다 — 수신 측이
  키 존재 여부를 분기하지 않고 항상 같은 모양으로 읽을 수 있게 하기 위해서다.
- **`commandId`와 `timestamp`는 백엔드가 생성한다.** REST 요청 DTO에는 이 두 필드 자리가 아예 없다.

### 4.2 명령 조합 검증 규칙 (확정)

| command | targetSystem | commandCategory | payload.destination |
|---|---|---|---|
| `MOVE` | `ROS2` | `MOVE` | **필수** |
| `FORK_UP` / `FORK_DOWN` | `EMBEDDED` | `FORK` | — |
| `LOAD` / `UNLOAD` | `EMBEDDED` | `LOAD` | — |
| `EMERGENCY_STOP` | `ALL` | `SAFETY` | — |
| `RESET_ESTOP` | `ALL` | `SAFETY` | — |
| `STOP` | `EMBEDDED` | `SAFETY` | — `팀 확인 필요` |

- 요청이 `targetSystem`/`commandCategory`를 **생략하면 백엔드가 위 표대로 채운다.**
- 요청이 **명시했는데 표와 다르면 발행하지 않고 400 `COMMAND_COMBINATION_INVALID`** 로 거부한다 —
  호출자가 잘못 알고 있는 조합을 조용히 고쳐서 발행하면, 수신 측이 `targetSystem`으로 1차 분기하는
  설계 자체가 신뢰를 잃기 때문이다.
- `STOP`은 확정 규격의 "지원값" 목록에는 있으나 조합 규칙 절에 없다. 추측으로 ROS2에 배정하지 않고
  이 프로젝트가 기존에 STOP을 실물 임베디드 명령으로 다뤄 온 동작을 유지했다 — `팀 확인 필요`.
- `RESET_ESTOP`은 규격이 "ALL 또는 팀 기존 처리 대상에 맞게"로 열어 뒀다. EMERGENCY_STOP과 짝을
  이루므로 동일하게 `ALL`+`SAFETY`로 고정했다.

### 4.3 이동 명령 예시

```json
{
  "commandId": "CMD-001", "vehicleId": "SIM-F01",
  "targetSystem": "ROS2", "commandCategory": "MOVE", "command": "MOVE",
  "payload": { "destination": { "x": 5.0, "y": 6.0, "heading": 180.0, "frameId": "map" } },
  "reason": null, "timestamp": "2026-07-23T11:20:27+09:00"
}
```

destination 검증: `x`/`y` 필수·finite·**단위 m**, `heading`은 **degree**([0,360) 정규화해서 발행),
`frameId`는 **`map` 또는 `odom`**(생략 시 `map`). 위반 시 400 `COMMAND_DESTINATION_INVALID`.

### 4.4 임베디드 명령 예시

```json
{
  "commandId": "CMD-002", "vehicleId": "REAL-F01",
  "targetSystem": "EMBEDDED", "commandCategory": "FORK", "command": "FORK_UP",
  "payload": {}, "reason": "적재 준비", "timestamp": "2026-07-23T11:20:27+09:00"
}
```

### 4.5 비상 정지 예시

```json
{
  "commandId": "CMD-003", "vehicleId": "REAL-F01",
  "targetSystem": "ALL", "commandCategory": "SAFETY", "command": "EMERGENCY_STOP",
  "payload": {}, "reason": "관제 사용자 비상 정지", "timestamp": "2026-07-23T11:20:27+09:00"
}
```

수신 측이 **가장 먼저 분기할 수 있도록** `targetSystem=ALL` + `commandCategory=SAFETY` +
`command=EMERGENCY_STOP` 조합이 항상 보장된다.

#### EMERGENCY_STOP 백엔드 보장 범위

백엔드는 UUID `commandId` 생성, 명령 저장, `forklift/{vehicleId}/command` publish 시도,
`PUBLISHED`/`PUBLISH_FAILED` 상태 기록까지만 보장한다. **`PUBLISHED`는 브로커 발행 호출 성공이며
ROS2 수신, 모터 정지, 하드웨어 안전, 정지 완료를 뜻하지 않는다.** 실제 정지·해제·fail-safe와 중복 명령
정책은 ROS2·임베디드 담당자와 장비 검증이 필요하다 — `외부 연동 확인 필요`.

### 4.6 명령 상태 전이

```
PENDING     → PUBLISHED, PUBLISH_FAILED
PUBLISHED   → ACCEPTED, IN_PROGRESS, SUCCESS, FAILED, REJECTED, CANCELLED
ACCEPTED    → IN_PROGRESS, SUCCESS, FAILED, REJECTED, CANCELLED
IN_PROGRESS → SUCCESS, FAILED, REJECTED, CANCELLED
(SUCCESS/FAILED/REJECTED/CANCELLED/PUBLISH_FAILED = 종료 상태, 더 이상 전이 없음)
```

MQTT 발행이 실패해도 트랜잭션을 롤백하지 않고 `PUBLISH_FAILED`로 정직하게 저장한다 —
"발행 실패를 실행 성공으로 저장하지 않는다"는 조건은 이 상태 분리로 만족된다.

---

## 5. WebSocket(STOMP) 아웃바운드 규격

- **엔드포인트**: `/ws` (SockJS). 프론트는 `new SockJS("http(s)://<host>/ws")`로 연결.
- **브로커 prefix**: `/topic` (SimpleBroker). **앱 prefix**: `/app`.
- 모든 이벤트는 "전체 destination"과 "개별 destination(`/{id}`)" **두 곳에 동시 전송**된다.
- 상태 이벤트는 DB 트랜잭션 **커밋 후** 전송한다. 전송 실패는 Broadcaster가 흡수(로그만)한다.

### 5.1 공통 envelope — `RealtimeEvent<T>`

**차량·AI·스테이션 이벤트가 전부 같은 봉투를 쓴다**(확정 1장 13번). 이전에는 도메인마다 봉투가 달랐고
AI는 봉투 자체가 없었다.

```json
{
  "eventType": "VEHICLE_STATUS_UPDATED",
  "vehicleId": "REAL-F01",
  "occurredAt": "2026-07-23T11:20:27+09:00",
  "data": { }
}
```

| 필드 | 필수 | 비고 |
|---|---|---|
| eventType | 필수 | §5.3 목록 |
| vehicleId | **nullable** | 차량 이벤트는 필수. 차량과 연결된 AI 이벤트는 그 vehicleId. 연결되지 않은 AI/스테이션 이벤트는 `null` |
| occurredAt | 필수 | `OffsetDateTime` `+09:00`. 서버 처리 시각이 아니라 **이벤트 발생 시각** |
| data | 필수 | 도메인별 payload |

**도메인 식별자 위치**: 최상위 식별자 이름은 `vehicleId` 하나로 통일한다. AI의 `cargoId`, 스테이션의
`stationId`/`measurementId`는 최상위로 올리지 않고 `data` 내부에 그대로 유지한다.

> destination은 통일하지 않았다 — 목표는 **payload envelope 구조의 통일**이지 경로 통합이 아니다.

### 5.2 destination · eventType · data 매핑

| destination(전체 / 개별) | eventType | data 타입 | vehicleId |
|---|---|---|---|
| `/topic/vehicles/status` / `…/{id}` | `VEHICLE_STATUS_UPDATED` | `VehicleStatusResponse`(ROS2) / `IsaacVehicleStatusEventData`(Isaac) | 필수 |
| `/topic/vehicles/location` / `…/{id}` | `VEHICLE_LOCATION_UPDATED` | `VehicleLocationEventData` / `IsaacVehicleLocationEventData` | 필수 |
| `/topic/vehicles/path` / `…/{id}` | `VEHICLE_PATH_UPDATED` | `VehiclePathEventData` | 필수 |
| `/topic/vehicles/result` / `…/{id}` | `VEHICLE_COMMAND_RESULT_UPDATED` | `VehicleCommandResultEventData` | 필수 |
| `/topic/vehicles/fork-status` / `…/{id}` | `VEHICLE_FORK_STATUS_UPDATED` | `EmbeddedForkStatusEventData` | 필수 |
| `/topic/vehicles/errors` / `…/{id}` | `VEHICLE_ERROR_OCCURRED` | `EmbeddedErrorEventData` | 필수 |
| `/topic/vehicles/load-safety` / `…/{id}` | `VEHICLE_LOAD_SAFETY_UPDATED` | `LoadSafetyResponse` | 필수 |
| `/topic/ai/cargo-analysis` / `…/{cargoId}` | `AI_CARGO_ANALYSIS_COMPLETED` | `AiCargoAnalysisResponse` | **nullable** |
| `/topic/stations/measurements`, `/topic/stations/{stationId}/measurements` | `STATION_MEASUREMENT_COMPLETED` | `StationMeasurementResponse` | **항상 null** |

스테이션은 차량과 독립적으로 동작해 측정 결과에 차량이 배정되지 않으므로 `vehicleId`가 항상 `null`이다.

### 5.3 eventType 전체 목록 — `RealtimeEventType`

`VEHICLE_STATUS_UPDATED`, `VEHICLE_LOCATION_UPDATED`, `VEHICLE_PATH_UPDATED`,
`VEHICLE_COMMAND_RESULT_UPDATED`, `VEHICLE_FORK_STATUS_UPDATED`, `VEHICLE_ERROR_OCCURRED`,
`VEHICLE_LOAD_SAFETY_UPDATED`, `AI_CARGO_ANALYSIS_COMPLETED`, `STATION_MEASUREMENT_COMPLETED`

`RealtimeEventTest.eventType_coversEveryConfirmedDomainEvent`가 이 목록 전체를 회귀 검증한다.

**적재 안전 이벤트의 `occurredAt`**: 서버 처리 시각이 아니라 **센서 감지 시각(`detectedAt`)**을 담는다.
화면이 "이 값이 얼마나 오래된 것인가"를 판단하는 근거이기 때문이다
(`LoadSafetyServiceIntegrationTest.broadcast_usesDetectedAtAsOccurredAt`).

---

## 6. Enum 값 (코드 정의 그대로)

### 6.1 VehicleStatus (확정 10종)
`UNKNOWN, IDLE, ACTIVE, MOVING, LIFTING, LOADING, UNLOADING, ESTOP, ERROR, OFFLINE`

의미: UNKNOWN=상태 확인 불가 / IDLE=대기·정지 / ACTIVE=일반 작업 활성 / MOVING=주행 중 /
LIFTING=포크 승강 중 / LOADING=적재 중 / UNLOADING=하역 중 / ESTOP=비상 정지 상태 / ERROR=오류 발생 /
OFFLINE=통신 단절·접속 종료.

`fromRaw`: null·빈값·미정의 값 → `UNKNOWN`. **어떤 값도 다른 값으로 치환하지 않는다** —
과거의 `MOVING → ACTIVE` 정규화는 폐지됐다. 대소문자와 앞뒤 공백만 흡수한다.
enum 선언 순서가 곧 상태 집계 응답(`GET /api/vehicles/status-counts`)의 항목 순서다.

### 6.2 IsaacForkliftStatus
`IDLE, MOVING, LIFTING, LOADING, ERROR, ESTOP, OFFLINE` — 확장된 `VehicleStatus`에 **전부 1:1 대응**한다
(정보 손실 없음).

### 6.3 VehicleCommandType
`MOVE, STOP, FORK_UP, FORK_DOWN, LOAD, UNLOAD, EMERGENCY_STOP, RESET_ESTOP`

### 6.4 VehicleCommandTargetSystem
`ROS2, EMBEDDED, ALL`

### 6.5 VehicleCommandCategory
`MOVE, FORK, LOAD, SAFETY`

### 6.6 VehicleCommandStatus
`PENDING, PUBLISHED, PUBLISH_FAILED, ACCEPTED, IN_PROGRESS, SUCCESS, FAILED, REJECTED, CANCELLED`

### 6.7 EmbeddedForkState
`MOVING_UP, MOVING_DOWN, STOPPED, BOTTOM, ERROR, UNKNOWN`

### 6.8 EmbeddedErrorSource
`DRIVE, STEERING, FORK, LIMIT_SWITCH, UART, SYSTEM`

### 6.9 EmbeddedStoppedAction
`DRIVE, STEERING, FORK`

### 6.10 EmbeddedErrorSeverity
`WARNING, ERROR, CRITICAL`

### 6.11 AiAnalysisStatus / DimensionScale / LoadBalanceDirection
`OK, NO_DETECTION, UNRELIABLE` / `REAL, MINIATURE` / `LEFT, RIGHT, FRONT, BACK`

### 6.12 LoadSafetyRiskLevel (**팀 확정 전**)
`NORMAL, CAUTION, WARNING, DANGER, UNKNOWN`

- **백엔드도 프론트도 이 값을 계산하지 않는다.** 비전·센서가 판정해 보낸 값을 그대로 저장·표시한다.
- `fromRaw()`는 대소문자·앞뒤 공백만 흡수하고, **정의되지 않은 값을 다른 단계로 흡수하지 않는다** —
  전부 `UNKNOWN`이다. 미정의 값이 `NORMAL`로 떨어지면 위험 상태가 화면에 정상으로 표시된다
  (`LoadSafetyRiskLevelTest.unknownValues_neverBecomeNormal`).
- `isAlerting()` = `WARNING` 또는 `DANGER`. 프론트 경고 오버레이 표시 기준과 동일하다.
  `UNKNOWN`은 경보가 **아니다**(판정 불가를 위험으로 승격하면 오탐이 쏟아진다).
- ⚠ 이 4단계 사다리는 prompt63.md가 잘려 확정되지 않았다 — §5 예시에 `"WARNING"` 하나만 있었다.
  비전 팀이 다른 값 집합을 쓰면 enum에 값을 추가해야 하며, 그때까지 미정의 값은 `UNKNOWN`으로
  안전하게 수신된다.

### 6.13 LoadSafetySource (**팀 확정 전**)
`VISION, SENSOR, ROS2, UNKNOWN`

같은 차량에 대해 비전과 센서가 서로 다른 주기로 값을 보낼 수 있어 출처를 보존한다.
미정의 값은 `UNKNOWN`(흡수하지 않음). ⚠ 값 집합 미확정 — §5 예시에 `"VISION"`만 있었다.

---

## 7. REST API 요약 (통신 관련)

| Method | URL | 설명 | 활성 조건 |
|---|---|---|---|
| GET | `/api/health` | 헬스체크 | 항상 |
| POST | `/api/vehicles` | 차량 등록 | 항상 |
| PATCH | `/api/vehicles/{vehicleId}/active` | 차량 활성·비활성 변경 | 항상 |
| GET | `/api/vehicles` | 활성 차량 목록 | 항상 |
| GET | `/api/vehicles/status-counts` | 상태별 집계(**10종 전부, 0건 포함**) | 항상 |
| GET | `/api/vehicles/{vehicleId}` | 차량 상세 | 항상 |
| GET | `/api/vehicles/{vehicleId}/status-history?limit=` | 상태 이력(기본 50, 1~200, **Isaac 확장 필드 포함**) | 항상 |
| PUT | `/api/vehicles/{vehicleId}/status` | 상태 갱신(테스트용) | `vehicle.status-test-api.enabled` |
| **POST** | **`/api/vehicles/{vehicleId}/commands`** | **통합 명령 발행** | 항상 |
| **GET** | **`/api/vehicles/{vehicleId}/commands`** / **`/{commandId}`** | **명령 조회** | 항상 |
| POST/GET | `/api/vehicles/{vehicleId}/embedded-commands`(+`/{commandId}`) | 위 API의 **deprecated alias** | 항상 |
| GET | `/api/vehicles/{forkliftId}/fork-status` | 포크 현재 상태 | 항상 |
| **GET** | **`/api/vehicles/load-safety/latest`** | **활성 차량 전체 최신 적재 안전**(미수신 차량은 목록에 없음) | 항상 |
| **GET** | **`/api/vehicles/{vehicleId}/load-safety/latest`** | **차량 최신 적재 안전**(미수신 시 `data:null`) | 항상 |
| GET | `/api/vehicles/{forkliftId}/embedded-errors?limit=` | 오류 이력 | 항상 |
| GET | `/api/ai/cargo-analysis/{analysisId}` | 분석 단건 | 항상 |
| GET | `/api/cargos/{cargoId}/ai-analysis/latest` | 최신 분석 | 항상 |
| GET | `/api/stations/measurements/{measurementId}` | 측정 단건 | 항상 |
| GET | `/api/stations/{stationId}/measurements/latest` | 스테이션 최신 측정 | 항상 |
| POST | `/api/mqtt/test` | MQTT 임시 발행(검증용) | `mqtt.test-api.enabled` |

공통 응답 형식: `{ "success": bool, "data": {...}|null, "error": { "code", "message" }|null }`.

**명령 관련 ErrorCode**: `COMMAND_TYPE_INVALID`(400), `COMMAND_COMBINATION_INVALID`(400),
`COMMAND_DESTINATION_INVALID`(400), `COMMAND_NOT_FOUND`(404), `COMMAND_LIMIT_INVALID`(400),
`COMMAND_ID_DUPLICATED`(409), `VEHICLE_NOT_FOUND`(404).

**적재 안전 조회 정책**: 적재 안전 데이터를 한 번도 수신하지 못한 차량은 **오류가 아니라
200 + `data:null`**이다(기존 `GET /api/vehicles/{id}/location/latest`와 같은 방침 — 미수신은 정상적인
초기 상태다). 반면 **등록되지 않은 차량**은 `VEHICLE_NOT_FOUND`(404)로 거부한다 — 오타 난 vehicleId에
"데이터 없음"을 돌려주면 호출자가 차량이 조용한 것인지 존재하지 않는 것인지 구분할 수 없다.
**쓰기 API는 없다** — 이 도메인의 쓰기 경로는 MQTT 하나뿐이며, 화면이나 외부 도구가 위험 단계를 임의로
써넣을 수 있으면 "비전이 판정한 값"이라는 계약이 깨진다.

---

## 8. 하위 호환 정책 · 남은 미확정 · 외부 연동 확인 필요

### 8.1 하위 호환 (과도기 조치 — 제거 조건 포함)

| 항목 | 동작 | 제거 조건 |
|---|---|---|
| 오프셋 없는 timestamp | Asia/Seoul로 간주해 수신 + **경고 로그** (`CommunicationTimeModule`) | 모든 발행 측이 `+09:00`을 붙이고 경고 로그가 사라지면 |
| 명령 결과의 `forkliftId` 키 | `vehicleId`의 `@JsonAlias`로 수신 | 임베디드·ROS2가 `vehicleId`로 전환하면 |
| 명령 결과의 `targetSystem`/`commandCategory` 누락 | 해당 검증만 생략 + **deprecated 경고 로그**. WebSocket 출력은 저장된 값으로 채워 완전한 envelope 유지 | 동일 |
| 위치·경로의 `direction` 필드 | `heading`의 읽기 alias (**단위 변환은 하지 않음**) | Isaac 브리지가 degree `heading`으로 전환하면 |
| `POST/GET /api/vehicles/{id}/embedded-commands` | `/commands`로 위임하는 alias | 프론트·테스트 도구가 `/commands`로 전환하면 |

**새 규격이 표준이다.** 위 항목은 전부 과도기 조치이며 신규 코드는 새 규격만 사용해야 한다.

### 8.2 남은 미확정 / 팀 확인 필요

1. `STOP` 명령의 `targetSystem`/`commandCategory` 조합 — 확정 규격의 조합표에 없어 기존 동작
   (`EMBEDDED`+`SAFETY`)을 유지했다. `팀 확인 필요`
2. Isaac 브리지의 `direction`(rad) → `heading`(degree) **단위 전환 시점** — 백엔드는 이름 alias만
   제공하고 값 변환은 하지 않는다.
3. Isaac 브리지의 차량 ID 표기(`SIM_F01` 언더스코어) vs 백엔드 등록 차량(`SIM-F01` 하이픈) 불일치 —
   백엔드 밖 문제이지만 이대로면 모든 시뮬 메시지가 미등록 차량으로 폐기된다.
4. **`forklift/+/load-safety` 규격 전반(§3.10) — `팀 확인 필요`.** 이 기능을 지시한 prompt63.md가
   payload 예시 중간에서 잘려 필드 표가 존재하지 않는다. 다음이 전부 **백엔드 구현이 채택한 값**이며
   비전·센서 팀 확정이 필요하다:
   - `riskLevel` 값 집합(현재 4단계 + UNKNOWN, §6.12)
   - `source` 값 집합(§6.13)
   - 단위(높이 m, roll/pitch degree, offset m) — 예시 값 크기로 추정했다
   - 필수/선택 구분(현재 `vehicleId`/`riskLevel`/`detectedAt`만 필수)
   - **센서 발행 주기** — 프론트의 "오래된 데이터" 임계값(현재 15초)이 이 값에 의존한다.
     주기가 임계값보다 길면 정상 데이터에 상시 경고가 붙는다.
   - 이력 보관 필요 여부(현재 차량당 최신 1행만 유지, 이력 테이블 없음)
   - 차량 `OFFLINE` 시 마지막 적재 안전 값의 표시 정책(현재 값이 남고 stale 표시로만 완화)

### 8.3 외부 연동 확인 필요 (저장소만으로 검증 불가)

> **2026-07-24 갱신**: 아래 중 "실제 Mosquitto 송수신"은 **검증 완료**로 바뀌었다(§8.5 참고).
> 자동 재연결과 EC2 연동은 여전히 미검증이다.

- ~~실제 Mosquitto Broker 송수신~~ → **검증 완료**(§8.5). 단 **자동 재연결과 로컬/EC2 포트 연결은 미검증**.
- ROS2/Isaac Sim이 실제로 발행하는 JSON이 위 DTO와 정확히 일치하는지.
- **ROS2/임베디드가 `forklift/{id}/command`를 구독해 통합 envelope를 해석하는지** — 이 저장소에는
  이 토픽을 구독하는 코드가 없다.
- **`EMERGENCY_STOP` 수신 즉시 ROS2·모터 드라이버가 실제로 정지하는지**, 해제 승인·네트워크 단절
  fail-safe·중복 명령 정책.
- 임베디드·ROS2가 통합 `command-result` envelope를 회신하는지.
- 프론트엔드(React, 현재 저장소에 없음)의 STOMP 구독·수신.
- **`forklift/{id}/load-safety`를 실제로 발행하는 주체가 아직 없다.** 비전·센서·ROS2 어디에도 이 토픽을
  발행하는 코드가 이 저장소에 존재하지 않는다 — 백엔드·프론트는 **수신 준비만 된 상태**이며 화면에는
  "적재 안전 데이터 미수신"이 표시된다. 송신 측 구현 후 end-to-end 확인이 필요하다.

### 8.4 ROS2 MQTT bridge 구현 상태 (prompt34)

`ros2_ws/src/fast_mqtt_bridge`에 Python(`rclpy` + `paho-mqtt`) 브리지 패키지를 추가했다.

- 모든 차량 MQTT publish/subscribe는 QoS 1, publish retained false다.
- 환경변수 > ROS parameter/YAML > 기본값 우선순위를 적용하며 비밀번호는 환경변수로 주입한다.
- MQTT callback은 priority queue에만 적재하고 ROS2 executor timer가 처리한다.
- commandId는 TTL/최대 개수 제한이 있는 thread-safe memory cache로 중복 실행을 막는다.
- 명령 결과는 이 문서 §3.6의 실제 DTO(`result`, `completedAt`, `SUCCESS`)를 사용한다.
- 위치는 §3.2의 실제 DTO(`position`, `messageAt`)를 사용한다.
- 경로는 §3.5의 실제 DTO(`waypoints`, `goal`)를 사용한다.
- 현재 ROS2 저장소에는 상태 원본 type, Nav2 action, 정지/비상정지 service가 없으므로 임의
  interface를 생성하지 않았다. 기본 명령 adapter는 실행 대신 `REJECTED` 결과를 보낸다.
- 기존 `forklift_teleop` `/cmd_vel` → UART 동작은 수정하지 않았다.

실행/환경변수/수동 Mosquitto 명령과 실제 장비 미검증 범위는
`ros2_ws/src/fast_mqtt_bridge/README.md`를 기준으로 한다.

### 8.5 실제 Mosquitto 브로커 검증 결과 (2026-07-24)

Jira `MQTT 브로커 구축·구독 연결` 검증 과정에서 **실제 Mosquitto(localhost:1883)** 를 대상으로 아래를
확인했다. 재현 절차는 `infra/mqtt/README.md` 2절, 상세 로그는 `prompt/answer/answer41.md` 참고.

| 검증 항목 | 결과 | 근거 |
|---|---|---|
| 브로커 Pub/Sub 왕복 | **성공** | `fast/test/connection mqtt-connected` 수신 |
| 백엔드 → 브로커 연결 | **성공** | `MQTT subscribed: bean=mqttInboundAdapter` |
| 8토픽 구독, **구독 QoS 전부 1** | **성공** | `qos=[1, 1, 1, 1, 1, 1, 1, 1]` |
| 수신 → Router → Service → DB → WebSocket | **성공** | `REAL-F01`/`MOVING`/battery 87, current upsert + history insert + `VEHICLE_STATUS_UPDATED` |
| **`MOVING` 상태 보존**(ACTIVE로 흡수 안 됨) | **성공** | DB·REST 응답 모두 `MOVING` |
| 명령 발행 **QoS 1** | **성공** | `forklift/REAL-F01/command` 로 통합 envelope 수신 |
| 명령 **retained false 실동작** | **성공** | 재구독 시 과거 명령 미전달 |
| 잘못된 JSON 폐기 후 consumer 유지 | **성공** | 폐기 로그 후 다음 메시지 정상 처리 |
| 시각 `+09:00` 왕복 | **성공** | 발행 `...+09:00` → REST 응답 `...+09:00` |

**여전히 미검증**: 브로커 중지·재시작을 통한 자동 재연결 실동작, Docker Compose 기동,
EC2 브로커·인증, ROS2 브리지 ↔ 브로커 실제 연결, 실제 차량의 명령 수신·`command-result` 회신.

---

## Measurement Station v2.0 (REST)

> 이 절은 실제 구현된 스테이션 도메인 코드(`com.fast.backend.station.*`)를 근거로 작성했다.

> ⚠️ **v1.0(MQTT)은 폐기됐다.** 측정 결과는 더 이상 `fast/station/{station_id}/measurement` 토픽으로
> 들어오지 않는다. 그 토픽의 구독·라우팅(`routeStationMeasurement`)·수신 DTO(`StationMeasurementMessage`)·
> MQTT 전용 검증은 **모두 제거**됐고, 설정 키 `mqtt.topics.station-measurement`도 없어졌다.
> 아래 REST 규격이 유일한 수신 경로다. (차량 위치 MQTT `forklift/+/location`은 그대로 유지된다.)

### 아키텍처 배경

- 측정 **추론·판정은 측정 데스크탑**에서 수행한다(거리/치수/전복/돌출 계산 완료).
- **백엔드는 측정 결과만 수신**해 검증·저장·중계한다(추론하지 않는다). 전복 등급을 재계산하지 않는다.
- **측정 데스크탑은 DB에 직접 접속하지 않는다.** Spring Boot REST API가 유일한 저장 경로다.
- 기존 `cargo/detected`(AI 화물 분석) 흐름은 그대로 유지한다 — 두 규격을 섞지 않는다.

### 수신 API

| 항목 | 값 |
|---|---|
| Method / URL | `POST /api/stations/measurements` |
| Content-Type | `application/json` |
| 성공 응답 | **201 Created**, 본문은 공통 `ApiResponse<StationMeasurementResponse>` |
| 인증 | **없음** — 이 저장소에 Spring Security 설정이 존재하지 않는다(전 API 공통) |
| 요청 DTO | `StationMeasurementCreateRequest` (**camelCase**) |

```json
{
  "measurementId": "station-1-20260731-093748-0001",
  "status": "ok",
  "cargoHeight": 0.723,
  "tippingLevel": "safe",
  "overhangRatio": 0.057,
  "measuredAt": "2026-07-31T09:37:48+09:00"
}
```

### 필드 정의

| 필드 | 타입 | 필수 | 단위 | 비고 |
|---|---|---|---|---|
| measurementId | String | 필수 | — | UNIQUE, blank 불가, **100자 이하**. 중복 시 409 |
| status | String→enum | 필수 | — | `ok` / `dimensions_only` / `no_detection` / `unreliable` (소문자 수신, 대문자 저장) |
| cargoHeight | Double | status별 | **meter** | **화물만의 높이(팔레트 제외)**. `> 0`, finite |
| tippingLevel | String→enum | status별 | — | `safe`/`warning`/`danger` 수신 → **`SAFE`/`WARNING`/`DANGER`로 정규화 저장** |
| overhangRatio | Double | status별 | 무차원 비율 | `>= 0`, finite. **상한 강제 없음**(1.0 초과 가능) |
| measuredAt | OffsetDateTime | 선택 | — | 장비 측정 시각. **저장 컬럼이 없어 현재는 버려진다**(아래 참고) |

**받지 않는 필드**: `sessionId`(백엔드가 활성 세션 조회), `stationId`(FR-202에서 컬럼 삭제, 설비는 하나),
`schemaVersion`(REST는 URL·DTO로 버전을 표현).

### status별 필드 조합

| status | cargoHeight | tippingLevel | overhangRatio | 의미 |
|---|---|---|---|---|
| `ok` | **필수**(> 0) | **필수** | **필수**(>= 0) | 정상 측정 |
| `dimensions_only` | **필수**(> 0) | **null 강제** | **null 강제** | 파렛트 미검출 — 치수만 성공, 전복·돌출 판정 불가 |
| `no_detection` | **null 강제** | **null 강제** | **null 강제** | 박스 없음 |
| `unreliable` | **null 강제** | **null 강제** | **null 강제** | 거리 측정 실패 |

값이 있으면 안 되는 자리에 값이 오면 **무시하지 않고 400으로 거부한다**(데스크탑의 잘못된 전송을 숨기지 않는다).

### 단위 정책 — cm가 아니라 m다

REST 계약의 `cargoHeight`는 **meter**이며 **화물만의 높이**다. 비전 원본은 cm(`height_cm`)이므로
**cm → m 변환 책임은 측정 데스크탑(전송 측)에 있다**. 예: `72.3cm` → `"cargoHeight": 0.723`.

백엔드는 단위를 추측해 자동 변환하지 **않는다** — `72.3`이 오면 72.3m로 저장한다. 자동으로 100을 나누면
진짜 대형 화물과 단위 실수를 구분할 수 없다.

**팔레트 높이는 `cargoHeight`에 포함하지 않는다.** 적재 높이 판정에서 백엔드가 설정값
`storage.placement.pallet-height-m`(기본 `0.12`, 환경변수 `STORAGE_PLACEMENT_PALLET_HEIGHT_M`)을
`PlacementService`에서 **정확히 한 번** 더한다.

```
requiredHeight = cargoHeight + storage.placement.pallet-height-m + storage.placement.height-clearance
```

비전의 `total_height_cm`(화물+파렛트)을 `cargoHeight`에 넣으면 팔레트 높이가 **두 번** 더해진다.

### 세션 연결 — 요청은 sessionId를 보내지 않는다

```
POST /api/stations/measurements
  → StationSessionMapper.findActiveSession()
  → activeSession.sessionId 를 StationMeasurement.sessionId 에 설정
  → INSERT
```

활성 세션이 없으면 **409 `STATION_SESSION_NOT_ACTIVE`** 이고 저장하지 않는다. 세션을 새로 만들어 붙이지
않는다 — 어느 화물의 측정인지 알 수 없기 때문이다. 세션은 `POST /api/stations/sessions?cargoId=...`로 먼저 연다.

> ⚠️ **late arrival을 완전히 막을 수 없다.** 요청이 `sessionId`를 전달하지 않으므로, 화물 A의 측정 결과가
> 세션 A 종료·세션 B 시작 이후에 늦게 도착하면 백엔드는 그것을 **세션 B의 결과로 저장**한다. 현재 저장
> 구조로는 이를 판별할 근거가 없다(`measurementId`에 세션 정보가 없고, `measured_at` 컬럼도 없어 세션
> 시작 시각과 비교할 수 없다). 완전 차단이 필요하면 요청에 `sessionId`를 포함시켜 `active_session_id`와
> 대조해야 한다.

### 중복 measurement_id 정책

DB UNIQUE + Service 사전 확인(`existsByMeasurementId`)으로 이중 방어. 동일 `measurementId` 재전송 시
**409 `STATION_MEASUREMENT_ID_DUPLICATED`** 를 반환하고 **기존 행을 갱신하지 않으며, 새 행도 만들지 않고,
WebSocket 재발행도 하지 않는다.**

(MQTT 시절에는 응답할 상대가 없어 "경고 로그 후 무시"였다. REST는 호출자가 결과를 알아야 하므로 바뀌었다.)

### 오류 처리

REST 경로는 `BusinessException`을 **삼키지 않는다** — `GlobalExceptionHandler`가 HTTP 상태로 옮긴다.

| ErrorCode | HTTP |
|---|---|
| `STATION_MEASUREMENT_INVALID`, `STATION_MEASUREMENT_STATUS_INVALID`, `STATION_MEASUREMENT_DIMENSIONS_INVALID` | 400 |
| `STATION_MEASUREMENT_ID_DUPLICATED`, `STATION_SESSION_NOT_ACTIVE`, `STATION_ALREADY_OCCUPIED` | 409 |
| `STATION_MEASUREMENT_NOT_FOUND`, `STATION_SESSION_NOT_FOUND` | 404 |

### measured_at 미저장

현재 `station_measurement`에는 **`measured_at` 컬럼이 없다**. `created_at`(수신 시각)만 남으므로:

- 장비 측정 시각은 **저장되지 않는다**(DTO에서 받아 검증만 하고 버린다)
- 최신 조회 정렬은 `sequence_no DESC`(**수신 순서**)를 쓴다
- 중복 판정에도 쓰지 않는다(`measurement_id`만 본다)
- 결과적으로 **실제 측정 시각 추적이 불가능**하다

컬럼 추가는 이번 범위 밖이다.

### DB 저장 테이블

`station_measurement` 단일 테이블(FR-202 8컬럼). 옛 `station_measurement_box`·`pallet_*`·
`measured_at_utc`/`measured_at_offset_minutes`는 FR-202 재설계에서 이미 사라졌다.

| REST 필드 | DB 컬럼 | 변환 |
|---|---|---|
| measurementId | `measurement_id` | 없음 |
| (활성 세션) | `session_id` | 백엔드가 조회해 주입 |
| status | `status` | 소문자 → enum → **대문자** |
| cargoHeight | `cargo_height` | 없음(m 그대로) |
| tippingLevel | `tipping_level` | 소문자 → **대문자**(DB CHECK가 대문자만 허용) |
| overhangRatio | `overhang_ratio` | 없음 |
| (서버 시각) | `created_at` | `LocalDateTime.now()` |

### WebSocket destination

| destination | eventType | payload |
|---|---|---|
| `/topic/stations/measurements`(전체) | `STATION_MEASUREMENT_COMPLETED` | `RealtimeEvent<StationMeasurementResponse>` |
| `/topic/stations/{sessionId}/measurements`(세션별) | 동일 | 동일 |

저장이 성공한 경우에만 1회 발행한다. 검증 실패·중복(409)에는 발행하지 않는다. 전송 실패는 Broadcaster가
잡아 로그만 남긴다(커밋된 저장이 전송 실패로 뒤집히지 않게).

### 조회 REST API

| Method | URL | 설명 | 오류 |
|---|---|---|---|
| GET | `/api/stations/measurements/{measurementId}` | measurement 단건 | 없음 → 404 |
| GET | `/api/stations/sessions/{sessionId}/measurements/latest` | 세션 최신(`sequence_no` 기준) | 없음 → 404 |
| POST | `/api/stations/sessions?cargoId=...` | 세션 열기 | 점유 중이면 409 |
| DELETE | `/api/stations/sessions/{sessionId}` | 세션 닫기 | 활성 아니면 409 |
| GET | `/api/stations/sessions/active` | 현재 활성 세션 | 없으면 409 |

### `cargo/detected`(AI 화물 분석) 규격과의 차이

| 항목 | cargo/detected (AI) | 측정 스테이션 |
|---|---|---|
| 수신 경로 | **MQTT** `cargo/detected` | **REST** `POST /api/stations/measurements` |
| JSON 키 | camelCase | camelCase |
| 식별자 | analysisId | measurementId (+ 활성 sessionId) |
| 상태 값 | ok/no_detection/unreliable | ok/**dimensions_only**/no_detection/unreliable |
| 높이 단위 | cm | **m(화물만)** |
| 저장 테이블 | ai_cargo_analysis / ai_cargo_detection_box | station_measurement |
| WebSocket | `/topic/ai/cargo-analysis` | `/topic/stations/measurements` |

### 외부 연동 확인 필요 / 합의 필요

- `외부 연동 확인 필요`: 측정 데스크탑의 실제 REST 호출, 프론트 STOMP 구독·표시.
- 합의 필요:
  1. 측정 데스크탑이 **cm → m 변환**을 실제로 수행하는지(백엔드는 변환하지 않는다)
  2. `tippingLevel` 대소문자 — 백엔드가 둘 다 받아 대문자로 저장하므로 **차단 요인은 아님**
  3. late arrival 차단이 필요하면 요청에 `sessionId`를 포함할지
  4. `measured_at` 컬럼 추가 여부
---

_기준: 이 문서는 2026-07-24 워킹트리의 운영 코드를 근거로 작성됐다. DTO/토픽/destination이 코드에서
바뀌면 이 문서도 함께 갱신할 것._

---

# EC2·Isaac MQTT 연동 규격 (2026-07-30 추가)

> prompt93 39~43항. **새 파일을 만들지 않고 이 문서에 덧붙였다**(기존 통신 규격 문서가 이미 있으므로).
> 아래 내용은 **현재 실행 코드 기준**이며, 이 문서 앞부분(2026-07-24 기준)에는
> FR-202 전환으로 **삭제된 토픽**(`cargo/detected`, `forklift/+/load-safety`)이 남아 있으니 주의할 것.

## 1. Broker 연결

| 항목 | 값 | 설정 키 |
|---|---|---|
| Host/Port | 환경변수로 주입(기본 `tcp://localhost:1883`) | `MQTT_BROKER_URL` |
| TLS | **미사용**(기본 URL 이 `tcp://`). TLS 로 갈 경우 `ssl://…:8883` 으로 주입 | `MQTT_BROKER_URL` |
| 인증 | username/password **선택**. 비어 있으면 `MqttConfig` 가 옵션에 아예 넣지 않는다 | `MQTT_USERNAME` / `MQTT_PASSWORD` |
| clientId | 인바운드/아웃바운드 분리 (`fast-backend-inbound` / `fast-backend-outbound`) | `MQTT_INBOUND_CLIENT_ID` / `MQTT_OUTBOUND_CLIENT_ID` |
| clean session | `true` | `mqtt.clean-session` |
| automatic reconnect | `true` | `mqtt.automatic-reconnect` |
| connection timeout | 10초 | `mqtt.connection-timeout` |
| keep alive | 30초 | `mqtt.keep-alive-interval` |
| 구독 QoS | **1** (모든 구독 토픽 동일) | `MQTT_DEFAULT_QOS` |
| Retained | 발행은 **false** 고정. 수신 시 retained 플래그를 **로그로 남긴다** | `VehicleCommandPublisher.COMMAND_RETAINED` |

**실제 호스트·계정·인증서 값은 이 문서에 기록하지 않는다.** 환경변수로만 주입한다.

## 2. 차량 위치 토픽

- **Topic**: `forklift/REAL-F01/location` (백엔드 구독 패턴은 `forklift/+/location` — 다중 차량 유지)
- **QoS**: 1 · **Retained**: false
- **Payload**
```json
{
  "vehicleId": "REAL-F01",
  "position": { "x": 1.0, "y": 2.0, "frameId": "map" },
  "heading": 0.0,
  "messageAt": "2026-07-27T10:30:45.304+09:00"
}
```
- **필드 타입**: `vehicleId` String / `position.x`,`position.y` Double / `position.frameId` String /
  `heading` Double / `messageAt` ISO-8601 offset 문자열
- **단위**: x·y = **m**, heading = **degree**
- **좌표계**: ROS `map` 프레임 (`frameId` 는 반드시 `"map"`)
- **heading 기준**: **0° = +X, 90° = +Y**. 백엔드는 저장 전 `[0, 360)` 으로 정규화한다
- **vehicleId 규칙**: `REAL-F01` (하이픈). `REAL_F01`(underscore)은 **거부**하며 자동 보정하지 않는다
- **messageAt 형식**: `OffsetDateTime`. 절대시각(Instant)으로 비교한다

## 3. Isaac 수정 사항

| 기존(잘못된 형식) | 변경 후 |
|---|---|
| `REAL_F01` | **`REAL-F01`** |
| 최상위 `x`, `y` | **`position.x`, `position.y`** |
| `direction` (radian) | **`heading` (degree)** |
| frameId 없음 | **`position.frameId = "map"` 필수** |
| retained 미지정 | **`retained = false`** |

- **radian → degree 변환은 발행 측(Isaac)이 한다.** 백엔드는 단위를 추측해 변환하지 않는다
- `messageAt` 은 **단조 증가**해야 한다(뒤로 가면 stale 로 무시된다)

## 4. 백엔드 검증

| 검증 | 실패 시 |
|---|---|
| topic vehicleId == payload vehicleId | 경고 로그 후 폐기 (`Vehicle ID mismatch…`) |
| vehicleId 형식(underscore 금지) | `rejected reason=vehicleId uses underscore…` |
| `frameId == "map"` (null·blank·odom 포함 그 외 전부 거부) | `rejected reason=UNSUPPORTED_FRAME_ID … expectedFrameId=map` |
| 좌표 유한값(NaN/Inf 금지) | 폐기 |
| `messageAt` 필수 | 폐기 |
| stale(과거 Instant) | `rejected reason=STALE_MESSAGE_AT …` |
| duplicate(동일 Instant) | `rejected reason=DUPLICATE_MESSAGE_AT …` |
| 미등록 차량 | `rejected reason=vehicle not registered …` |

stale/duplicate 는 **서비스(절대시각 비교)와 SQL(조건부 upsert)** 양쪽에서 막는다.

## 5. station measurement — **MQTT 아님(REST)**

이 절의 옛 내용(`fast/station/{station_id}/measurement` 토픽, `StationMeasurementMessage` snake_case DTO,
"tippingLevel/overhangRatio 는 수신 규격에 없어 항상 null 저장")은 **폐기됐다**. 전체 규격은
§Measurement Station v2.0 (REST) 참고. 요약하면:

- **수신**: `POST /api/stations/measurements` (MQTT 구독 없음)
- **DTO**: `StationMeasurementCreateRequest` — **camelCase**
- **tippingLevel**: 요청값을 받아 **대문자로 정규화해 실제 저장**한다(더 이상 null 하드코딩이 아니다)
- **overhangRatio**: 요청값을 검증(`>= 0`, finite, 상한 없음)해 **실제 저장**한다
- **단위**: `cargoHeight` = **meter, 화물만**. cm → m 변환은 측정 데스크탑 책임.
  팔레트 높이는 `storage.placement.pallet-height-m`(0.12)를 `PlacementService`가 한 번만 더한다
- **status**: `ok` / `dimensions_only` / `no_detection` / `unreliable` — status별 null 강제는 v2.0 절 표 참고
- **남은 미확정 항목**
  1. 측정 데스크탑이 cm → m 변환을 실제로 수행하는지(**외부 연동 확인 필요**)
  2. late arrival 차단을 위해 요청에 `sessionId`를 넣을지
  3. `measured_at` 컬럼 추가 여부(현재 미저장)

## 6. 테스트 방법

### 6.1 mosquitto_pub (개발, TLS 없음)

```bash
mosquitto_pub   -h <BROKER_HOST> -p <BROKER_PORT>   -t "forklift/REAL-F01/location"   -q 1   -m '{"vehicleId":"REAL-F01","position":{"x":1.0,"y":2.0,"frameId":"map"},"heading":0.0,"messageAt":"2026-07-27T10:30:45.304+09:00"}'
```

> `-r` 옵션은 **쓰지 않는다**. mosquitto_pub 은 기본이 retained=false 이고,
> `-r false` 같은 문법은 존재하지 않는다(`-r` 은 값 없는 플래그다).

### 6.2 TLS 환경

```bash
mosquitto_pub   -h <BROKER_HOST> -p 8883   --cafile <CA_CERT_PATH>   -t "forklift/REAL-F01/location"   -q 1   -m '{"vehicleId":"REAL-F01","position":{"x":1.0,"y":2.0,"frameId":"map"},"heading":0.0,"messageAt":"2026-07-27T10:30:45.304+09:00"}'
```

클라이언트 인증서를 쓰면 `--cert`, `--key` 를, 계정 인증이면 `-u`, `-P` 를 추가한다.
**실제 비밀번호·개인키 경로는 커밋하지 않는다.**

### 6.3 백엔드 수신 확인 절차

1. 기동 로그에서 MQTT 연결 성공 확인
2. 구독 로그에서 `forklift/+/location` 포함 확인
3. Isaac 또는 mosquitto_pub 으로 발행
4. `MQTT message received: topic=…, qos=…, retained=…` 로그 확인
5. `[VehicleLocation] received …` → `[VehicleLocation] updated …` 로그 확인(거부 시 `rejected reason=…`)
6. DB 확인

```sql
SELECT vehicle_id, position_x, position_y, heading, message_at, received_at
  FROM vehicle_current_status
 WHERE vehicle_id = 'REAL-F01';
```

7. REST/WebSocket 에서 최신 위치 확인

### 6.4 실패 시나리오 (기대 동작)

| 발행 내용 | 기대 결과 |
|---|---|
| `REAL_F01` | vehicleId 형식 오류로 폐기(자동 보정 없음) |
| `frameId: "odom"` | `UNSUPPORTED_FRAME_ID` 로 폐기(저장·중계 모두 안 함) |
| frameId 누락 | 동일하게 폐기(map 으로 보정하지 않음) |
| topic `REAL-F01` / payload `REAL-F02` | topic-payload mismatch 로 폐기 |
| 같은 `messageAt` 재발행 | `DUPLICATE_MESSAGE_AT` 무시 |
| 더 오래된 `messageAt` | `STALE_MESSAGE_AT` 무시 |
| radian heading (`1.57`) | **자동 변환하지 않는다.** 1.57° 로 저장되므로 Isaac 이 degree 로 변환해 발행해야 한다 |

### 6.5 두 도메인의 독립성 (44항)

- 차량 위치 `messageAt` = **위치 최신성 판정용**
- station measurement 측정 시각 = **측정 이력 정렬·추적용**
- 서로의 시각으로 stale/duplicate 를 판정하지 않는다. DTO 도 통합하지 않는다
