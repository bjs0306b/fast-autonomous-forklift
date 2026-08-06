# 최적 적재 위치 산출

## 목적

측정 전 운반 작업을 생성하고 차량이 고정 측정 위치에 도착하면 화물을 측정한다. 안전 조건과 높이 조건을 만족하는 최종 결과에 대해 빈 적재 위치를 선택·예약한다. 현재 MVP는 통제된 창고 환경과 사전 등록된 적재 위치를 전제로 한다.

## 입력 계약

### 측정 결과

`station_measurement`의 최신 결과를 사용한다.

| 값 | 의미 |
|---|---|
| `status` | `OK`인 결과만 사용 |
| `cargo_height` | 팔레트를 제외한 화물 높이(m) |
| `tipping_level` | `SAFE`인 결과만 사용 |
| `overhang_ratio` | `0.05` 미만만 사용 |

`DIMENSIONS_ONLY`, `NO_DETECTION`, `UNRELIABLE`, `WARNING`, `DANGER`, 돌출률 5% 이상은 적재 추천 대상이 아니다.

### 고정 설정값

```yaml
storage:
  placement:
    pallet-height-m: 0.12
    height-clearance: 0.25
    max-overhang-ratio-exclusive: 0.05
```

- 모든 길이와 좌표는 현실 스케일 `m`를 사용한다.
- 팔레트 높이는 T-11 규격 0.12m로 고정한다.
- 높이 여유는 현실 스케일 0.25m로 계산한다. 1:10 모형에서는 Isaac Sim 또는 제어 경계에서 0.025m로 축소한다.
- 백엔드는 현실 스케일 값만 계산하며 별도 `scale` 필드를 사용하지 않는다.

## 높이 적합성

```text
requiredHeight = cargoHeight + palletHeight + heightClearance
               = cargoHeight + 0.12 + 0.25
```

다음 조건을 모두 만족하는 위치만 후보가 된다.

```text
storage_slot.status == EMPTY
storage_slot.usable_height >= requiredHeight
```

화물 폭과 길이는 측정·저장하지 않으므로 현재 MVP는 평면 적합성을 판단하지 않는다. 통제된 환경에서 동일한 1.1m × 1.1m 팔레트가 들어가는 적재 위치만 등록한다.

## 후보 우선순위

현재 `PlacementService`는 다음 순서로 후보를 정렬한다.

1. 화물과 팔레트를 넣고 남는 높이가 가장 작은 위치
2. 높이가 같으면 Nav2 이동 거리(`travelDistance`)가 짧은 위치
3. 거리도 같거나 없으면 `slotCode` 오름차순

```text
heightRemaining = usableHeight - (cargoHeight + palletHeight)
```

현재 `TransportTaskService`는 Nav2 거리 공급자를 아직 연결하지 않아 `travelDistance`를 `null`로 전달한다. 따라서 실제 구현은 1번과 3번으로 결정된다. Nav2 경로 길이 연동 시 후보별 거리만 채우면 기존 정렬식을 그대로 사용할 수 있다.

## 작업 생성과 예약

신규 입하는 본문 없이 `POST /api/cargos`를 호출한다. 백엔드는 `BIGINT AUTO_INCREMENT`로
`cargoId`를 생성하고, 같은 트랜잭션에서 해당 화물의 `PENDING` 운반 작업을 생성한다. 이 시점의
`measurementId`, 목적지, 포크 높이는 null이다. 차량 배정 후 `taskId`가 연결된 MOVE 명령이 성공하면
작업을 `MEASURING`으로 전환한다. 백엔드는 활성 측정 세션이 없을 때만 MQTT 요청을 보내며, AI가 기존
API로 세션을 생성하면 같은 `cargoId`의 대기 작업에 해당 세션을 연결한다.

최종 측정 결과가 적합하면 백엔드는 다음을 하나의 트랜잭션으로 처리한다.

1. `measurement_session_id`로 측정 결과와 운반 작업의 일치 여부 확인
2. 높이·전복 위험·돌출률 조건 검증
3. `storage_slot`을 `EMPTY`에서 `RESERVED`로 조건부 갱신
4. 측정 결과와 목적지 좌표·방향·포크 높이를 작업에 저장

동시에 다른 작업이 먼저 예약하면 조건부 갱신이 실패하고 전체 트랜잭션을 롤백한다.

## 상태 변경

```text
PENDING → ASSIGNED → MOVING_TO_PICKUP → MEASURING → PICKING_UP
        → TRANSPORTING → PLACING → COMPLETED
```

- `COMPLETED`: 예약 위치를 `OCCUPIED`로 바꾸고 `stored_cargo_id`를 기록한다.
- 측정 전 `FAILED` 또는 `CANCELLED`: 예약된 위치가 없으므로 작업만 종료한다.
- 목적지 예약 후 `FAILED` 또는 `CANCELLED`: 예약 위치를 다시 `EMPTY`로 돌린다.
- 한 차량에는 동시에 하나의 활성 작업만 배정한다.
- 차량이 `active=true`이고 현재 상태가 `IDLE`일 때만 배정한다.

## 관련 파일

- 스키마: `src/main/resources/db/schema.sql`
- 적재 계산: `src/main/java/com/fast/backend/storage/placement/PlacementService.java`
- 안전 게이트: `src/main/java/com/fast/backend/station/service/StationMeasurementPlacementEligibility.java`
- 작업 생성·예약: `src/main/java/com/fast/backend/transport/service/TransportTaskService.java`

## MVP 이후 보강 사항

- Nav2 경로 거리 공급자 연결
- 측정 위치 MOVE 이후 실제 포크·적재 명령의 자동 연계
- 필요 시 화물·적재 위치 폭과 길이를 추가해 평면 적합성 판단
- `WARNING`을 관제 승인 후 진행하는 정책과 승인 이력
