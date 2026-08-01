# 통신 규격

현재 MVP의 기준 통신 경계다. 좌표와 길이는 `m`, 속도는 `m/s`, 방향은 `[0, 360)` 범위의 degree를 사용한다.

## 전체 흐름

```text
차량 제어: 관제 REST 요청 → Spring Boot MQTT 명령 → ROS2 처리 → MQTT 상태/결과 수신
화물 측정: taskId 연결 MOVE 성공 → 백엔드 MQTT 측정 요청 → AI 최대 3회 측정
             → AI REST 결과 등록
  → MySQL 저장
  → REST 조회 및 STOMP WebSocket 갱신
```

## MQTT 공통 정책

- MQTT v3, QoS 1, retained false
- 브로커: `MQTT_BROKER_URL` 환경변수로 주입
- 차량 식별자는 토픽의 `{vehicleId}`와 payload 식별자가 같아야 한다.
- 측정 결과는 `measurementId`, 차량 명령은 `commandId`로 중복을 방지한다.
- 잘못된 JSON 또는 지원하지 않는 토픽은 저장하지 않고 로그만 남긴다.

## 토픽

| 토픽 | 방향 | 주요 payload |
|---|---|---|
| `forklift/{vehicleId}/status` | ROS2 → 백엔드 | `forkliftId`, `status`, `battery`, `timestamp` |
| `forklift/{vehicleId}/location` | ROS2 → 백엔드 | `vehicleId`, `position{x,y,frameId}`, `heading`, `speed`, `messageAt` |
| `forklift/{vehicleId}/path` | ROS2/Isaac → 백엔드 | `forkliftId`, `waypoints[]`, `goal{x,y,heading}`, `timestamp` |
| `forklift/{vehicleId}/command` | 백엔드 → ROS2 | 명령 envelope |
| `forklift/{vehicleId}/command-result` | ROS2 → 백엔드 | `commandId`, `vehicleId`, `result`, `message`, `completedAt` |
| `fast/station/measure_request` | 백엔드 → 측정 AI | `sessionId`, `cargoId`, `taskId`, `vehicleId`, `maxAttempts`, `requestedAt` |

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
  "timestamp": "2026-08-01T10:00:00+09:00"
}
```

`payload`와 `commandCategory`는 MQTT 실행 계약에만 사용하며 DB에는 저장하지 않는다. 현재 ROS2 브리지는 명령 수신·검증·결과 회신까지 구현돼 있으나 실제 이동 adapter는 아직 `UnavailableCommandAdapter`이므로, 물리 실행 완료로 간주하면 안 된다.

### 측정 결과

백엔드는 작업에 연결된 MOVE 성공 결과를 받으면 세션을 열고 MQTT 측정 요청을 발행한다. 측정 AI는 요청의 `sessionId`를 그대로 포함해 `POST /api/stations/measurements`로 최종 결과를 등록한다.

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
| `POST` | `/api/cargos` | 측정 전 화물 등록 |
| `POST` | `/api/stations/sessions?cargoId=...` | 측정 세션 생성 |
| `DELETE` | `/api/stations/sessions/{sessionId}/force` | 실패 후 남은 세션 강제 해제 |
| `GET` | `/api/stations/sessions/active` | 현재 측정 중인 세션 조회 |
| `GET` | `/api/stations/sessions/{sessionId}/measurements/latest` | 세션 측정 결과 조회 |
| `POST` | `/api/stations/measurements` | 측정 결과 등록 |
| `POST` | `/api/transport-tasks` | 측정 전 운반 작업 생성 |
| `PATCH` | `/api/transport-tasks/{taskId}/assign` | 차량 배정 |
| `PATCH` | `/api/transport-tasks/{taskId}/status` | 작업 상태 전이 |
| `POST` | `/api/vehicles/{vehicleId}/commands` | 차량 명령 생성·MQTT 발행. 측정 위치 MOVE에는 `taskId` 포함 |

측정 위치 MOVE 요청 예시는 다음과 같다. `taskId`는 `POST /api/transport-tasks` 응답의 문자열 작업 식별자다.

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

- 측정 AI가 죽어 결과가 오지 않으면 세션 TTL 이후 다음 세션 생성 시 회수한다.
- REST 재시도나 MQTT QoS 1 재전송으로 같은 결과가 다시 와도 `measurementId` unique 제약으로 중복 저장하지 않는다.
- 위치 메시지는 `messageAt`이 현재 저장값보다 새로울 때만 반영한다.
- DB를 새로 만드는 MVP 단계이므로 별도 마이그레이션 파일은 두지 않는다.

외부 파트별 구현 책임과 예시는 `docs/backend-message/cargo-measurement-workflow-contract.md`를 따른다.
