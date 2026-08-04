# 적재 순서 계획

## 목적

대기 중인 운반 작업 가운데 다음에 처리할 화물을 결정한다. MVP는 화물을 받는 위치와 측정 위치가
한 곳으로 고정되어 있고 한 번에 한 대의 지게차만 진입할 수 있는 통제된 환경을 전제로 한다.

이 문서에서 상차는 고정 위치의 화물을 지게차가 들어 올리는 과정이고, 하차는 추천된 적재 위치에
화물을 내려놓는 과정이다. 창고에서 화물을 꺼내 출고 지점으로 옮기는 역방향 출고 작업은 현재 범위에
포함하지 않는다.

## 현재 데이터와 제약

순서 계획에 사용하는 현재 데이터는 다음과 같다.

| 데이터 | 용도 |
|---|---|
| `transport_task.status` | 대기·진행·종료 작업 구분 |
| `transport_task.created_at` | 대기 순서 판단 |
| `transport_task.id` | 생성 시각이 같을 때의 최종 정렬 기준 |
| `transport_task.vehicle_id` | 작업이 배정된 차량 확인 |
| `vehicle.active` | 사용 가능한 차량 확인 |
| `vehicle_current_status.status` | `IDLE` 차량만 선택 |
| `station_state` | 측정 위치 점유 여부 확인 |
| `storage_slot.status` | 적재 위치 예약·점유 상태 확인 |

현재 `transport_task`에는 입고·출고 구분, 업무 우선순위, 마감 시각이 없다. 따라서 MVP에서는 임의의
가중치 점수식을 만들지 않고 생성 순서에 따른 선입선출을 사용한다.

또한 다음 동시성 제약을 유지한다.

- 한 화물에는 종료되지 않은 운반 작업을 하나만 생성한다.
- 한 차량에는 활성 운반 작업을 하나만 배정한다.
- `MOVING_TO_PICKUP` 또는 `MEASURING` 작업은 전체 시스템에서 하나만 허용한다.
- 적재 위치는 `EMPTY → RESERVED` 조건부 갱신으로 한 작업만 예약한다.

## MVP 순서 결정 규칙

다음 조건을 만족하는 작업만 순서 결정 대상이다.

```text
transport_task.status == PENDING
```

처리 순서는 다음 정렬식으로 결정한다.

```text
ORDER BY created_at ASC, id ASC
```

즉 가장 오래 기다린 작업을 먼저 처리한다. `id`는 동일한 시각에 생성된 작업도 항상 같은 순서로
결정하기 위한 보조 기준이다.

자동 매칭은 1초마다 실행하며 다음 조건을 모두 만족할 때 한 건만 처리한다.

1. 가장 오래된 `PENDING` 작업이 있다.
2. `active=true`이고 현재 상태가 `IDLE`인 차량이 있다.
3. 해당 차량에 다른 활성 작업이 없다.

조건을 만족하지 않으면 다음 주기에 다시 확인한다. 한 주기에는 하나만 배정하므로 여러 차량이 비어
있어도 작업은 1초 간격으로 차례대로 `ASSIGNED`가 된다.

자동 매칭은 차량 배정까지만 수행하며 MOVE 명령은 발행하지 않는다. 측정 위치 MOVE를 시작할 때는
기존 `VehicleCommandService`가 `MOVING_TO_PICKUP`, `MEASURING` 작업이 없는지 확인하므로 측정 차선에는
계속 최대 한 대만 진입한다.

## 처리 흐름

```text
PENDING 작업 조회
  → 가장 오래된 작업 선택
  → IDLE 차량 배정
  → 기존 MOVE 명령 대기
  → 고정 측정 위치 MOVE
  → 이전 측정 세션 해제 대기
  → 화물 측정
  → 적재 가능 여부 확인
  → 적재 위치 선택·예약
  → 화물 상차
  → 적재 위치로 이동
  → 화물 하차
  → 작업 완료
  → 다음 PENDING 작업 처리
```

작업 상태는 기존 상태 전이를 그대로 사용한다.

```text
PENDING → ASSIGNED → MOVING_TO_PICKUP → MEASURING → PICKING_UP
        → TRANSPORTING → PLACING → COMPLETED
```

- 측정 결과가 부적합하거나 빈 적재 위치가 없으면 `FAILED`로 종료한다.
- 이동 또는 측정 TTL이 만료돼도 `FAILED`로 종료한다.
- 목적지 예약 후 실패·취소되면 해당 위치를 다시 `EMPTY`로 반환한다.
- 실패한 작업 때문에 다음 작업을 막지 않으며, 종료 처리 후 다음 `PENDING` 작업을 선택한다.
- 발행된 측정 요청과 실패한 측정은 자동 재시도하지 않는다. 다만 이전 세션 점유 중에는 요청을 아직
  발행하지 않고 1초마다 설비 상태만 다시 확인한다.

## 적재 위치 선택과의 관계

적재 순서 계획은 어떤 화물을 먼저 처리할지만 결정한다. 실제 목적지는 측정이 끝난 뒤
`optimal-placement.md`의 규칙으로 선택한다.

따라서 작업 순서 산정 시에는 화물 높이나 목적지 이동 거리를 사용하지 않는다. 측정 전에는 화물 높이와
목적지 적재 위치가 아직 정해지지 않기 때문이다.

적재 위치 추천은 다음 순서로 수행한다.

1. 안전 조건과 높이 조건을 만족하는 `EMPTY` 위치 검색
2. 남는 높이가 가장 작은 위치 선택
3. 조건이 같으면 Nav2 이동 거리 사용
4. 거리도 같거나 없으면 `slotCode` 오름차순

## 권장 구현 위치

MVP 자동 스케줄러는 다음 구조로 구현한다.

| 구성 요소 | 역할 |
|---|---|
| `TransportSchedulingScheduler` | 1초 간격으로 자동 매칭 실행 |
| `TransportSchedulingService` | 다음 대기 작업과 사용 가능한 차량을 한 건씩 매칭 |
| `TransportTaskMapper` | 가장 오래된 `PENDING` 작업 조회 |
| `VehicleCurrentStatusMapper` | 사용 가능한 `IDLE` 차량 조회 |
| `TransportTaskService` | 기존 조건부 갱신으로 `PENDING → ASSIGNED` 처리 |

조회 직후 다른 요청이 먼저 작업이나 차량을 배정할 수 있으므로 기존 `TransportTaskService.assign()`의
조건부 `PENDING → ASSIGNED` 갱신 결과가 1건인지 최종 확인한다. 경합으로 배정에 실패하면 다음 1초
주기에 최신 상태를 다시 조회한다.

현재 스키마만으로 MVP를 구현할 수 있으므로 컬럼 추가는 필요하지 않다.

## 완료 조건

- 여러 `PENDING` 작업이 있으면 `created_at`, `id` 오름차순으로 처리된다.
- 측정 위치로 두 대가 동시에 이동하지 않는다.
- 한 차량에 두 개의 활성 작업이 배정되지 않는다.
- 실패·취소된 작업이 다음 작업의 실행을 막지 않는다.
- 적재 위치 예약 충돌이 발생하면 한 작업만 예약에 성공한다.
- 같은 입력 상태에서는 항상 같은 다음 작업이 선택된다.

## MVP 이후 확장

출고와 긴급 작업을 함께 최적화하려면 현재 스키마만으로는 부족하다. 실제 요구가 확정된 뒤 다음 정보를
추가한다.

- 작업 유형: `INBOUND`, `OUTBOUND`
- 업무 우선순위와 출고 마감 시각
- 출고 작업의 원본 적재 위치와 최종 하차 위치
- Nav2 예상 이동 거리 또는 경로 비용
- 통로 혼잡도와 다른 차량의 예약 경로

이 데이터가 추가되면 마감 임박도, 대기 시간, 이동 거리, 재배치 횟수를 조합한 우선순위 점수로
확장할 수 있다. 대기 시간이 길수록 점수를 높이는 aging 규칙을 함께 적용해 낮은 우선순위 작업이
무기한 밀리지 않도록 한다.

## 관련 파일

- 적재 위치 추천: `docs/backend-api/optimal-placement.md`
- 적재 공간 관리: `docs/backend-api/storage-space-recognition.md`
- 작업 상태: `src/main/java/com/fast/backend/transport/domain/TaskStatus.java`
- 작업 서비스: `src/main/java/com/fast/backend/transport/service/TransportTaskService.java`
- 자동 매칭: `src/main/java/com/fast/backend/transport/service/TransportSchedulingService.java`
- 주기 실행: `src/main/java/com/fast/backend/transport/service/TransportSchedulingScheduler.java`
- 작업 Mapper: `src/main/resources/mapper/TransportTaskMapper.xml`
- 실행 스키마: `src/main/resources/db/schema.sql`
