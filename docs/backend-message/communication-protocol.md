# 통신 규격

현재 MVP의 기준 통신 경계다. 좌표와 길이는 `m`, 속도는 `m/s`, 방향은 `[0, 360)` 범위의 degree를 사용한다.

## 전체 흐름

```text
차량 제어: 관제 REST 요청 → Spring Boot MQTT 명령 → ROS2 처리 → MQTT 상태/결과 수신
화물 측정: taskId 연결 MOVE 성공 → 설비 해제 대기 → 백엔드 MQTT 측정 요청 → AI 세션 생성 및 1회 측정
             → AI REST 결과 등록
  → MySQL 저장
  → REST 조회 및 STOMP WebSocket 갱신
```

## MQTT 공통 정책

- MQTT v3, QoS 1, retained false
- 브로커: `MQTT_BROKER_URL` 환경변수로 주입
- 개발 기본값: `tcp://70.12.130.106:1883` (AI 측정 프로그램 기본 브로커와 동일)
- 차량 식별자는 토픽의 `{vehicleId}`와 payload 식별자가 같아야 한다.
- 측정 결과는 `measurementId`, 차량 명령은 `commandId`로 중복을 방지한다.
- 잘못된 JSON 또는 지원하지 않는 토픽은 저장하지 않고 로그만 남긴다.

## 토픽

| 토픽 | 방향 | 주요 payload |
|---|---|---|
| `forklift/{vehicleId}/status` | ROS2 → 백엔드 | `forkliftId`, `status`, `timestamp` (송신자가 보내는 `battery`는 백엔드가 사용하지 않고 무시한다) |
| `forklift/{vehicleId}/location` | ROS2/Isaac → 백엔드 | ROS2 표준 위치 또는 Isaac 기존 평면 위치 |
| `forklift/{vehicleId}/path` | ROS2/Isaac → 백엔드 | `forkliftId`, `waypoints[]`, `goal{x,y,heading}`, `timestamp` |
| `forklift/{vehicleId}/command` | 백엔드 → ROS2 | 명령 envelope |
| `forklift/{vehicleId}/command-result` | ROS2 → 백엔드 | `commandId`, `vehicleId`, `result`, `message`, `completedAt` |
| `fast/station/measure_request` | 백엔드 → 측정 AI | `cargoId` |

위치 메시지는 다음 두 형식을 수신한다.

- ROS2 표준: `vehicleId`, `position{x,y,frameId}`, `heading`(degree), `speed`, `messageAt`
- Isaac 호환: `forkliftId`, `x`, `y`, `direction`(radian), `speed`, `timestamp`

Isaac 호환 형식은 MQTT 수신 경계에서 `frameId=map`인 표준 위치로 변환한다. 좌표는 이미 m 단위이므로
배율을 다시 적용하지 않으며, `direction`만 degree로 변환해 기존 위치 저장·WebSocket 경로를 공유한다.

### 차량 명령

```json
{
  "commandId": "uuid",
  "vehicleId": "FORKLIFT-01",
  "targetSystem": "ROS2",
  "commandCategory": "MOVE",
  "command": "MOVE",
  "payload": {
    "destination": {"x": 5.0, "y": 6.0, "heading": 180.0, "frameId": "map"}
  },
  "destination": {"x": 5.0, "y": 6.0, "direction": 3.141592653589793},
  "timestamp": "2026-08-01T10:00:00+09:00"
}
```

`payload.destination`은 ROS2 정본 계약이다. 최상위 `destination`은 기존 Isaac Sim 브리지 호환용이며
동일 좌표를 담되 `direction`은 radian을 사용한다. 두 필드는 MQTT 실행 계약에만 사용하며 DB에는
저장하지 않는다. 현재 ROS2 브리지는 명령 수신·검증·결과 회신까지 구현돼 있으나 실제 이동 adapter는
아직 `UnavailableCommandAdapter`이므로, 물리 실행 완료로 간주하면 안 된다.

MOVE 명령 발행 후 최종 `command-result` 대기 시간은 기본 300초다. 이 안에 결과가 없으면 운반 작업을
`FAILED`로 종료한다. MOVE 성공 상태를 커밋한 직후 설비를 확인하고, 점유 중이면 MQTT를 발행하지 않고
1초마다 다시 확인한다. 이 대기까지 `started_at` 기준 300초를 넘으면 실패 처리한다. AI 측정 요청·활성
세션에는 별도 60초 TTL을 적용한다.

### 측정 결과

백엔드는 작업에 연결된 MOVE 성공 결과를 받으면 작업을 `MEASURING`으로 커밋한다. 커밋 직후 활성
세션을 확인하고, 비어 있을 때만 `cargoId`로 MQTT 측정 요청을 발행한다. 점유 중이면 요청을 보내지 않고
1초마다 다시 확인한다. 측정 AI는 세션
생성 API를 호출하고, 응답으로 받은 `sessionId`를 포함해 `POST /api/stations/measurements`로 최종 결과를
등록한다. 세션 생성 시 백엔드는 같은 화물의 측정 대기 작업과 세션을 연결한다.

```json
{
  "sessionId": "uuid",
  "measurementId": "station-20260801-0001",
  "status": "ok",
  "cargoHeight": 0.723,
  "tippingLevel": "safe",
  "overhangRatio": 0.04
}
```

- `cargoHeight`: 팔레트를 제외한 화물 높이(m)
- `status`: `ok`, `dimensions_only`, `no_detection`, `unreliable`
- `tippingLevel`: `safe`, `warning`, `danger`; `ok`일 때 필수
- `overhangRatio`: 무차원 비율; `ok`일 때 필수
- 백엔드는 활성 세션과 `sessionId`가 다르면 늦게 도착한 이전 화물 결과로 보고 거부한다.
- 결과가 저장되면 같은 트랜잭션에서 측정 설비 점유를 자동 해제한다.

## REST API

| Method | 경로 | 역할 |
|---|---|---|
| `POST` | `/api/cargos` | 화물 ID 자동 생성 및 대기 운반 작업 원자적 생성 |
| `POST` | `/api/stations/sessions?cargoId=...` | 측정 세션 생성 |
| `GET` | `/api/stations/sessions/active` | 현재 측정 중인 세션 조회 |
| `GET` | `/api/stations/sessions/{sessionId}/measurements/latest` | 세션 측정 결과 조회 |
| `POST` | `/api/stations/measurements` | 측정 결과 등록 |
| `POST` | `/api/transport-tasks` | 기존 화물에 운반 작업을 수동 생성하는 호환 API |
| `PATCH` | `/api/transport-tasks/{taskId}/assign` | 차량 배정 |
| `PATCH` | `/api/transport-tasks/{taskId}/status` | 작업 상태 전이 |
| `POST` | `/api/vehicles/{vehicleId}/commands` | 차량 명령 생성·MQTT 발행. 측정 위치 MOVE에는 `taskId` 포함 |

신규 입하는 본문 없이 `POST /api/cargos`를 호출한다. 백엔드는 `BIGINT AUTO_INCREMENT` 화물 ID와
`PENDING` 운반 작업을 같은 트랜잭션에서 만들고 `cargoId`, `taskId`, `taskStatus`를 반환한다.
측정 위치 MOVE 요청의 `taskId`는 이 응답에 포함된 문자열 작업 식별자다.

```json
{
  "taskId": "TASK-uuid",
  "command": "MOVE",
  "destination": {"x": 1.0, "y": 2.0, "heading": 90.0, "frameId": "map"}
}
```

## 실시간 화면

백엔드는 STOMP WebSocket으로 차량 위치·상태·경로, 측정 완료, 운반 작업, 명령 결과를 프론트에 전달한다. WebSocket은 화면 갱신용이며 영속 데이터의 기준은 MySQL이다.

## 장애 처리

- 측정 AI가 죽어 결과가 오지 않으면 60초 세션 TTL과 주기 정리 작업으로 자동 회수한다.
- REST 재시도나 MQTT QoS 1 재전송으로 같은 결과가 다시 와도 `measurementId` unique 제약으로 중복 저장하지 않는다.
- 위치 메시지는 `messageAt`이 현재 저장값보다 새로울 때만 반영한다.
- 상태 메시지는 차량당 현재 상태 1행(`vehicle_current_status`)만 갱신한다. 과거 이력 테이블은 두지 않는다.
- DB를 새로 만드는 MVP 단계이므로 별도 마이그레이션 파일은 두지 않는다.

외부 파트별 구현 책임과 예시는 `docs/backend-message/cargo-measurement-workflow-contract.md`를 따른다.
