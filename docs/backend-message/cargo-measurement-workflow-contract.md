# 화물 측정 연동 계약

이 문서는 백엔드 밖의 ROS2·AI·Isaac Sim 담당자가 구현해야 할 최소 계약을 정리한다. 백엔드는 DB와 아래 MQTT/REST 경계까지만 책임진다.

## 전체 순서

```text
화물 등록과 운반 작업 원자적 생성 및 차량 배정
→ taskId가 포함된 측정 위치 MOVE 명령 발행
→ ROS2가 MOVE 결과 SUCCESS 반환
→ 백엔드가 작업을 MEASURING으로 커밋하고 측정 설비가 비기를 기다림
→ 활성 세션이 없을 때
→ 백엔드가 cargoId로 fast/station/measure_request 발행
→ AI 측정 프로그램이 기존 REST API로 측정 세션 생성
→ 백엔드가 세션과 대기 중인 운반 작업 연결
→ AI 측정 프로그램이 1회 촬영·추론
→ AI 측정 프로그램이 최종 결과를 REST로 등록
→ 백엔드가 적재 위치를 선택·예약하고 작업을 PICKING_UP으로 전환
→ AI의 기존 세션 종료 DELETE는 이미 자동 해제된 세션으로 안전하게 처리
```

작업 상태는 `ASSIGNED → MOVING_TO_PICKUP → MEASURING → PICKING_UP` 순서로 진행한다.
`MEASURING`에서 `measurement_requested_at`이 null이면 측정 위치 도착 후 설비 해제를 기다리는 상태다.
측정 결과가 안전 조건을 만족하지 않거나 빈 적재 위치가 없으면 `FAILED`로 종료한다.

## ROS2 담당 계약

ROS2는 기존 `forklift/{vehicleId}/command`의 MOVE 명령을 실행하고 결과를 `forklift/{vehicleId}/command-result`로 반환한다.

- 받은 `commandId`, `vehicleId`, `targetSystem`, `commandCategory`, `command`를 결과에 그대로 사용한다.
- Nav2 목표 도착이 성공했을 때만 `result=SUCCESS`를 보낸다.
- 취소·실패·거부는 각각 `CANCELLED`, `FAILED`, `REJECTED`로 보낸다.
- 좌표계는 `map`, 좌표는 m, `heading`은 degree를 사용한다.
- ROS2가 측정 요청 토픽을 직접 발행하거나 `cargoId`를 관리하지 않는다.

현재 `ros2_ws/src/fast_mqtt_bridge`는 명령 검증과 결과 envelope까지 있으나 실제 Nav2 실행 adapter가 임시 구현이다. ROS2 담당자는 MOVE를 `NavigateToPose`에 연결하고 목표 종료 결과를 아래 형식으로 반환해야 한다.

```json
{
  "commandId": "uuid",
  "vehicleId": "FORKLIFT-01",
  "targetSystem": "ROS2",
  "commandCategory": "MOVE",
  "command": "MOVE",
  "result": "SUCCESS",
  "message": "goal reached",
  "completedAt": "2026-08-02T10:00:00+09:00"
}
```

## AI 측정 프로그램 담당 계약

### 측정 요청 수신

- 토픽: `fast/station/measure_request`
- 방향: 백엔드 → AI 측정 프로그램
- QoS: 1
- retained: false

```json
{
  "cargoId": 1
}
```

- 요청 한 건당 한 번 측정하고 최종 결과 한 건만 전송한다.
- `cargoId`는 백엔드가 `POST /api/cargos`에서 자동 생성한 64비트 정수다.
- AI는 기존 `POST /api/stations/sessions?cargoId=...` API로 세션을 생성한다.
- 운영 환경에서는 백엔드 MQTT 요청을 받은 경우에만 세션 생성 API를 호출한다. 수동 테스트 호출은
  다음 측정 요청보다 먼저 설비를 점유할 수 있으므로 운영 흐름과 분리한다.
- 백엔드는 생성된 세션을 같은 `cargoId`의 측정 대기 작업과 연결한다.
- 이미지 파일은 저장하지 않는다. 실시간 영상 표시가 필요하면 측정 결과 계약과 별도 스트림으로 구현한다.

정상 측정 결과가 저장되고 작업이 `PICKING_UP`으로 전환되면, 측정 트랜잭션 커밋 후
`CARGO_INTAKE_NEXT_DELAY_MS`(기본 5000ms)가 지난 시점에 다음 화물과 `PENDING` 운반 작업을
자동 생성한다. 부적합 측정, 적재 위치 추천 실패, 세션 TTL 만료에서는 자동 생성하지 않는다.
자동 입하는 `CARGO_INTAKE_AUTO_CREATE_ENABLED`(기본 `true`)로 끌 수 있다.

현재 AI의 `trigger.py`, `serve.py`, `rest_client.py` 세션 생성 흐름을 그대로 사용한다.

### TTL 실패 처리

- 백엔드가 측정 위치 MOVE 명령을 발행한 뒤 300초 안에 MOVE 결과를 받지 못하면 작업을
  `FAILED`로 종료한다. 기준 시각은 `transport_task.started_at`이다.
- MOVE 성공 시 설비 상태를 즉시 확인하고, 이전 세션이 남아 있으면 MQTT를 발행하지 않고 1초마다
  다시 확인한다.
  이 대기까지 `started_at` 기준 300초를 넘으면 작업을 `FAILED`로 종료한다.
- 요청 발행 조건에도 같은 300초 경계를 적용하므로 정리 스케줄러보다 발행 스케줄러가 먼저 실행돼도
  만료 작업은 MQTT로 내보내지 않는다.
- 백엔드가 측정 요청을 발행하면 `transport_task.measurement_requested_at`을 기록한다.
- 설정된 TTL 안에 AI가 세션을 열지 않으면 작업을 `FAILED`로 종료한다.
- AI가 세션을 열었지만 TTL 안에 최종 결과를 저장하지 못해도 세션을 자동 해제하고 작업을 `FAILED`로 종료한다.
- 실패한 작업은 재측정하지 않으며 다음 대기 작업이 측정 차선을 사용할 수 있다.
- 설비 점유 중에는 아직 측정 요청을 발행하지 않은 것이므로 재측정으로 보지 않는다.
- 측정 요청 발행 주기는 `STATION_MEASUREMENT_REQUEST_DISPATCH_INTERVAL_MS`(기본 1초)다.
- 정리 주기는 `STATION_SESSION_CLEANUP_INTERVAL_MS`(기본 5초)다.
- MOVE 결과와 설비 해제 대기 상한은 `STATION_MOVE_TTL_SECONDS`(기본 300초), 측정 요청·활성 세션
  TTL은 `STATION_SESSION_TTL_SECONDS`(기본 60초)로 설정한다.

### 최종 결과 등록

AI 측정 프로그램은 세션 생성 API에서 받은 `sessionId`를 그대로 사용해 `POST /api/stations/measurements`로 결과를 등록한다.

```json
{
  "sessionId": "uuid",
  "measurementId": "station-20260802-0001",
  "status": "ok",
  "cargoHeight": 0.723,
  "tippingLevel": "safe",
  "overhangRatio": 0.04
}
```

- `cargoHeight`: 팔레트를 제외한 화물 높이(m)
- `status`: `ok`, `dimensions_only`, `no_detection`, `unreliable`
- `ok`일 때 `tippingLevel`과 `overhangRatio`가 필수다.
- `sessionId`가 현재 활성 세션과 다르면 백엔드는 이전 화물의 늦은 결과로 판단해 거부한다.
- AI의 기존 `DELETE /api/stations/sessions/{sessionId}` 호출은 유지한다. 결과 저장으로 이미 자동 해제된
  경우 백엔드는 `STATION_SESSION_NOT_ACTIVE`를 반환하고 AI는 이를 정상 종료로 처리한다.

## Isaac Sim 담당 계약

- 측정 시작 여부와 작업 상태를 판단하지 않는다.
- 기존 WebRTC 화면 송출 구조를 유지한다.
- 기존 `twin_bridge.py` 위치 형식(`forkliftId`, `x`, `y`, `direction`, `timestamp`)을 유지한다.
- 백엔드가 Isaac 위치를 ROS2 표준 위치 형식으로 변환해 DB와 관제 WebSocket에 반영한다.
- 실물 미니어처 AMCL `map` 좌표가 기준이며, 현실 스케일 모델의 배율 변환은 Isaac Sim 내부에서 처리한다.

현재 WebRTC 및 시각화 코드는 유지한다. Isaac Sim은 이동 완료 `command-result`를 발행하지 않고,
ROS2가 Nav2 실행 결과를 백엔드에 반환한다. Isaac Sim이 `fast/station/measure_request`를 직접 발행하던
구조가 있다면 제거하고 ROS2의 MOVE 결과만 사용한다.

## Embedded 담당 계약

이 측정 흐름 때문에 변경할 사항은 없다. 기존 주행·포크 UART 및 리미트 스위치 제어를 유지한다.

## 백엔드가 보장하는 항목

- 한 번에 하나의 작업만 측정 위치로 이동하거나 측정할 수 있다.
- `vehicle_command.task_id`로 MOVE 결과와 운반 작업을 연결한다.
- MOVE 성공 후 활성 세션이 남아 있으면 요청을 유실하지 않고 설비 해제까지 대기한다.
- AI가 세션을 생성하면 같은 `cargoId`의 측정 대기 작업에 연결한다.
- `transport_task.measurement_session_id`로 측정 결과의 대상 작업을 확정한다.
- 측정 요청 실패, MOVE 실패, 부적합 측정 결과는 작업을 `FAILED`로 종료한다.
- MOVE 결과, 측정 요청 또는 활성 측정 세션이 각각의 TTL을 넘으면 작업을 `FAILED`로 종료하고
  차선을 자동 해제한다.
- AI 측정 결과는 MQTT가 아니라 REST 한 경로로만 받는다.

## 배포 유의 사항

변경 전 버전이 만든 `MOVING_TO_PICKUP + measurement_requested_at 존재` 작업은 새 상태 규칙과 호환되지
않는다. MVP DB를 초기화하지 않고 배포한다면 진행 중인 측정 작업이 없는 것을 확인한 뒤 교체한다.
