> ⚠ **이 문서는 더 이상 현행이 아니다(2026-07-30).**
> FR-202 스키마 전환으로 `vehicle_status_history` 테이블과 상태 이력 API가 **제거**됐다.
> 또한 증분 migration 운영을 중단해 이 문서가 가리키던
> `db/migration/2026-07-24-unified-command-and-isaac-status.sql` 은 **존재하지 않는다**.
> 현재 DB 적용 방식은 `docs/backend-db/database-schema.md` 의 'DB 적용 정책'을 따른다
> (개발 DB 초기화 후 `schema.sql` 로 재생성). 아래 내용은 당시 기록으로만 남긴다.

# 차량별 상태 이력 조회 API

> 조사·검증 기준일: 2026-07-24  
> 코드 기준: Spring Boot 3.3.4, Java 21, MyBatis 3.0.3  
> 관련 Jira 이슈 번호: 저장소에서 확인되지 않음(팀 확인 필요)

## 1. 개요

이 API는 등록된 특정 차량의 `vehicle_status_history` 누적 이력을 조회한다. 관제 화면이나 운영
도구에서 과거 상태, 배터리, 위치, 속도 및 Isaac 확장 적재 정보를 확인하는 용도다.

선행 기능은 차량 등록과 상태 이력 저장이다. 상태 이력은 MQTT 또는 테스트용 REST 입력을 받은
`VehicleStatusService.updateCurrentStatus`가 현재 상태 upsert와 같은 트랜잭션에서 저장한다.
이 문서의 API는 저장된 이력을 읽기만 하며 현재 상태 갱신, MQTT 수신, WebSocket push, 삭제 및
통계 집계는 담당하지 않는다.

### 조사 결과

| 구분 | 관련 파일·클래스 | 구현 상태 | 역할 | 부족한 부분 |
|---|---|---|---|---|
| Controller | `VehicleController` | 완료 | 실제 GET 경로와 `limit`을 받고 Service 결과를 공통 응답으로 감싼다 | 기간·상태·page/offset·sort 파라미터 미구현 |
| Service | `VehicleStatusHistoryService` | 완료 | `limit` 검증, 차량 존재 확인, Domain → DTO 변환 | 필터 검증·전체 건수 계산 미구현 |
| Mapper interface | `VehicleStatusHistoryMapper` | 완료 | 이력 insert 및 차량별 최근 이력 조회 선언 | 조건 객체·count query 없음 |
| Mapper XML | `VehicleStatusHistoryMapper.xml` | 완료 | 차량 조건, 안정적인 최신순 정렬, `LIMIT` 적용 | 동적 SQL, 기간·상태 조건, `OFFSET` 없음 |
| Domain | `VehicleStatusHistory` | 완료 | 이력 테이블 16개 필드 표현 | 없음 |
| Request DTO | 없음 | 미구현 | 현재는 path/query 값을 Controller가 직접 받는다 | 복합 조회 조건 DTO 없음 |
| Response DTO | `VehicleStatusHistoryResponse` | 완료 | 기본 상태와 Isaac 확장 필드를 반환한다 | 페이지 메타데이터 없음 |
| 오류 | `ErrorCode`, `GlobalExceptionHandler` | 완료 | 차량 없음과 limit 범위 오류를 공통 JSON으로 변환한다 | query 타입 변환 오류의 공통 ErrorCode 응답은 미검증 |
| DB | `db/schema.sql` | 완료 | 이력 테이블, FK, 조회용 복합 인덱스 정의 | 운영 DB 실제 적용 여부 미검증 |
| ~~migration~~ | **삭제됨**(증분 migration 미운영) | 기존 이력 테이블에 Isaac 확장 컬럼을 수동 추가한다 | 테이블 최초 생성은 최신 `schema.sql` 사용 전제 |
| Controller 단위 테스트 | `VehicleControllerTest` | 완료 | 기본/명시 limit 위임과 응답 래핑 검증 | HTTP 계층은 통합 테스트가 담당 |
| Service 단위 테스트 | `VehicleStatusHistoryServiceTest` | 완료 | 차량 없음, 빈 목록, 매핑, limit 경계 검증 | 미지원 필터 테스트 없음 |
| Mapper 통합 테스트 | `VehicleStatusHistoryMapperTest` | 완료 | insert, 차량 분리, 최신순, limit, null, enum, 생성 ID, 동일 시각 정렬 검증 | 운영 MySQL 실행계획 미검증 |
| HTTP 통합 테스트 | `VehicleControllerIntegrationTest` | 완료 | 성공/빈 목록/404/limit 오류/비숫자 limit를 MockMvc로 검증 | 실제 배포 환경 호출 미검증 |
| Isaac 통합 테스트 | `VehicleStatusIsaacExtrasIntegrationTest` | 완료 | 이력 DTO의 확장 필드와 `+09:00` 변환 검증 | HTTP JSON의 확장 필드 전체 검증은 없음 |
| README·기존 문서 | `README.md`, `communication-protocol.md`, `database-schema.md` | 일부 구현 | 전체 시스템 및 이력 API/DB 흐름을 요약한다 | 이 API만을 위한 상세 문서는 이번 문서가 최초 |

## 2. 구현 상태

| 계층 | 구현 파일 | 상태 | 설명 |
|---|---|---|---|
| Controller | `src/main/java/com/fast/backend/vehicle/controller/VehicleController.java` | 완료 | `GET /api/vehicles/{vehicleId}/status-history` |
| Service | `src/main/java/com/fast/backend/vehicle/service/VehicleStatusHistoryService.java` | 완료 | limit 1~200 검증 후 차량 확인 및 조회 |
| Mapper | `VehicleStatusHistoryMapper.java`, `VehicleStatusHistoryMapper.xml` | 완료 | `vehicle_id` 조건, 최신순, limit |
| Domain | `VehicleStatusHistory.java` | 완료 | DB 이력 행 매핑 |
| Response DTO | `VehicleStatusHistoryResponse.java` | 완료 | 단일 이력 응답 16개 필드 |
| Request DTO | 없음 | 미구현 | 현재 요청 조건이 `vehicleId`, `limit`뿐이므로 별도 DTO가 없다 |
| DB | `src/main/resources/db/schema.sql` | 코드만 있음 | 테이블/FK/인덱스 정의, 테스트 H2 적용 완료 |
| 운영 DB | 외부 MySQL | 미검증 | 저장소만으로 DDL 적용 여부와 실제 데이터 조회를 확인할 수 없다 |

## 3. API 명세

- Method: `GET`
- Path: `/api/vehicles/{vehicleId}/status-history`
- Request Content-Type: 본문 없음
- Response Content-Type: `application/json`
- 인증 여부: Spring Security 또는 별도 인증 필터가 코드와 의존성에서 확인되지 않아 인증 없이 노출
- 응답 형태: `ApiResponse<List<VehicleStatusHistoryResponse>>`
- 정렬: `message_at DESC, id DESC`
- 페이지네이션: 페이지 방식이 아니라 `limit`만 지원하며 offset과 전체 건수는 제공하지 않음

## 4. 요청 파라미터

| 이름 | 위치 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|---|
| `vehicleId` | path | String | 필수 | 없음 | 등록 여부를 확인한 뒤 조회할 차량 ID |
| `limit` | query | int | 선택 | `50` | 반환할 최대 행 수. 허용 범위 `1`~`200` |

`limit`은 Service에서 범위를 먼저 검증한 다음 차량 존재 여부를 확인한다. 따라서 잘못된 limit과
존재하지 않는 차량이 함께 주어지면 limit 오류가 우선한다. 숫자가 아닌 limit은 Spring MVC 바인딩
단계에서 HTTP 400이 되지만, 현재 통합 테스트는 그 응답의 공통 ErrorCode까지 검증하지 않는다.

`from`, `to`, `status`, `page`, `size`, `sort`, `offset`은 현재 API가 지원하지 않는다. 빈
`vehicleId`를 별도로 검사하는 코드도 없으며 빈 path segment는 이 매핑에 도달하지 않는다.

## 5. 응답 구조

최상위 응답은 공통 `ApiResponse`다.

| 필드 | 타입 | nullable | 설명 |
|---|---|---:|---|
| `success` | boolean | 아니요 | 성공 여부 |
| `data` | array | 성공 시 아니요 | 최신순 이력 배열. 이력이 없으면 `[]` |
| `error` | object | 성공 시 예 | 성공 응답에서는 `null` |

`data[]` 항목은 다음과 같다.

| 필드 | 타입 | nullable | 설명 |
|---|---|---:|---|
| `id` | Long | 아니요 | 이력 PK |
| `vehicleId` | String | 아니요 | 차량 ID |
| `status` | String | 아니요 | `VehicleStatus` enum 이름 |
| `battery` | Integer | 예 | 배터리 잔량 |
| `positionX` | Double | 예 | X 좌표 |
| `positionY` | Double | 예 | Y 좌표 |
| `heading` | Double | 예 | `[0, 360)`로 정규화된 방향각 |
| `speed` | Double | 예 | 속도 |
| `forkHeight` | Double | 예 | Isaac 포크 높이 |
| `hasCargo` | Boolean | 예 | Isaac 적재 여부 |
| `cargoId` | String | 예 | Isaac 화물 ID |
| `footprintLength` | Double | 예 | Isaac footprint 길이 |
| `footprintWidth` | Double | 예 | Isaac footprint 너비 |
| `messageAt` | OffsetDateTime | 예 | 메시지 시각. DB 값을 `+09:00`으로 반환 |
| `receivedAt` | OffsetDateTime | 아니요 | 서버 수신 시각. `+09:00` |
| `createdAt` | OffsetDateTime | 아니요 | 이력 생성 시각. `+09:00` |

지원 상태는 `UNKNOWN`, `IDLE`, `ACTIVE`, `MOVING`, `LIFTING`, `LOADING`, `UNLOADING`,
`ESTOP`, `ERROR`, `OFFLINE`이다. 현재 응답은 페이지 객체가 아니므로 `page`, `size`,
`totalElements`, `totalPages` 필드가 없다.

## 6. 정상 응답 예시

```http
GET /api/vehicles/SIM-F01/status-history?limit=2
Accept: application/json
```

```json
{
  "success": true,
  "data": [
    {
      "id": 102,
      "vehicleId": "SIM-F01",
      "status": "LOADING",
      "battery": 87,
      "positionX": 12.4,
      "positionY": 8.1,
      "heading": 90.0,
      "speed": 0.0,
      "forkHeight": 0.8,
      "hasCargo": true,
      "cargoId": "CARGO-001",
      "footprintLength": 2.1,
      "footprintWidth": 1.1,
      "messageAt": "2026-07-24T10:15:20+09:00",
      "receivedAt": "2026-07-24T10:15:20.050+09:00",
      "createdAt": "2026-07-24T10:15:20.050+09:00"
    }
  ],
  "error": null
}
```

이력이 없는 등록 차량은 HTTP 200과 `"data": []`를 반환한다. 위 값은 응답 형식을 설명하는
예시이며 특정 운영 데이터의 실측값은 아니다.

## 7. 오류 응답

| 상황 | HTTP status | ErrorCode | 설명 |
|---|---:|---|---|
| 등록되지 않은 `vehicleId` | 404 | `VEHICLE_NOT_FOUND` | Service의 차량 조회 실패 |
| `limit < 1` 또는 `limit > 200` | 400 | `VEHICLE_STATUS_HISTORY_LIMIT_INVALID` | Service 범위 검증 실패 |
| 숫자가 아닌 `limit` | 400 | 미검증 | Spring MVC 타입 변환 실패. 공통 오류 body/ErrorCode는 테스트로 확정되지 않음 |

차량 없음:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VEHICLE_NOT_FOUND",
    "message": "등록되지 않은 차량입니다: NO-SUCH-VEHICLE"
  }
}
```

잘못된 limit:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VEHICLE_STATUS_HISTORY_LIMIT_INVALID",
    "message": "limit은 1~200 범위여야 합니다: 201"
  }
}
```

기간, 상태, page/size, sort 파라미터가 구현되지 않았으므로 그에 대응하는 전용 오류도 없다.

## 8. 처리 흐름

```text
GET /api/vehicles/{vehicleId}/status-history?limit=
→ VehicleController.statusHistory
→ VehicleStatusHistoryService.findRecentHistory
→ limit 범위 검증
→ VehicleMapper.findByVehicleId (차량 존재 확인)
→ VehicleStatusHistoryMapper.findRecentByVehicleId
→ vehicle_status_history
→ VehicleStatusHistory Domain
→ VehicleStatusHistoryResponse
→ ApiResponse<List<VehicleStatusHistoryResponse>>
```

Service는 `@Transactional(readOnly = true)`다. Mapper가 빈 목록을 반환하면 추가 예외나 `null`
변환 없이 빈 배열 응답으로 이어진다.

## 9. DB 테이블

기준 DDL은 `src/main/resources/db/schema.sql`이다.

| 컬럼 | 타입 | nullable | 설명 |
|---|---|---:|---|
| `id` | BIGINT AUTO_INCREMENT | 아니요 | PK |
| `vehicle_id` | VARCHAR(50) | 아니요 | `vehicle.vehicle_id` FK, Java `vehicleId`로 매핑 |
| `status` | VARCHAR(20) | 아니요 | 차량 상태 enum 이름 |
| `battery` | INT | 예 | 배터리 |
| `position_x` | DOUBLE | 예 | X 좌표 |
| `position_y` | DOUBLE | 예 | Y 좌표 |
| `heading` | DOUBLE | 예 | 방향각 |
| `speed` | DOUBLE | 예 | 속도 |
| `fork_height` | DOUBLE | 예 | Isaac 포크 높이 |
| `has_cargo` | BOOLEAN | 예 | Isaac 적재 여부 |
| `cargo_id` | VARCHAR(50) | 예 | Isaac 화물 ID |
| `footprint_length` | DOUBLE | 예 | Isaac footprint 길이 |
| `footprint_width` | DOUBLE | 예 | Isaac footprint 너비 |
| `message_at` | DATETIME | 예 | 메시지 발생 시각 |
| `received_at` | DATETIME | 아니요 | 서버 수신 시각 |
| `created_at` | DATETIME | 아니요 | 생성 시각 |

`VehicleStatusHistoryMapper.xml`의 `resultMap`이 snake_case 컬럼을 camelCase Java 필드로 명시
매핑한다. 기존 DB용 수동 migration은 다섯 개 Isaac 확장 컬럼만 추가하며 최신 전체 DDL은
`schema.sql`에 있다.

## 10. 조회 SQL

실제 조회 구조:

```sql
SELECT id, vehicle_id, status, battery, position_x, position_y, heading, speed,
       fork_height, has_cargo, cargo_id, footprint_length, footprint_width,
       message_at, received_at, created_at
FROM vehicle_status_history
WHERE vehicle_id = #{vehicleId}
ORDER BY message_at DESC, id DESC
LIMIT #{limit}
```

- 동적 조건: 없음
- 차량 조건: `vehicle_id = #{vehicleId}`
- 기간 조건: 미구현
- 상태 조건: 미구현
- 정렬: 메시지 시각 내림차순, 같은 시각이면 ID 내림차순
- pagination: `LIMIT`만 있고 `OFFSET`은 없음
- count query: 없음
- null 조건 처리: 별도 동적 처리가 없다. `message_at`이 null인 행의 위치는 DB 정렬 규칙에 의존한다.
- 인덱스: `idx_vehicle_status_history_vehicle_message(vehicle_id, message_at DESC)` 존재

현재 인덱스는 차량별 시간순 조회의 선두 조건과 정렬을 지원한다. 동률 정렬의 `id DESC`까지
완전히 포함하지는 않으므로 대량 데이터에서 필요하면 `(vehicle_id, message_at DESC, id DESC)`의
실행계획을 검토할 수 있다. 상태 필터가 추가될 때에는 `(vehicle_id, status, message_at)` 후보를
실제 데이터 분포와 쿼리 계획으로 검증해야 하며, 이번 작업에서는 인덱스를 임의 추가하지 않았다.

## 11. 구현 파일

| 파일 | 역할 |
|---|---|
| `src/main/java/com/fast/backend/vehicle/controller/VehicleController.java` | REST 경로 |
| `src/main/java/com/fast/backend/vehicle/service/VehicleStatusHistoryService.java` | 검증·차량 확인·DTO 변환 |
| `src/main/java/com/fast/backend/vehicle/mapper/VehicleStatusHistoryMapper.java` | Mapper 계약 |
| `src/main/resources/mapper/VehicleStatusHistoryMapper.xml` | insert와 실제 조회 SQL |
| `src/main/java/com/fast/backend/vehicle/domain/VehicleStatusHistory.java` | DB 행 Domain |
| `src/main/java/com/fast/backend/vehicle/dto/VehicleStatusHistoryResponse.java` | 응답 DTO |
| `src/main/java/com/fast/backend/common/exception/ErrorCode.java` | 전용 오류 코드 |
| `src/main/java/com/fast/backend/common/exception/GlobalExceptionHandler.java` | 오류 HTTP 응답 변환 |
| `src/main/resources/db/schema.sql` | 최신 전체 테이블·인덱스 DDL |
| ~~`src/main/resources/db/migration/2026-07-24-unified-command-and-isaac-status.sql`~~ | **삭제됨**(증분 migration 미운영, 2026-07-30) |
| `src/test/java/com/fast/backend/vehicle/mapper/VehicleStatusHistoryMapperTest.java` | 실제 H2 Mapper 검증 |
| `src/test/java/com/fast/backend/vehicle/service/VehicleStatusHistoryServiceTest.java` | Service 단위 검증 |
| `src/test/java/com/fast/backend/vehicle/controller/VehicleControllerTest.java` | Controller 단위 검증 |
| `src/test/java/com/fast/backend/vehicle/controller/VehicleControllerIntegrationTest.java` | MockMvc/H2 HTTP 통합 검증 |
| `src/test/java/com/fast/backend/vehicle/service/VehicleStatusIsaacExtrasIntegrationTest.java` | Isaac 이력 필드 통합 검증 |

## 12. 테스트

### 지원 기능별 근거

| 검증 항목 | 테스트 근거 | 결과 |
|---|---|---|
| 차량별 조회 성공·HTTP 200·JSON 배열 | `VehicleControllerIntegrationTest.statusHistory_afterTwoStatusUpdates_returnsTwoEntriesNewestFirst` | 통과 |
| 최신순 정렬 | 위 통합 테스트, `VehicleStatusHistoryMapperTest.findRecentByVehicleId_ordersByMessageAtDescending` | 통과 |
| 차량 ID 조건·다른 차량 제외 | `findRecentByVehicleId_isSeparatedByVehicle` | 통과 |
| 빈 목록 | Service 및 Controller 통합 테스트 | 통과 |
| 존재하지 않는 차량·404 | Service 및 Controller 통합 테스트 | 통과 |
| limit 기본값·명시값 | `VehicleControllerTest` | 통과 |
| limit 최소/최대와 오류 | Service 및 Controller 통합 테스트 | 통과 |
| 비숫자 limit | `VehicleControllerIntegrationTest.statusHistory_nonNumericLimit_returns400` | HTTP 400 통과, body 미검증 |
| Mapper H2 insert/query | `VehicleStatusHistoryMapperTest` | 통과 |
| 같은 timestamp 안정 정렬 | `findRecentByVehicleId_sameMessageAt_ordersByIdDescending` | 이번 작업에서 추가, 통과 |
| Isaac 확장 필드와 시간 오프셋 | `VehicleStatusIsaacExtrasIntegrationTest` | 통과 |
| 기간·상태·page/size·전체 건수 | 해당 기능 미구현 | 테스트 없음 |

### 실행 결과

관련 테스트:

```text
Tests run: 47, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

전체 테스트(`mvn clean test`):

```text
Tests run: 531, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

환경은 Java 21과 H2 MySQL 호환 모드다. 외부 MQTT broker 및 운영 MySQL에는 연결하지 않았다.

## 13. 호출 예시

### curl

```bash
curl -H "Accept: application/json" \
  "http://localhost:8080/api/vehicles/SIM-F01/status-history?limit=20"
```

### HTTP

```http
GET /api/vehicles/SIM-F01/status-history?limit=20 HTTP/1.1
Host: localhost:8080
Accept: application/json
```

### Swagger/OpenAPI 형식 예시

현재 저장소에 Swagger/OpenAPI 의존성이나 명세 파일은 없다. 문서 도구에 옮길 때 사용할 수 있는
실제 계약의 요약은 다음과 같다.

```yaml
get:
  summary: 차량별 최근 상태 이력 조회
  parameters:
    - name: vehicleId
      in: path
      required: true
      schema: { type: string }
    - name: limit
      in: query
      required: false
      schema: { type: integer, default: 50, minimum: 1, maximum: 200 }
  responses:
    "200":
      description: ApiResponse로 감싼 상태 이력 배열
    "400":
      description: limit 범위 또는 형식 오류
    "404":
      description: 등록되지 않은 차량
```

이는 Swagger가 실행 중이라는 뜻이 아니라 현재 Controller 계약을 OpenAPI 모양으로 표현한 예다.

## 14. 구현 완료 범위

### 완료

- 실제 API 경로와 단순 배열 응답
- 차량 ID 존재 확인
- 차량별 이력 조회 및 다른 차량 이력 제외
- `message_at DESC, id DESC` 최신순 안정 정렬
- `limit` 기본값 50, 허용 범위 1~200
- 빈 목록, 차량 없음, 잘못된 limit 처리
- Domain → Response DTO 변환
- Isaac 확장 필드와 `+09:00` 시각 반환
- H2 기반 Mapper/Service/Controller/통합 테스트
- 동일 timestamp 정렬 회귀 테스트
- API Markdown 문서

### 일부 완료

- 페이지네이션: `limit`만 있고 offset/page 및 전체 건수 없음
- 요청값 검증: limit만 전용 ErrorCode로 검증
- DB 배포: 최신 DDL과 수동 migration은 있으나 운영 적용 여부 미검증

### 미검증

- 운영 MySQL의 테이블·인덱스 적용 상태와 실행계획
- 실제 대량 데이터 성능
- 배포 환경에서의 실제 HTTP 호출
- 프론트 관제 화면 연동

## 15. 남은 작업

1. 제품 요구가 확정되면 `from`/`to`, `status`, page/offset, sort 계약을 먼저 정하고 Request DTO,
   동적 SQL, count query, 오류 코드를 함께 구현한다.
2. 비숫자 query parameter도 공통 `ApiResponse` 오류 형식으로 보장할지 결정하고 테스트한다.
3. 대량 데이터로 운영 MySQL `EXPLAIN`을 수행해 현재 복합 인덱스와 `id` 동률 정렬 비용을 검증한다.
4. 운영 DB에 최신 DDL(`schema.sql`)이 적용됐는지 확인한다(증분 migration은 운영하지 않는다).
5. 프론트 연결, Swagger/OpenAPI 도입 여부 및 관련 Jira 이슈 키는 팀 확인이 필요하다.

## Jira 등록 내용

### 제목

차량별 상태 이력 조회 API 구현

### 설명

특정 차량의 상태 변경 이력을 조회하는 API를 구현한다. 차량 상태 수신 시 저장된
`vehicle_status_history` 데이터를 기반으로 하며, 관제 화면에서 차량별 과거 상태와 배터리·적재
정보를 확인하는 데 사용한다. 현재 구현은 차량 조건, 최신순 정렬, limit 방식 조회를 지원한다.
기간·상태·페이지 조건은 후속 범위다.

### 작업 내용

- 차량별 상태 이력 조회 API
- `vehicleId` 조건 조회와 차량 존재 확인
- `message_at DESC, id DESC` 최신순 정렬
- `limit` 방식 반환 건수 제한
- 상태 이력 Response DTO와 Isaac 확장 필드
- Mapper 조회 SQL
- Mapper/Service/Controller/통합 테스트
- 동일 timestamp 정렬 테스트
- API 문서화

### 완료 조건

- [x] API 경로 구현
- [x] 차량 ID 검증
- [x] 상태 이력 조회
- [x] 최신순 정렬
- [ ] 기간 필터
- [ ] 상태 필터
- [ ] page/offset 페이지네이션
- [x] limit 방식 반환 건수 제한
- [x] 빈 목록 정상 반환
- [x] 차량 없음 오류 처리
- [x] 테스트 통과
- [x] Markdown 문서 작성
- [ ] 운영 MySQL 검증
- [ ] 프론트 연동

## GitLab 등록 내용

### Commit 제목

```text
feat: 차량별 상태 이력 조회 API 구현
```

영문:

```text
feat: implement vehicle status history query API
```

### Commit 본문

```text
- 차량 ID별 최근 상태 이력 조회 API 문서화
- vehicle_status_history Mapper 조회 구조 정리
- 차량 존재 여부와 limit 범위 검증 명세화
- 최신 상태순 및 동일 시각 ID 역순 정렬 테스트 추가
- Mapper/Service/Controller/통합 테스트 결과 기록
```

기간·상태 필터, page/offset, count query는 실제 구현되지 않아 본문에서 제외했다.

### MR 제목

```text
feat: 차량별 상태 이력 조회 API 구현
```

### MR 설명

#### 작업 목적

등록 차량의 누적 상태 이력을 관제·운영 도구에서 조회할 수 있도록 실제 구현과 API 계약을
검증하고 문서화한다.

#### API

- `GET /api/vehicles/{vehicleId}/status-history`
- query: `limit`(선택, 기본 50, 1~200)
- response: `ApiResponse<List<VehicleStatusHistoryResponse>>`
- order: `message_at DESC, id DESC`

#### DB 조회 구조

`vehicle_id`로 필터링하고 복합 인덱스 `(vehicle_id, message_at DESC)`를 이용할 수 있는 SQL에
`LIMIT`을 적용한다. 기간·상태·offset/count 조회는 아직 없다.

#### 구현 파일

Controller, `VehicleStatusHistoryService`, `VehicleStatusHistoryMapper`, Mapper XML,
`VehicleStatusHistory`, `VehicleStatusHistoryResponse`, DB schema 및 관련 테스트.

#### 테스트 결과

- 관련 테스트: 47건 성공
- 전체 테스트: 531건 성공
- Failures/Errors/Skipped: 0/0/0
- H2 MySQL 호환 모드 검증, 운영 MySQL·외부 연동 미검증

#### 남은 작업

기간/상태/page 조건의 제품 계약 확정과 구현, 운영 MySQL 실행계획, 프론트 연결, Jira 이슈 키
연결이 필요하다.

#### 관련 Jira

`차량별 상태 이력 조회 API 구현` — 이슈 키는 팀 확인 필요.
