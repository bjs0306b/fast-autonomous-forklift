# PROJECT IMPLEMENTATION AUDIT

## 1. 조사 기준 경로

`C:\SSAFY\fast-backend`

조사 범위에 포함한 것: `pom.xml`, `src/main/java/**`, `src/main/resources/**`, `src/test/java/**`,
`src/test/resources/**`, `docs/**`, `README.md`, `*.md`, `*.sql`, `*.yml`, `ros2_ws/**`, `isaac_sim/**`,
`firmware/**`, `ai/**`.

판단에서 제외한 것(프롬프트 지정): `.git/`, `.idea/`, `target/`, `build/`, `out/`, `node_modules/`,
`recovery/`, `recovery-today/`, `.omx/`, `.ai/`.

Gradle 파일(`build.gradle`, `settings.gradle`, `gradlew`)은 **저장소에 존재하지 않는다**. 이 프로젝트는
Maven 단일 모듈이다. `docker-compose*`, `Dockerfile*`, `mosquitto.conf` 역시 **저장소에 존재하지 않는다**
(문서 본문에서 Mosquitto를 언급할 뿐 설정 산출물 파일이 없다).

## 2. 조사 시점 기준

- 조사일: **2026-07-24**
- 판단 근거: 위 경로에 **현재 존재하는 파일의 실제 내용**만 사용했다.
- Git 브랜치·reflog·commit history·GitLab MR은 **조사하지 않았다**.
- 다른 브랜치의 파일을 가져오거나 비교하지 않았다.
- 조사 과정에서 프로젝트 파일을 **수정·삭제하지 않았다**. commit/push/pull/merge/reset/checkout/
  restore/clean을 **실행하지 않았다**. 이 보고서 파일 생성과 `mvn test`가 만든 `target/` 빌드 산출물이
  유일한 파일 시스템 변화다.

## 3. 전체 구현 현황 요약

| 번호 | 기능 | 판정 | 구현률 추정 | 핵심 근거 파일 | 주요 누락 사항 |
|---:|---|---|---:|---|---|
| 1 | 비상 정지 명령 처리 | 부분 구현 | 75% | `embedded/service/EmbeddedCommandService.java`, `EmbeddedCommandResultService.java`, `domain/EmbeddedCommandType.java` | 전용 비상정지 API 없음(공통 명령 API 경유), `forklift/%s/emergency` 토픽 미사용, 실제 차량 정지 미검증 |
| 2 | Spring Boot 골격 | 완료 | 100% | `pom.xml`, `FastBackendApplication.java`, `common/**`, `application*.yml` | 없음 |
| 3 | DB 스키마 | 완료 | 100% | `src/main/resources/db/schema.sql`(10 테이블), `mapper/*.xml`(10개) | migration 도구(Flyway/Liquibase) 없음, 실 MySQL 자동 적용 안 함(의도적) |
| 4 | 통신 규격 문서 | 완료 | 95% | `docs/backend-message/communication-protocol.md`(633줄) | heading 단위·timestamp 타임존·QoS/retained 최종값이 문서상 `미확정` |
| 5 | 상태 이력 저장 | 완료 | 100% | `vehicle/service/VehicleStatusService.java`, `mapper/VehicleStatusHistoryMapper.xml` | 없음 |
| 6 | 상태 이력 조회 API | 부분 구현 | 80% | `VehicleController#statusHistory`, `VehicleStatusHistoryService.java` | 페이지네이션(offset/page) 없음, 날짜 범위 필터 없음, 정렬 고정 |
| 7 | 다중 차량 관리·집계 | 완료 | 100% | `VehicleController.java`, `VehicleService.java`, `VehicleCurrentStatusMapper.xml` | 없음 |
| 8 | MQTT 브로커·구독 | 부분 구현 | 70% | `config/mqtt/MqttConfig.java`, `application-local.yml` | **docker-compose·mosquitto.conf 파일 자체가 없음**, 브로커 구동 검증 불가 |
| 9 | ROS2-MQTT-WebSocket 규격 | 부분 구현 | 65% | `communication-protocol.md`, 각 DTO, `isaac_sim/scripts/twin_bridge.py` | ROS2측 MQTT 발행 코드 없음(UART teleop만), 식별자 키·차량 ID 불일치 미해소 |
| 10 | MQTT 브리지 | 완료 | 95% | `mqtt/inbound/**`, `mqtt/outbound/**`, `MqttMessageRouter.java` | 실제 브로커 송수신 검증은 외부 환경 필요 |
| 11 | WebSocket 상태 Push | 완료 | 95% | `config/websocket/WebSocketConfig.java`, `vehicle/websocket/VehicleWebSocketBroadcaster.java` | SimpleBroker(단일 인스턴스 한정), origin 기본값 `*`, 실제 브라우저 검증 없음 |

**집계**: 완료 7 / 부분 구현 4 / 뼈대만 존재 0 / 문서만 존재 0 / 미구현 0 / 확인 불가 0

## 4. 기능별 상세 결과

각 기능의 전체 상세(발견 파일, 실제 구현 흐름, 구현된 내용, 누락·끊어진 내용, 테스트 근거, 판정 이유)는
`prompt/answer/answer31.md`에 동일 기준으로 기록했다. 이 절은 핵심만 요약한다.

### [1] 비상 정지 명령 처리 — 부분 구현

`EmbeddedCommandType.EMERGENCY_STOP`이 실제 enum 상수로 존재하고, 전용 API가 아니라 공통 명령 API
`POST /api/vehicles/{forkliftId}/embedded-commands`에 `{"command":"EMERGENCY_STOP"}`를 실어 처리한다.
백엔드 체인(차량 검증 → UUID commandId 생성 → `embedded_vehicle_command` insert(PENDING) → MQTT publish →
PUBLISHED/PUBLISH_FAILED 기록 → `forklift/+/command-result` 수신 → 상태 전이 검증 → update →
`/topic/vehicles/result` push)은 **끊김 없이 연결되어 있고 테스트도 있다**.

끊어진 지점은 백엔드 밖이다. `forklift/{id}/command`를 구독해 실제로 모터를 멈추는 코드가 이 저장소에
없다(`ros2_ws`는 UART teleop 전용, `firmware/esp32`는 MQTT를 쓰지 않는다). `communication-protocol.md`
§4.1도 "`PUBLISHED`는 브로커 발행 호출 성공이며 ROS2 수신·모터 정지·하드웨어 안전을 뜻하지 않는다"고
명시한다. **"백엔드 명령 생성·발행까지만 있음"이 정확한 상태이며, "실제 차량 정지까지 검증됨"은 아니다.**

### [2] Spring Boot 프로젝트 골격 — 완료

Java 21, Spring Boot 3.3.4, Maven 단일 모듈. Web / Validation / Integration+MQTT(Paho 1.2.5) /
WebSocket / MyBatis 3.0.3 / mysql-connector-j / jackson-jsr310 / starter-test / integration-test / H2 모두
`pom.xml`에 실재한다. Controller-Service-Mapper 계층, 공통 응답(`ApiResponse`/`ErrorResponse`), 예외 체계
(`BusinessException`/`ErrorCode`/`GlobalExceptionHandler`), 프로필 3종(`application.yml`/`-local`/`-test`)이
모두 구현되어 있다.

### [3] DB 스키마 초안 — 완료

`schema.sql`에 10개 테이블이 실제 DDL로 존재하고, 대응하는 Mapper XML 10개가 모두 있다. 이 DDL은 테스트
프로필에서 H2에 매번 실행되므로 **문법적 실행 가능성이 자동 검증된다**. 초안 수준을 넘어 운영 가능한
스키마다.

### [4] 통신 토픽·메시지 규격 문서화 — 완료

`docs/backend-message/communication-protocol.md`(633줄)가 MQTT 토픽 9종, WebSocket destination, JSON
예시, 필수 필드표, enum 10종, QoS/retained, 오류 규격, 미확정 항목 9개를 코드 기준으로 정리한다.
문서 서두에 "코드와 문서가 어긋나면 코드가 옳다"고 명시되어 있고, 실제로 대조한 결과 코드와 일치했다.

### [5] 차량 상태 이력 테이블·저장 로직 — 완료

`vehicle_status_history` 테이블 + domain + mapper + INSERT SQL + service 호출이 모두 실재하며, MQTT 상태
수신 경로가 이 저장 로직을 실제로 호출한다. current status upsert와 history insert가 **같은
`@Transactional`** 안에 있어 부분 성공이 발생하지 않는다(롤백 통합 테스트로 검증됨).

### [6] 차량별 상태 이력 조회 API — 부분 구현

`GET /api/vehicles/{vehicleId}/status-history?limit=`가 Controller → Service → Mapper → DB로 완전히
연결되어 있고 미등록 차량은 404를 반환한다. 다만 **페이지네이션·날짜 범위 필터·정렬 선택이 없다**
(limit 1~200 단독, `ORDER BY message_at DESC, id DESC` 고정).

### [7] FR-501-1 다중 차량 등록·상태 집계 — 완료

등록/목록/상세/활성변경/상태별 집계 5개 API가 모두 실동작한다. 중복 등록은 애플리케이션 사전 확인
(`existsByVehicleId`) + DB UNIQUE로 이중 방어한다. 집계는 `LEFT JOIN + GROUP BY`로 상태 행이 없는 차량도
UNKNOWN으로 포함하고, 5개 상태를 항상 0으로 채워 응답한다.

### [8] MQTT 브로커 구축·구독 연결 — 부분 구현

**연결·구독 코드는 완료다**: 브로커 주소/인증/clientId 분리/connect options/자동 재연결/cleanSession/
wildcard 8토픽 구독/QoS/message callback/오류 채널/연결 이벤트 리스너가 전부 구현되어 있다.

**브로커 "구축" 산출물이 없다**: `docker-compose.yml`, `Dockerfile`, `mosquitto.conf`가 저장소에
존재하지 않는다. README와 `answer11.md`가 설치 절차를 산문으로 설명할 뿐, 재현 가능한 설정 파일이 없다.
외부 브로커의 실제 구동 여부는 현재 파일만으로 확인 불가하다.

### [9] ROS2↔MQTT↔WebSocket 통신 규격 합의 — 부분 구현

규격 자체는 문서와 DTO로 정리되어 있다. 그러나 **실제 ROS2 MQTT 발행 코드가 이 저장소에 없다** —
`ros2_ws/src/forklift_teleop`는 UART 텔레옵 전용이고 MQTT를 전혀 쓰지 않는다. 따라서 이 항목은
**백엔드 문서와 DTO 기준으로만 확인되었다**. 유일한 실제 발행자는 `isaac_sim/scripts/twin_bridge.py`이며,
아래 §9에 기록한 구체적 불일치가 있다.

### [10] MQTT 상태 수신·명령 발행 브리지 — 완료(수신·발행 각각)

**수신**: Adapter → `mqttInputChannel` → `MqttMessageReceiver` → `MqttMessageRouter`(토픽 8종 분기 +
topic/payload 식별자 일치 검증) → 도메인 Service → DB → WebSocket. 잘못된 JSON은 해당 메시지만 폐기한다.

**발행**: REST Controller → Service → DTO → `MqttPublisher`(Jackson 직렬화) → `MqttGateway` →
`mqttOutboundChannel` → `MqttPahoMessageHandler` → `forklift/{id}/command`. 결과는 `command-result`
구독으로 되돌아와 DB update + WebSocket push까지 이어진다.

### [11] 관제 WebSocket 실시간 상태 Push — 완료

`/ws`(SockJS), app prefix `/app`, broker prefix `/topic`, 전체/차량별 destination 이중 전송,
`SimpMessagingTemplate` 기반 Broadcaster, **`TransactionSynchronization.afterCommit()`으로 DB 커밋 이후
전송**, 전송 예외는 Broadcaster가 흡수, origin은 설정값으로 분리. 요구 항목이 모두 코드로 존재한다.

## 5. 발견한 API 목록

| Method | URL | Controller | 활성 조건 |
|---|---|---|---|
| GET | `/api/health` | `HealthController` | 항상 |
| POST | `/api/vehicles` | `VehicleController` | 항상 |
| GET | `/api/vehicles` | `VehicleController` | 항상 |
| GET | `/api/vehicles/status-counts` | `VehicleController` | 항상 |
| GET | `/api/vehicles/{vehicleId}` | `VehicleController` | 항상 |
| PATCH | `/api/vehicles/{vehicleId}/active` | `VehicleController` | 항상 |
| GET | `/api/vehicles/{vehicleId}/status-history?limit=` | `VehicleController` | 항상 |
| PUT | `/api/vehicles/{vehicleId}/status` | `VehicleStatusTestController` | `vehicle.status-test-api.enabled` |
| POST | `/api/vehicles/{forkliftId}/embedded-commands` | `EmbeddedCommandController` | 항상 |
| GET | `/api/vehicles/{forkliftId}/embedded-commands?limit=` | `EmbeddedCommandController` | 항상 |
| GET | `/api/vehicles/{forkliftId}/embedded-commands/{commandId}` | `EmbeddedCommandController` | 항상 |
| GET | `/api/vehicles/{forkliftId}/fork-status` | `EmbeddedCommandController` | 항상 |
| GET | `/api/vehicles/{forkliftId}/embedded-errors?limit=` | `EmbeddedCommandController` | 항상 |
| GET | `/api/ai/cargo-analysis/{analysisId}` | `AiCargoAnalysisController` | 항상 |
| GET | `/api/cargos/{cargoId}/ai-analysis/latest` | `AiCargoAnalysisController` | 항상 |
| GET | `/api/stations/measurements/{measurementId}` | `StationMeasurementController` | 항상 |
| GET | `/api/stations/{stationId}/measurements/latest` | `StationMeasurementController` | 항상 |
| POST | `/api/mqtt/test` | `MqttTestController` | `mqtt.test-api.enabled` |

**비상 정지 전용 API는 존재하지 않는다.** `POST /api/vehicles/{forkliftId}/embedded-commands`에
`command: "EMERGENCY_STOP"`을 실어 보내는 방식이다.

## 6. 발견한 MQTT topic 목록

설정 출처: `application-local.yml`의 `mqtt.topics.*` → `MqttProperties.Topics` → `MqttTopics`.

| 토픽 | 방향 | 코드에서 실제 사용 | QoS | retained |
|---|---|---|---|---|
| `forklift/+/status` | 구독 | O — `routeStatus` | 1 | — |
| `forklift/+/location` | 구독 | O — `routeLocation` | 1 | — |
| `forklift/+/path` | 구독 | O — `routePath` | 1 | — |
| `forklift/+/command-result` | 구독 | O — `routeCommandResult` | 1 | — |
| `forklift/+/fork-status` | 구독 | O — `routeForkStatus` | 1 | — |
| `forklift/+/error` | 구독 | O — `routeEmbeddedError` | 1 | — |
| `cargo/detected` | 구독 | O — `routeCargoDetected` | 1 | — |
| ~~`fast/station/+/measurement`~~ | ~~구독~~ | **X — 폐기됨(2026-07-31 REST 전환). 코드에 없음** | — | — |
| `forklift/%s/command` | 발행 | O — `Embedded`/`IsaacForkliftCommandPublisher` | 1 | false |
| `forklift/%s/emergency` | — | **X — 정의만 존재, 발행·구독 코드 없음** | — | — |

구독 8개는 `MqttConfig.mqttInboundAdapter()`의 `topics` 배열에 실제로 전부 등록되어 있다.

## 7. 발견한 WebSocket endpoint/topic 목록

- **Endpoint**: `/ws` (SockJS, `WebSocketConfig#registerStompEndpoints`)
- **Application destination prefix**: `/app`
- **Broker destination prefix**: `/topic` (SimpleBroker)
- **Allowed origin**: `websocket.allowed-origin-patterns`, 로컬 기본값 `*`

| destination | eventType | 발신 코드 |
|---|---|---|
| `/topic/vehicles/status` + `/{id}` | `VEHICLE_STATUS_UPDATED` | `broadcastStatus`, `broadcastIsaacStatus` |
| `/topic/vehicles/location` + `/{id}` | `VEHICLE_LOCATION_UPDATED` | `broadcastLocation`, `broadcastIsaacLocation` |
| `/topic/vehicles/path` + `/{id}` | `VEHICLE_PATH_UPDATED` | `broadcastPath` |
| `/topic/vehicles/result` + `/{id}` | `VEHICLE_COMMAND_RESULT_UPDATED` | `broadcastEmbeddedCommandResult` |
| `/topic/vehicles/fork-status` + `/{id}` | `VEHICLE_FORK_STATUS_UPDATED` | `broadcastForkStatus` |
| `/topic/vehicles/errors` + `/{id}` | `VEHICLE_ERROR_OCCURRED` | `broadcastEmbeddedError` |
| `/topic/ai/cargo-analysis` + `/{cargoId}` | (봉투 없음) | `AiCargoAnalysisBroadcaster` |
| `/topic/stations/measurements`, `/topic/stations/{stationId}/measurements` | `STATION_MEASUREMENT_COMPLETED` | `StationMeasurementBroadcaster` |

`VehicleWebSocketBroadcaster#broadcastCommandResult`(`VehicleCommandResultEventData`)는 정의만 있고
호출하는 프로덕션 코드가 없다.

## 8. 발견한 DB table 목록

출처: `src/main/resources/db/schema.sql` (전부 실제 `CREATE TABLE` DDL로 존재)

| 테이블 | 역할 | PK | FK | UNIQUE / INDEX | 시간 컬럼 | 상태 컬럼 |
|---|---|---|---|---|---|---|
| `vehicle` | 차량 기준정보 | `id` | — | UQ `vehicle_id` | created_at, updated_at | `source`, `active` |
| `vehicle_current_status` | 차량별 최신 상태 | `vehicle_id` | → `vehicle.vehicle_id` | PK | message_at, received_at, updated_at | `status` |
| `vehicle_status_history` | 차량 상태 누적 이력 | `id` | → `vehicle.vehicle_id` | IDX `(vehicle_id, message_at DESC)` | message_at, received_at, created_at | `status` |
| `ai_cargo_analysis` | AI 화물 분석 부모 | `id` | — | UQ `analysis_id`, IDX `(cargo_id, processed_at DESC)` | captured_at, processed_at, received_at, created_at | `status`, `dimension_scale` |
| `ai_cargo_detection_box` | AI 감지 박스 | `id` | → `ai_cargo_analysis.id` | — | created_at | — |
| `station_measurement` | 측정 스테이션 결과 부모 | `id` | — | UQ `measurement_id`, IDX `(station_id, measured_at_utc DESC)` | measured_at_utc, received_at, created_at | `status`, `eccentric` |
| `station_measurement_box` | 스테이션 감지 박스 | `id` | → `station_measurement.id` | — | created_at | — |
| `embedded_vehicle_command` | 임베디드 명령·결과 | `id` | — | UQ `command_id`, IDX `(forklift_id, issued_at DESC)` | issued_at, published_at, completed_at, created_at, updated_at | `command`, `status` |
| `vehicle_fork_current_status` | 포크 최신 상태 | `forklift_id` | — | PK | message_at, received_at, updated_at | `fork_state`, `limit_bottom` |
| `embedded_error_history` | 임베디드 오류 이력 | `id` | — | IDX `(forklift_id, occurred_at DESC)` | occurred_at, received_at, created_at | `error_source`, `severity` |

**총 10개 테이블.** 오류 관련 테이블은 `embedded_error_history`, 명령 관련은 `embedded_vehicle_command`,
측정 관련은 `station_measurement`/`ai_cargo_analysis` 계열이다.

Java enum이 존재하지만 DB에는 전부 `VARCHAR`로 저장한다(MySQL ENUM 타입 미사용): `VehicleStatus`,
`VehicleSource`, `EmbeddedCommandType`, `EmbeddedCommandStatus`, `EmbeddedForkState`,
`EmbeddedErrorSource`, `EmbeddedErrorSeverity`, `EmbeddedStoppedAction`, `AiAnalysisStatus`,
`DimensionScale`, `LoadBalanceDirection`, `StationDirection`, `StationMeasurementStatus`,
`IsaacForkliftStatus`, `AiCargoAnalysis` 관련 enum.

## 9. 끊어진 호출 흐름

### 9-1. 명령 발행 → 실제 차량 동작 (백엔드 밖에서 끊김)

```
REST Controller → CommandService → command DB 저장 → MQTT publish(forklift/{id}/command)
    → [끊김] 이 토픽을 구독하는 코드가 이 저장소에 없음
    → command-result subscribe → 결과 상태 갱신 → WebSocket push   ← 백엔드 쪽은 준비 완료
```

`ros2_ws/src/forklift_teleop`는 UART 텔레옵 전용이며 MQTT를 쓰지 않는다. `firmware/esp32/motor_controller`
역시 MQTT가 아니라 UART로 통신한다. 즉 **명령이 실제 모터까지 도달하는 경로가 저장소 안에 없다.**

### 9-2. Isaac Sim 브리지 ↔ 백엔드 차량 ID 불일치

`isaac_sim/scripts/twin_bridge.py`는 `SIM_ID = "SIM_F01"`(**언더스코어**)로 `forklift/SIM_F01/status`에
발행한다. 그런데 백엔드 시드 데이터(`db/data-local.sql`)에 등록된 차량은 `SIM-F01`, `SIM-F02`
(**하이픈**)다.

```
twin_bridge.py publish(forklift/SIM_F01/status)
    → MqttMessageRouter.routeStatus → 토픽/payload 식별자 일치 (둘 다 SIM_F01) → 통과
    → ForkliftStatusService → VehicleStatusService.updateCurrentStatus("SIM_F01")
    → [끊김] vehicleMapper.findByVehicleId("SIM_F01") → VEHICLE_NOT_FOUND
    → 경고 로그로 다운그레이드 후 메시지 폐기 → DB 저장·WebSocket 전송 모두 일어나지 않음
```

### 9-3. Isaac 상태 payload가 Isaac 경로로 분기되지 않음

`MqttMessageRouter.isIsaacStatusPayload()`는 `forkHeight`/`hasCargo`/`footprint` 키가 있거나 `battery`
키가 **없을 때** Isaac으로 판별한다. `twin_bridge.py`가 보내는 상태 payload는
`{forkliftId, status, battery, timestamp}`로 **`battery`가 있고 Isaac 판별 키는 없다** → ROS2 경로
(`ForkliftStatusMessage`)로 분기된다. 두 DTO 모두 `forkliftId` 키를 쓰므로 역직렬화 자체는 성공하지만,
**설계 의도와 실제 분기가 어긋난다.**

### 9-4. `forklift/%s/emergency` 토픽 미사용

설정과 `MqttTopics.forkliftEmergency()`에 정의는 있으나, 이를 발행하거나 구독하는 프로덕션 코드가
전혀 없다(참조는 `MqttTopicsTest` 뿐). 정의부터 사용처까지 흐름이 아예 시작되지 않는다.

### 9-5. `VehicleStatusAdapter` 인터페이스 구현체 없음

`vehicle/mqtt/VehicleStatusAdapter.java`는 구현체가 하나도 없는 확장 포인트다. Javadoc에 "구현체가 아직
없다"고 명시되어 있고 TODO가 남아 있다 — **뼈대만 존재하는 유일한 요소**다. 다만 현재 MQTT 상태 처리는
이 인터페이스를 거치지 않고 `ForkliftStatusService`/`IsaacForkliftStatusService`가 직접 처리하므로
실동작에 공백은 없다.

### 9-6. `broadcastCommandResult` 미호출

`VehicleWebSocketBroadcaster#broadcastCommandResult`는 호출하는 프로덕션 코드가 없다(임베디드 결과는
`broadcastEmbeddedCommandResult`를 쓴다). 기능 공백은 아니고 사용되지 않는 메서드다.

## 10. 미구현 항목

11개 조사 항목 중 **완전 미구현으로 판정된 것은 없다.** 각 항목 안에서 확인된 구체적 미구현 요소는
다음과 같다.

1. 비상 정지 **전용** REST 엔드포인트 (공통 명령 API로 대체됨)
2. `forklift/{id}/emergency` 토픽의 발행·구독 로직
3. 명령 중복 방지 정책 — `EMBEDDED_COMMAND_ID_DUPLICATED` ErrorCode와 `existsByCommandId` Mapper 메서드는
   있으나 `EmbeddedCommandService.issueCommand`가 이를 호출하지 않는다(commandId를 UUID로 새로 만들기
   때문에 실질 충돌 위험은 낮음)
4. 상태 이력 조회의 페이지네이션(offset/page)·날짜 범위 필터·정렬 옵션
5. `docker-compose.yml` / `Dockerfile` / `mosquitto.conf` 등 브로커·배포 인프라 파일 전체
6. DB migration 도구(Flyway/Liquibase) 및 버전 관리
7. ROS2측 MQTT 브리지 노드 (`forklift/{id}/command` 구독 + 상태 발행)
8. 차량 온라인/오프라인 자동 판정 Scheduler (`vehicle.offline-check-enabled` 설정값만 있고 이를 읽는
   로직이 없음 — `application-local.yml` 주석에도 명시됨)
9. MQTT 최초 수신 시 차량 자동 등록 (`vehicle.auto-registration-enabled` 설정값만 있고 참조 코드 없음 —
   설정 파일 주석에 "지금은 이 값을 참조하는 로직 자체가 없다"고 기록되어 있음)
10. `VehicleStatusAdapter` 구현체 2종(ROS2/Isaac)
11. 프론트엔드(React) — 이 저장소에 존재하지 않음

## 11. 외부 환경이 있어야 검증 가능한 항목

현재 파일만으로는 **확인 불가**하며, 코드 결함이 아니라 환경 의존이다.

| 항목 | 필요한 외부 환경 |
|---|---|
| MQTT 브로커 실제 송수신·구독 성공 | 구동 중인 Mosquitto (localhost:1883 또는 EC2) |
| 자동 재연결 실제 동작 | 브로커 강제 종료·재기동 시나리오 |
| MySQL 스키마 실제 적용 | EC2/로컬 MySQL 8 (schema.sql 수동 실행 전제) |
| ROS2가 발행하는 JSON이 DTO와 일치하는지 | 실제 ROS2 노드 |
| 임베디드 펌웨어가 command JSON을 해석하는지 | 실물 REAL01 장비 |
| **`EMERGENCY_STOP` 수신 후 실제 모터 정지** | 실물 장비 + ROS2 + MCU |
| 비상정지 해제 승인·네트워크 단절 fail-safe·중복 명령 정책 | 장비 통합 테스트 |
| STOMP 구독·수신 | 프론트엔드(React) 또는 STOMP 클라이언트 |
| Isaac Sim 트윈 브리지 실제 발행 | Isaac Sim + paho-mqtt |
| 측정 스테이션 PC 발행 | 스테이션 PC |

## 12. 다음 구현 우선순위

1. **(안전 최우선) 비상정지 end-to-end 연결** — `forklift/{id}/command`를 구독하는 ROS2/임베디드 측
   소비자를 만들고, `EMERGENCY_STOP` 수신 → 모터 정지 → `command-result` 응답까지 실장비로 검증한다.
   현재 백엔드는 발행까지만 보장하며, 이 상태를 "비상정지 구현 완료"로 오해하면 안전 사고로 이어진다.
   FR-503(비상 정지, High)의 실질 진척은 여기서 결정된다.
2. **Isaac 브리지 차량 ID·판별 규격 정합화** — `SIM_F01` vs `SIM-F01` 불일치는 지금도 모든 시뮬 메시지가
   조용히 폐기되는 실제 버그다(§9-2). 동시에 Isaac 상태 payload가 Isaac 경로로 분기되도록 판별 규칙 또는
   payload 규격을 맞춘다(§9-3). 양쪽 다 한 줄 수준의 수정으로 흐름이 살아난다.
3. **브로커·배포 인프라 파일화** — `docker-compose.yml` + `mosquitto.conf`를 저장소에 넣어 팀원이 동일
   환경을 재현할 수 있게 한다. 현재는 산문 문서뿐이라 §11의 검증 불가 항목 대부분이 여기서 막혀 있다.
4. **ROS2 MQTT 브리지 노드 신설** — `ros2_ws`에 MQTT↔ROS2 브리지 패키지를 추가한다. 항목 9의 "규격 합의"가
   문서 수준에 머무는 근본 원인이며, 1번의 전제 조건이기도 하다.
5. **상태 이력 조회 API 보강** — 페이지네이션(offset 또는 cursor), 날짜 범위(`from`/`to`), 정렬 옵션.
   FR-504 모니터링 대시보드가 붙으면 limit 50만으로는 부족해진다.
6. **차량 오프라인 자동 판정 Scheduler** — `vehicle.offline-check-enabled`/`offline-timeout-seconds`
   설정값이 이미 준비되어 있으나 이를 읽는 코드가 없다. 관제 화면에서 "죽은 차량"을 구분하려면 필요하다.
7. **DB migration 도구 도입 검토** — 테이블이 10개로 늘었고 팀 공용 MySQL을 쓰므로, 수동 DDL 실행 방식은
   스키마 드리프트 위험이 있다.
8. **운영 보안 정리** — WebSocket `allowed-origin-patterns` 기본값 `*`를 실제 프론트 Origin으로 좁히고,
   MQTT 인증 정보를 배포 시크릿으로 관리한다.

---

## 부록 A. 테스트 및 실행 가능성 확인

| 항목 | 결과 |
|---|---|
| 빌드 도구 | **Maven** (`pom.xml`). Gradle 파일 없음. Maven Wrapper(`mvnw`/`mvnw.cmd`)도 **없음** |
| Java 버전 | `pom.xml` `<java.version>21</java.version>` |
| 시스템 PATH의 `java`/`mvn` | **없음** (둘 다 인식되지 않음) |
| 사용한 JDK | `C:\Users\SSAFY\.jdks\ms-21.0.11` (Microsoft OpenJDK 21) |
| 사용한 Maven | IntelliJ IDEA 2026.1.4 번들 Maven 3 |
| 테스트 파일 수 | 69개 (`src/test/java/**/*.java`) |
| 실행 명령 | `mvn -f pom.xml test -B` (JAVA_HOME=ms-21.0.11) |
| **실행 결과** | **Tests run: 478, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS** |

환경 의존성 없이 전 테스트가 통과했다. 테스트 프로필이 `mqtt.enabled=false`로 실제 브로커 접속을 막고,
DB는 임베디드 H2(MySQL 호환 모드)에 `db/schema.sql`을 실행하므로 **MySQL·Mosquitto·Docker·ROS2 없이도
자체 완결적으로 동작한다.**

> 특기 사항: `docs/backend-message/communication-protocol.md`와 `answer13.md`는 "개발 PC의 JDK가 21이
> 아니라 JBR 25여서 Mockito inline mock 계측 실패로 서비스 단위 테스트가 실행되지 못한다"고 기록하고
> 있다. 이번 조사에서 **JDK 21(ms-21.0.11)을 명시적으로 지정해 실행한 결과 Mockito 테스트를 포함한 478개
> 전부가 통과했다**. 해당 문서 기록은 JDK 선택 문제였을 뿐 코드 결함이 아니었음이 확인되었다.

설정 파일을 수정하지 않았고, 테스트를 통과시키기 위해 코드를 고치지 않았으며, 외부 서비스 실행이나
Docker container 생성도 하지 않았다.

## 부록 B. 조사했으나 11개 항목에 포함되지 않은 구현

조사 중 확인된, 프롬프트 11개 항목 밖의 실제 구현이다. 프로젝트 전체 규모를 파악하는 데 참고가 된다.

- **AI 화물 분석 도메인** (`com.fast.backend.ai.*`): `cargo/detected` 수신 → 검증 → 2테이블 저장 →
  `/topic/ai/cargo-analysis` 전송 + 조회 API 2종. 통합 테스트 포함.
- **측정 스테이션 도메인** (`com.fast.backend.station.*`): ~~`fast/station/+/measurement` 수신 →
  snake_case DTO + OffsetDateTime 오프셋 보존 저장~~ → **2026-07-31 REST 전환으로 바뀌었다.**
  `POST /api/stations/measurements`로 camelCase 6필드(`sessionId`·`measurementId`·`status`·
  `cargoHeight`·`tippingLevel`·`overhangRatio`)를 받는다. MQTT 구독·라우팅·DTO는 제거됐다.
  세션 뮤텍스(`station_state`)와 TTL 자동 해제가 추가됐다.
- **임베디드 포크 상태·오류 이력** (`com.fast.backend.embedded.*`): `fork-status`/`error` 수신 → 저장 →
  전송 + 조회 API 2종.
- **Isaac Sim 도메인** (`com.fast.backend.isaac.*`): 상태·위치·경로 수신 및 명령 발행.
- **AI 학습 파이프라인** (`ai/`): Python 데이터셋 변환·리뷰·분할, TFNova perception. 테스트 3종.
- **ESP32 펌웨어** (`firmware/esp32/motor_controller`): DC/리니어 모터, 서보, PCA9685, UART 태스크.
- **ROS2 텔레옵** (`ros2_ws/src/forklift_teleop`): UART 브리지, 매핑, 프로토콜. 테스트 2종.
