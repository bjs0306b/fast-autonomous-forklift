# 차량 상태 이력 테이블·저장 로직

> 조사 기준일: 2026-07-24  
> 코드 기준: Spring Boot 3.3.4, Java 21, MyBatis 3.0.3  
> 운영 MySQL migration 상태: 저장소에서 확인할 수 없어 **운영 DB 미적용**으로 분류

## 1. 목적

MQTT로 수신한 차량 상태를 차량별 최신 상태와 누적 이력으로 나누어 저장한다.
`vehicle_current_status`는 차량마다 최신 한 행을 유지하고, `vehicle_status_history`는 시간에 따라
수락된 상태를 계속 추가해 관제 조회, 장애 분석 및 상태 변화 추적에 사용한다.

### 구현 조사 결과

| 구분 | 관련 파일 | 현재 구현 상태 | 부족한 부분 | 실제 검증 여부 |
|---|---|---|---|---|
| 차량 테이블 | `src/main/resources/db/schema.sql` | 완료 | 운영 MySQL 적용 여부 확인 필요 | H2 DDL 테스트 완료 |
| 현재 상태 테이블 | `db/schema.sql`, `VehicleCurrentStatus` | 완료 | 운영 MySQL 적용 여부 확인 필요 | Mapper H2 테스트 완료 |
| 상태 이력 테이블 | `db/schema.sql`, `VehicleStatusHistory` | 완료 | source/frameId 컬럼 없음. 현재 요구 흐름에는 사용하지 않음 | Mapper H2 테스트 완료 |
| current 저장 | `VehicleStatusService`, `VehicleCurrentStatusMapper` | 완료 | 없음 | 단위·H2 통합 테스트 완료 |
| history 저장 | `VehicleStatusService`, `VehicleStatusHistoryMapper` | 완료 | 별도 source/원문 payload/중복 ID 보관 없음 | 단위·H2 통합 테스트 완료 |
| 트랜잭션 | `VehicleStatusService.updateCurrentStatus` | 완료 | 없음 | 실제 rollback 통합 테스트 완료 |
| stale/중복 | `VehicleStatusService.isStale` | 완료 | 동시 수신 경쟁에 대한 잠금/낙관적 버전 없음 | 순차 입력 통합 테스트 완료 |
| 상태 enum | `VehicleStatus` | 완료 | 알 수 없는 값은 거부가 아니라 `UNKNOWN` 저장 | 10종·MOVING 보존 테스트 완료 |
| Isaac 확장 | `IsaacForkliftStatusService`, `VehicleStatusUpdateCommand.IsaacExtras` | 완료 | 운영 migration 적용 여부 확인 필요 | current/history/보존/clear 통합 테스트 완료 |
| 시간 변환 | `CommunicationTime` | 완료 | DB DATETIME 자체에는 offset 정보 없음 | `+09:00` 변환 테스트 완료 |
| MQTT 연결 | `MqttMessageReceiver`, `MqttMessageRouter` | 코드만 구현 | 실제 broker/ROS2/Isaac 송신과 end-to-end 미검증 | Receiver/Router 단위 테스트 완료 |
| WebSocket | `VehicleStatusService`, `VehicleWebSocketBroadcaster` | 완료 | 실제 프론트 수신 미검증 | afterCommit·rollback 미전송 테스트 완료 |
| migration | `2026-07-24-unified-command-and-isaac-status.sql` | 운영 DB 미적용 | 실제 실행 및 확인 쿼리 필요 | 파일 내용만 검토 |
| 기존 DB·통신 문서 | `database-schema.md`, `communication-protocol.md` | 완료 | 이번 조사에서 코드 불일치 없음 | 코드와 대조 완료 |

`VehicleStatusMapper` 또는 `VehicleStatusMapperTest`라는 이름의 타입은 없다. 현재 상태는
`VehicleCurrentStatusMapper`, 이력은 `VehicleStatusHistoryMapper`가 담당한다.

## 2. 전체 데이터 흐름

```text
MQTT forklift/{vehicleId}/status
→ MqttMessageReceiver.receive
→ MqttMessageRouter.route
→ MqttMessageRouter.routeStatus
→ ROS2/Mock: ForkliftStatusService.handleStatus
  또는 Isaac: IsaacForkliftStatusService.handleStatus
→ VehicleStatusService.updateCurrentStatus
→ VehicleCurrentStatusMapper.findByVehicleId
→ VehicleCurrentStatusMapper.upsert
→ VehicleStatusHistoryMapper.insert
→ transaction commit
→ VehicleWebSocketBroadcaster
```

| 순서 | 클래스 | 메서드 | 입력 | 처리 | 출력 |
|---:|---|---|---|---|---|
| 1 | `MqttMessageReceiver` | `receive` | MQTT topic, payload, QoS, retained | 메타데이터 로그 후 Router 위임, 최종 예외 격리 | 없음 |
| 2 | `MqttMessageRouter` | `route` | topic, JSON 문자열 | 지원 topic·JSON object 검증 | 상태 라우팅 호출 |
| 3 | `MqttMessageRouter` | `routeStatus` | `forklift/{id}/status` | 확장 키 또는 battery 부재로 Isaac/일반 payload 판별, topic ID 일치 검증 | 상태 Service 호출 |
| 4A | `ForkliftStatusService` | `handleStatus` | `ForkliftStatusMessage` | 일반 메시지를 확장 필드 없는 공통 Command로 변환 | 공통 저장 Service 호출 |
| 4B | `IsaacForkliftStatusService` | `handleStatus` | `IsaacForkliftStatusMessage` | Isaac 검증·공통 상태 매핑·5개 확장 필드 Command 생성 | 공통 저장 Service 호출 |
| 5 | `VehicleStatusService` | `updateCurrentStatus` | vehicleId, `VehicleStatusUpdateCommand` | 차량/배터리/stale 검증, 확장 필드 병합 | 저장된 최신 상태 응답 |
| 6 | `VehicleCurrentStatusMapper` | `findByVehicleId` | vehicleId | 기존 messageAt·Isaac 값 조회 | 기존 current 또는 empty |
| 7 | `VehicleCurrentStatusMapper` | `upsert` | 병합 완료 current | 차량 PK 기준 insert/update | 차량별 최신 한 행 |
| 8 | `VehicleStatusHistoryMapper` | `insert` | current에서 복제한 history | 누적 insert | 생성 ID |
| 9 | Spring transaction | commit | 두 DB 작업 | 모두 성공한 경우 함께 commit | 영속화 완료 |
| 10 | `VehicleStatusService` | `afterCommit` callback | 공통 상태 응답 | 커밋 뒤 공통 상태 WebSocket 전송 | `/topic/vehicles/status` |

Isaac 전용 WebSocket 이벤트는 트랜잭션 프록시가 적용된 `updateCurrentStatus` 호출이 정상 반환한 뒤
`IsaacForkliftStatusService`에서 추가 전송된다. DB 저장 예외가 나면 공통 Service 호출이 실패하므로
전용 이벤트 전송까지 진행하지 않는다.

## 3. 테이블 구조

### vehicle

| 컬럼 | 타입 | NULL | 기본값 | 키 | 용도 |
|---|---|---:|---|---|---|
| `id` | BIGINT AUTO_INCREMENT | N | 자동 증가 | PK | 내부 식별자 |
| `vehicle_id` | VARCHAR(50) | N | 없음 | UNIQUE | 논리 차량 ID |
| `name` | VARCHAR(100) | N | 없음 | - | 차량명 |
| `source` | VARCHAR(20) | N | 없음 | - | `REAL` 또는 `SIMULATION` |
| `vehicle_type` | VARCHAR(30) | Y | NULL | - | 차량 유형 |
| `active` | BOOLEAN | N | TRUE | - | 활성 여부 |
| `created_at` | DATETIME | N | 없음 | - | 생성 시각 |
| `updated_at` | DATETIME | N | 없음 | - | 수정 시각 |

실제 차량과 시뮬레이션 차량은 같은 테이블을 사용하고 `source`로 구분한다.

### vehicle_current_status

| 컬럼 | 타입 | NULL | 기본값 | 키 | 용도 |
|---|---|---:|---|---|---|
| `vehicle_id` | VARCHAR(50) | N | 없음 | PK, FK | 차량 ID이자 차량별 한 행 보장 |
| `status` | VARCHAR(20) | N | `UNKNOWN` | - | 공통 차량 상태 |
| `battery` | INT | Y | NULL | - | 배터리 |
| `position_x` | DOUBLE | Y | NULL | - | X 좌표 |
| `position_y` | DOUBLE | Y | NULL | - | Y 좌표 |
| `heading` | DOUBLE | Y | NULL | - | 방향각 |
| `speed` | DOUBLE | Y | NULL | - | 속도 |
| `fork_height` | DOUBLE | Y | NULL | - | Isaac 포크 높이 |
| `has_cargo` | BOOLEAN | Y | NULL | - | Isaac 적재 여부 |
| `cargo_id` | VARCHAR(50) | Y | NULL | - | Isaac 화물 ID |
| `footprint_length` | DOUBLE | Y | NULL | - | Isaac footprint 길이 |
| `footprint_width` | DOUBLE | Y | NULL | - | Isaac footprint 너비 |
| `message_at` | DATETIME | Y | NULL | - | 발행 시스템의 메시지 시각 |
| `received_at` | DATETIME | N | 없음 | - | 백엔드 수신 시각 |
| `updated_at` | DATETIME | N | 없음 | - | current 행 갱신 시각 |

### vehicle_status_history

| 컬럼 | 타입 | NULL | 기본값 | 키 | 용도 |
|---|---|---:|---|---|---|
| `id` | BIGINT AUTO_INCREMENT | N | 자동 증가 | PK | 이력 식별자 |
| `vehicle_id` | VARCHAR(50) | N | 없음 | FK, INDEX 선두 | 차량 ID |
| `status` | VARCHAR(20) | N | 없음 | - | 공통 차량 상태 |
| `battery` | INT | Y | NULL | - | 배터리 |
| `position_x` | DOUBLE | Y | NULL | - | X 좌표 |
| `position_y` | DOUBLE | Y | NULL | - | Y 좌표 |
| `heading` | DOUBLE | Y | NULL | - | 방향각 |
| `speed` | DOUBLE | Y | NULL | - | 속도 |
| `fork_height` | DOUBLE | Y | NULL | - | Isaac 포크 높이 |
| `has_cargo` | BOOLEAN | Y | NULL | - | Isaac 적재 여부 |
| `cargo_id` | VARCHAR(50) | Y | NULL | - | Isaac 화물 ID |
| `footprint_length` | DOUBLE | Y | NULL | - | Isaac footprint 길이 |
| `footprint_width` | DOUBLE | Y | NULL | - | Isaac footprint 너비 |
| `message_at` | DATETIME | Y | NULL | 복합 INDEX | 발행 시스템의 메시지 시각 |
| `received_at` | DATETIME | N | 없음 | - | 백엔드 수신 시각 |
| `created_at` | DATETIME | N | 없음 | - | 이력 insert 시각 |

`frameId`와 메시지 source 컬럼은 두 상태 테이블에 없다. `vehicle.source`는 차량 자체의
REAL/SIMULATION 구분이며 개별 이력의 수신 경로를 나타내지 않는다. 현재 상태 MQTT DTO에는 위치
좌표가 없으므로 MQTT 상태 저장에서는 위치 필드가 null이다. 이 컬럼은 공통 Command/테스트 경로의
상태 스냅샷을 수용한다.

## 4. 테이블 관계

```text
vehicle.vehicle_id
  1 ── 0..1 vehicle_current_status.vehicle_id (PK + FK)
  1 ── 0..N vehicle_status_history.vehicle_id (FK)
```

- `vehicle_current_status.vehicle_id`가 PK이므로 차량별 current는 최대 한 행이다.
- 두 상태 테이블 모두 `vehicle(vehicle_id)`를 참조하는 FK가 있다.
- history 조회 인덱스는
  `idx_vehicle_status_history_vehicle_message(vehicle_id, message_at DESC)`다.
- 조회 SQL은 같은 `message_at`에 대해 `id DESC`를 보조 정렬로 사용한다.
- FK에 `ON DELETE`가 없으므로 차량 삭제 시 기본 RESTRICT/NO ACTION에 의존한다.
- 애플리케이션에 차량·이력 삭제 또는 보존 기간 정책은 구현되어 있지 않다.

## 5. 저장 처리 순서

`VehicleStatusService.updateCurrentStatus`의 실제 순서는 다음과 같다.

1. `VehicleMapper.findByVehicleId`로 등록 차량 확인
2. `VehicleStatus.fromRaw`로 상태 정규화
3. battery가 있을 때 `0..100` 검증
4. payload timestamp를 Asia/Seoul `LocalDateTime`으로 변환하고, 없으면 수신 시각 사용
5. `VehicleCurrentStatusMapper.findByVehicleId`로 기존 current 조회
6. 동일하거나 과거 `messageAt`이면 즉시 기존 상태 반환
7. Isaac 확장 필드를 기존 current와 병합
8. `VehicleCurrentStatusMapper.upsert`
9. 병합 완료 current를 `VehicleStatusHistory`로 복제해 `insert`
10. 메서드 정상 종료 시 transaction commit
11. 등록된 transaction synchronization의 `afterCommit`에서 공통 WebSocket 전송

Mapper 호출 순서는 current 조회 → current upsert → history insert다.

## 6. current status 갱신 정책

- 등록되지 않은 차량은 `VEHICLE_NOT_FOUND`로 거부하며 자동 등록하지 않는다.
- `vehicle_id` PK와 MySQL/H2 호환 `INSERT ... ON DUPLICATE KEY UPDATE`로 한 행만 유지한다.
- status는 대소문자와 공백을 정규화한다. 지원하지 않는 문자열은 실패가 아니라 `UNKNOWN`이 된다.
- battery는 null을 허용하며 값이 있으면 0~100이어야 한다.
- 신규 messageAt이 기존보다 **엄격히 늦을 때만** 갱신한다.
- `received_at`과 `updated_at`은 갱신 시점의 서버 시각으로 함께 설정한다.
- 일반 ROS2/REST Command에는 `isaacExtras == null`이므로 기존 5개 Isaac 값을 보존한다.
- Isaac Command에는 항상 non-null `IsaacExtras`가 있으므로 내부 null까지 명시적 값으로 덮어쓴다.

정책 요약:

> 차량별 최신 상태는 `vehicle_current_status`에 upsert한다. 수신 메시지의 `messageAt`이 기존
> 값보다 늦지 않으면 current와 history 모두 반영하지 않는다.

## 7. history 누적 정책

실제 정책은 **모든 수락된 정상 메시지를 저장**하는 방식이다. status 값이 이전 행과 같은지는
비교하지 않는다. 따라서 더 늦은 timestamp로 같은 상태가 반복되면 새 이력을 추가한다.

- 정상적으로 검증되고 기존 current보다 messageAt이 늦은 메시지마다 한 행 insert
- 상태 변경 시에만 저장하는 방식이 아님
- 동일 timestamp는 stale과 같은 정책으로 차단
- 더 오래된 timestamp도 차단
- 별도 message ID나 payload hash 기반 중복 차단은 없음
- timestamp가 다른 동일 payload는 별도 이력으로 저장
- ROS2와 Isaac 모두 같은 `vehicle_status_history` 사용
- 이력에 source 구분 필드 없음
- history는 병합 완료 current 객체에서 만들어 current와 동일한 Isaac 값을 보관

## 8. stale 메시지 처리

`isStale`은 기존 `message_at >= incoming message_at`이면 true다.

| 입력 관계 | current | history | WebSocket |
|---|---|---|---|
| 최초 메시지 | insert | insert | commit 후 전송 |
| 기존보다 늦음 | update | insert | commit 후 전송 |
| 기존과 동일 | 변경 없음 | 추가 없음 | 전송 없음 |
| 기존보다 과거 | 변경 없음 | 추가 없음 | 전송 없음 |

순차 입력 정책은 단위 및 H2 통합 테스트로 검증됐다. 여러 프로세스나 스레드가 같은 차량의 상태를
동시에 처리할 때 select와 upsert 사이의 경쟁을 막는 row lock, version 컬럼 또는 SQL 조건부 upsert는
없으므로 동시성 안전성은 별도 검증이 필요하다.

## 9. Isaac 추가 필드 저장 정책

| 입력 경로 | `isaacExtras` | current 정책 | history 정책 |
|---|---|---|---|
| ROS2/일반 MQTT | null | 기존 5필드 보존 | 보존된 값을 새 이력에도 복사 |
| REST 테스트 상태 | null | 기존 5필드 보존 | 보존된 값을 새 이력에도 복사 |
| Isaac 일반 상태 | non-null | 메시지 값으로 덮어씀 | 덮어쓴 값을 복사 |
| Isaac LWT OFFLINE | non-null, 내부 값 null 가능 | null을 명시적으로 반영 | null 상태를 복사 |

5개 필드는 `forkHeight`, `hasCargo`, `cargoId`, `footprintLength`, `footprintWidth`다. Isaac에서
`hasCargo=false`, `cargoId=null`, `forkHeight=0.0` 같은 값은 유효한 명시적 clear다.

Isaac 상태 enum 7종은 공통 상태에 1:1 대응하고, `MOVING`, `LIFTING`, `LOADING`, `UNLOADING`,
`ESTOP`을 다른 값으로 축약하지 않는다.

## 10. Transaction 및 WebSocket 처리

`VehicleStatusService.updateCurrentStatus`에 `@Transactional`이 적용된다.

- current upsert와 history insert는 같은 transaction이다.
- current upsert가 예외를 던지면 history insert를 호출하지 않는다.
- history insert의 RuntimeException/DataAccessException은 밖으로 전파되어 current upsert도 rollback한다.
- 예외를 Service 안에서 흡수하지 않으므로 Spring 기본 rollback 규칙이 적용된다.
- 공통 WebSocket 이벤트는 `TransactionSynchronization.afterCommit`에서 전송한다.
- rollback이면 `afterCommit`이 호출되지 않아 아직 저장되지 않은 상태가 전송되지 않는다.
- 실제 transaction synchronization이 없는 순수 단위 호출에서는 즉시 broadcast하는 fallback이 있다.
- Broadcaster의 전송 예외는 자체 격리되어 이미 commit된 DB 결과를 되돌리지 않는다.

`VehicleStatusServiceRollbackIntegrationTest`는 강제로 history insert를 실패시킨 뒤 current 행과
history 행이 모두 남지 않음을 H2에서 검증한다. 단위 테스트는 current upsert 실패 시 history와
WebSocket 미호출, history insert 실패 시 예외 전파와 WebSocket 미호출도 확인한다.

## 11. Mapper와 SQL

| Mapper 메서드 | SQL 유형 | 대상 테이블 | 조건 | 정렬 | 반환 |
|---|---|---|---|---|---|
| `VehicleCurrentStatusMapper.findByVehicleId` | SELECT | `vehicle_current_status` | `vehicle_id = #{vehicleId}` | 없음 | Optional current |
| `findAllByVehicleIds` | SELECT | `vehicle_current_status` | `vehicle_id IN (...)` | 없음 | current 목록 |
| `upsert` | INSERT/UPDATE | `vehicle_current_status` | PK 중복 시 `ON DUPLICATE KEY UPDATE` | 없음 | void |
| `countByStatusForActiveVehicles` | SELECT/JOIN/GROUP | `vehicle`, `vehicle_current_status` | `active = true` | 없음 | 상태별 집계 |
| `VehicleStatusHistoryMapper.insert` | INSERT | `vehicle_status_history` | 없음 | 없음 | 영향 행 수, 생성 ID |
| `findRecentByVehicleId` | SELECT | `vehicle_status_history` | `vehicle_id`, `LIMIT` | `message_at DESC, id DESC` | history 목록 |

두 XML 모두 명시적 `resultMap`을 사용해 `vehicle_id` ↔ `vehicleId`와 snake_case ↔ camelCase를
매핑한다. DB 컬럼은 `forklift_id`가 아니라 `vehicle_id`다. current/history insert와 select에
Isaac 확장 5필드가 모두 포함된다.

기간 조건, 상태 조건, offset/page 및 count query는 구현되어 있지 않다. 현재 조회 API는 limit
1~200만 지원한다. H2 테스트는 MySQL mode를 사용하며 upsert와 DDL의 기본 호환성을 검증하지만,
실제 MySQL의 실행계획·잠금·동시성까지 보장하지 않는다.

## 12. 테스트 결과

### 필수 항목별 근거

| 항목 | 테스트 | 결과 |
|---|---|---|
| 신규 current insert | `VehicleCurrentStatusMapperTest.upsert_firstCall_insertsNewRow` | 통과 |
| 기존 current update | `upsert_secondCallSameVehicleId_updatesRowInPlace` | 통과 |
| 정상 history insert/current 동시 저장 | `VehicleStatusServiceIntegrationTest` | 통과 |
| current 실패 시 history 미호출 | `VehicleStatusServiceTest` | 통과 |
| history 실패 시 current rollback | `VehicleStatusServiceRollbackIntegrationTest` | 통과 |
| stale/동일 timestamp current·history 차단 | `VehicleStatusServiceIntegrationTest` | 통과 |
| MOVING 보존 | `VehicleStatusServiceTest`, `VehicleStatusIsaacExtrasIntegrationTest` | 통과 |
| battery 범위·미등록 차량 | `VehicleStatusServiceTest` | 통과 |
| Isaac 5필드 current/history | `VehicleStatusIsaacExtrasIntegrationTest` | 통과 |
| ROS2 수신 시 Isaac 값 보존 | `VehicleStatusIsaacExtrasIntegrationTest` | 통과 |
| Isaac 명시적 clear | `VehicleStatusIsaacExtrasIntegrationTest` | 통과 |
| timestamp `+09:00` | `VehicleStatusIsaacExtrasIntegrationTest` | 통과 |
| 차량별 history 최신순·빈 목록 | Mapper/Service/Controller 테스트 | 통과 |
| commit 이후 WebSocket | `VehicleStatusServiceTest.updateCurrentStatus_activeTransaction_broadcastsOnlyAfterCommit` | 통과 |
| rollback 시 WebSocket 미전송 | Service 실패 단위 테스트 및 rollback 통합 구조 | 통과 |
| MQTT ROS2/Isaac 분기 | `MqttMessageRouterTest`, 각 상태 Service 테스트 | 통과 |

관련 테스트 실행:

```text
Tests run: 109, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

전체 테스트 실행:

```text
Tests run: 531, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Java 21과 H2 MySQL 호환 모드에서 검증했다. 실제 MQTT broker와 운영 MySQL은 사용하지 않았다.

## 13. 운영 DB migration 방법

신규 DB는 `src/main/resources/db/schema.sql`의 최신 전체 정의를 수동 적용한다. 애플리케이션은
공용 MySQL에 이 파일을 자동 실행하지 않는다.

기존 DB에서 current/history 테이블은 있으나 Isaac 확장 컬럼이 없다면
`src/main/resources/db/migration/2026-07-24-unified-command-and-isaac-status.sql`의 다음 부분을
사전 점검 후 수동 적용한다.

1. `vehicle_current_status`에 Isaac 5컬럼 추가
2. `vehicle_status_history`에 Isaac 5컬럼 추가
3. 적용 확인 쿼리 실행

해당 migration은 같은 날짜의 통합 명령 컬럼 변경도 포함하므로 상태 테이블 부분만 적용할지 전체를
적용할지는 팀 배포 절차에 따라 결정해야 한다. MySQL 8의 `ADD COLUMN IF NOT EXISTS` 제약 때문에
재실행 안전성이 없으므로 `information_schema.COLUMNS` 확인이 선행되어야 한다.

이번 작업에서는 운영 DB에 접속하거나 SQL을 실행하지 않았다. 따라서 migration 상태는
**운영 DB 미적용/확인 필요**다. 최신 schema, 기존 migration, Mapper가 이미 일치해 새로운 migration
파일은 만들지 않았다.

## 14. 남은 확인 사항

1. 운영 MySQL에 current/history 테이블과 Isaac 확장 컬럼·복합 인덱스가 실제 적용됐는지 확인
2. 동일 차량 상태의 동시 수신 경쟁에서 stale 정책이 유지되는지 부하/동시성 테스트
3. 대량 history에서 `(vehicle_id, message_at DESC)` 조회 실행계획과 보존·파티셔닝 정책
4. 실제 ROS2/Isaac → MQTT broker → DB → WebSocket end-to-end 검증
5. 차량 삭제 시 FK RESTRICT를 유지할지 이력 익명화/보존 정책을 둘지 팀 결정
6. 이력별 source, 원문 message ID 또는 payload 보관이 필요한지 제품 요구 확인
7. Jira 이슈 키와 운영 migration 실행 주체·일정은 팀 확인 필요

## Jira 등록 내용

### 목적

MQTT로 수신한 차량 상태를 차량별 최신 상태와 상태 이력으로 구분하여 저장하고, 관제 및 장애 분석을
위한 상태 변화 기록을 제공한다.

### 구현 내용

- `vehicle_current_status` 차량별 current upsert
- `vehicle_status_history` 상태 이력 누적 insert
- current와 history 동일 transaction 처리
- 동일·과거 timestamp stale 차단
- 차량 상태 10종 및 MOVING 독립 저장
- Isaac 확장 상태 5필드 current/history 저장
- Asia/Seoul DB 시각과 통신 `+09:00` 변환
- DB commit 후 WebSocket push
- Mapper/Service/MQTT/H2 통합 테스트

### 완료 조건

- [x] 차량별 최신 상태 1건 유지
- [x] 정상 상태 수신 시 이력 저장
- [x] current와 history 동일 transaction 처리
- [x] stale 메시지 정책 적용
- [x] MOVING 상태 보존
- [x] Isaac 확장 필드 current 저장
- [x] Isaac 확장 필드 history 저장
- [x] timestamp 정책 반영
- [x] rollback 시 불완전 데이터 방지
- [x] 관련 테스트 통과
- [x] DB 문서 갱신
- [x] migration SQL 작성 여부 확인: 기존 migration으로 충분
- [ ] 운영 MySQL migration 적용 확인
- [ ] 실제 MQTT/WebSocket end-to-end 검증

### 비고

코드와 H2 테스트는 완료됐으나 운영 MySQL migration 적용은 확인되지 않았다.

## GitLab 등록 내용

### Commit 제목

```text
feat: 차량 상태 이력 테이블 및 저장 로직 구현
```

영문:

```text
feat: implement vehicle status history persistence
```

### Commit 본문

실제 이번 작업은 기존 구현 조사와 문서화가 중심이므로 다음 본문이 현재 변경 내용에 맞다.

```text
- 차량별 current upsert와 상태 이력 누적 저장 구조 문서화
- current/history 동일 transaction과 rollback 정책 정리
- stale 및 동일 timestamp 차단 정책 정리
- 차량 상태 10종과 Isaac 확장 필드 저장 정책 정리
- timestamp 변환과 commit 이후 WebSocket 흐름 정리
- 관련 테스트 및 전체 회귀 테스트 결과 기록
```

### MR 설명

#### 작업 목적

차량 상태 current/history 저장 구현을 코드·DDL·Mapper·테스트 기준으로 검증하고 운영 적용 경계를
명확히 문서화한다.

#### 주요 변경 사항

- 전용 구현 문서 추가
- current/history/Isaac/stale/timestamp 정책 정리
- Jira/GitLab 복사용 내용 및 검증 결과 추가

#### DB 변경 사항

이번 작업에서 신규 DDL은 없다. 최신 `schema.sql`과 기존
`2026-07-24-unified-command-and-isaac-status.sql`이 코드와 일치한다.

#### 저장 처리 흐름

MQTT → Router → ROS2/Isaac Service → `VehicleStatusService` → current upsert → history insert →
commit → WebSocket.

#### Transaction 정책

current와 history는 하나의 transaction이며 history 실패 시 current도 rollback한다. WebSocket은
commit 이후 전송한다.

#### 테스트 결과

- 관련 테스트 109건 성공
- 전체 테스트 531건 성공
- Failures/Errors/Skipped 0/0/0

#### 운영 DB migration

직접 실행하지 않았다. 기존 DB의 Isaac 컬럼과 인덱스 적용 여부를 확인해야 한다.

#### 미검증 또는 확인 필요 항목

운영 MySQL, 실제 MQTT/WebSocket end-to-end, 동시 수신 경쟁, 대량 이력 성능과 보존 정책.
