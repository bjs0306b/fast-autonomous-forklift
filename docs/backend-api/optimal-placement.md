# 최적 적재 위치 산출

## 1. 목적과 문서 상태

이 문서는 FR-202에서 화물에 적합한 적재 위치를 선택하고 예약하는 목표 동작을 정의한다.
FR-201에서 관리하는 적재 위치 정보와 측정 스테이션의 화물 분석 결과를 입력으로 사용한다.

본문은 다음 상태를 구분한다.

- **목표 규격**: FR-202 MVP에서 구현해야 하는 동작
- **현재 구현**: 저장소에서 확인되는 실제 동작
- **후속 작업**: 목표 규격을 구현하기 위해 다른 파일에서 변경해야 하는 내용
- **TBD**: 실제 맵 좌표나 최종 통신 필드명처럼 아직 확정되지 않은 설정값

> 현재 백엔드의 적재 계산, AI 안전 판정, 스테이션 메시지와 이 문서의 목표 규격 사이에는 차이가 있다.
> 상세 차이는 `12. 현재 구현과 목표 규격의 차이`와 `15. 다른 파일의 후속 수정 사항`에서 관리한다.

## 2. 범위와 담당 영역

### 2.1 FR-202 담당 범위

백엔드는 다음 작업을 담당한다.

1. 스테이션 측정 결과를 현재 화물과 연결한다.
2. 측정 단위를 현실 스케일 `m`로 정규화한다.
3. 안전 조건과 적재 가능 조건을 만족하는 빈 적재 위치를 찾는다.
4. 확정된 우선순위에 따라 최적 위치를 한 곳 선택한다.
5. 운반 작업 생성과 함께 해당 위치를 원자적으로 예약한다.
6. 적재 성공·실패·취소에 따라 적재 위치 상태를 변경한다.

### 2.2 다른 영역의 담당 범위

- **측정 스테이션·AI**: 화물 높이, 돌출률, 전복 위험 분석 결과 생성
- **ROS2/Nav2**: 경로 계획, 경유점 생성, 위치 추정, 주행 제어
- **Isaac Sim**: 현실 스케일 명령을 현실 모델과 1:10 모형 모델에 적용
- **MQTT**: 확정된 작업·명령·상태의 전달
- **프론트엔드**: 추천 결과와 차단 사유 표시, 향후 `WARNING` 작업의 사용자 승인

FR-202는 최종 적재 위치와 그 위치의 접근 자세를 결정하지만, 해당 위치까지의 경로나 조향각을 직접 계산하지 않는다.

## 3. 확정 상수와 단위

### 3.1 기본 단위

스테이션의 원시 수신 메시지는 센서 출력 규격에 따라 `cm`를 사용한다. Target input adapter가 이를
정확히 한 번 `m`로 변환한 이후에는 placement DTO, 백엔드 계산, SQL, MQTT 및 Isaac Sim 연동이 모두
현실 스케일 `m`를 사용한다. 방향은 `degree`를 사용한다.

| 값 | 단위 |
|---|---|
| 화물 높이·팔레트 규격·적재 위치 높이 | m |
| 스테이션·픽업·목적지 좌표 | m |
| 포크 높이 | m |
| 진행 방향 | degree |
| 돌출률·지지면 이탈률·종횡비 | 무차원 비율 |

### 3.2 팔레트 규격

현실 스케일 T-11 팔레트 규격을 고정 상수로 사용한다.

| 구분 | 값 |
|---|---:|
| 너비 | 1.1m |
| 길이 | 1.1m |
| 높이 | 0.12m |

팔레트 규격은 모든 작업에서 같으므로 팔레트별 측정값으로 저장하지 않는다.

### 3.3 적재 여유 높이

| 스케일 | 여유 높이 | 용도 |
|---|---:|---|
| 현실 | 0.25m | 백엔드의 적재 가능 판정 |
| 모형 1:10 | 0.025m | Isaac Sim 및 모형 표현 |

백엔드는 현실 스케일로 한 번만 계산한다. 모형용 `0.025m`를 백엔드 계산식에 다시 더하지 않는다.

### 3.4 Isaac Sim 스케일 변환

백엔드는 현실 스케일 값을 전달하고 Isaac Sim 연동 계층에서 모형 모델의 길이와 좌표에만 `0.1`을 적용한다.

```text
miniatureLengthM = realLengthM × 0.1
```

`heading`, `overhang`, `support_offset`, `margin`, `aspect_ratio`에는 `0.1`을 적용하지 않는다.
현실 모델과 모형 모델을 동시에 움직일 수 있으므로 백엔드에 별도의 스케일 선택 필드는 요구하지 않는다.

> **현재 구현 차이:** 스테이션 DTO와 검증기는 현재 미니어처 환산 필드를 별도로 요구한다.
> 목표 규격에서는 현실 스케일 값을 정규 입력으로 사용하고 모형 변환을 Isaac Sim 경계에서 담당한다.

## 4. 스테이션 점유와 측정 결과 연결

### 4.1 단일 스테이션 설정

MVP에서는 측정 스테이션이 하나이며 위치 `x`, `y`와 지게차 정차 방향 `heading`은 고정값이다.
이 값은 애플리케이션 설정에서 관리하고 DB에 별도 스테이션 테이블이나 `station_id`를 두지 않는다.
운반 작업을 생성할 때 해당 값을 `transport_task.source_*`에 실행 시점 스냅샷으로 저장한다.

### 4.2 단일 화물 점유 원칙

한 스테이션에는 동시에 하나의 화물만 진입할 수 있다. `station_state`의 단일 행을 잠근 뒤
`active_session_id`가 비어 있을 때만 새 세션을 연결한다.

개념적인 상태 흐름은 다음과 같다.

```text
active_session_id = NULL
    → active_session_id = sessionId
    → active_session_id = NULL
```

### 4.3 측정 세션 식별자

측정 결과를 현재 화물과 안전하게 연결하기 위해 다음 식별자를 사용한다.

```text
sessionId
cargoId
measurementId
```

이전 화물의 결과가 늦게 도착하여 현재 화물에 연결되지 않도록 `sessionId`를 검증한다.

### 4.4 측정 결과 유효성

다음 조건을 모두 만족한 결과만 FR-202 입력으로 사용한다.

1. `sessionId`가 현재 `active_session_id`와 일치한다.
2. 해당 세션의 `cargoId`가 현재 화물과 일치한다.
3. 같은 활성 세션에서 `sequence_no`가 가장 큰 최신 결과다.
4. 측정 상태가 `OK`다.
5. 화물 높이, 전복 위험등급과 돌출률이 존재한다.
6. 애플리케이션에 고정 스테이션 좌표가 설정되어 있다.

불완전하거나 오래된 결과에는 임의 기본값을 넣지 않는다. 해당 측정은 재측정 또는 격리 대상으로 처리한다.

> **현재 구현 차이:** 목표 스키마 초안에는 세션 연결과 `tipping_level`, `overhang_ratio`가 정의되어 있지만
> 현재 실행 스키마와 애플리케이션 매핑에는 아직 반영되지 않았다.

## 5. 적재 위치 데이터 의미

### 5.1 개별 적재 위치

`storage_slot`은 팔레트 하나를 적재할 수 있는 고정 위치다. MVP에서는 랙과 랙 층을 별도 테이블로
관리하지 않고, 시뮬레이션에서 정해진 각 위치를 `slot_code`로 직접 식별한다.

| 속성 | 의미 |
|---|---|
| `slot_code` | 적재 위치의 고유 식별자 |
| `usable_height` | 화물과 팔레트가 사용할 수 있는 수직 가용 높이 |
| `fork_height` | 해당 위치에 적재하기 위한 포크 목표 높이 |
| `destination_x`, `destination_y` | 지게차가 적재 작업을 시작할 최종 정차·접근 좌표 |
| `destination_heading` | 적재 위치를 바라보는 정차 방향 |
| `status` | `EMPTY`, `RESERVED`, `OCCUPIED`, `BLOCKED` 중 하나 |
| `reserved_task_id` | 이 위치를 예약한 운반 작업. 예약되지 않았으면 `NULL` |
| `stored_cargo_id` | 이 위치에 적재된 화물. 비어 있으면 `NULL` |

`usable_height`는 바닥의 절대 높이가 아니라 해당 위치에 화물이 들어갈 수 있는 가용 높이다.
랙·층·열과 같은 표현이 필요하면 `slot_code`의 명명 규칙이나 화면 표시 정보로 다루고,
MVP 적재 계산을 위한 별도 `rack`, `rack_level` 테이블은 만들지 않는다.

### 5.2 좌표의 역할

`storage_slot.destination_*`은 지게차가 이동할 수 있는 모든 좌표를 의미하지 않는다. 픽업·대기·충전·경유·장애물 회피 좌표는 주행 시스템이 별도로 사용할 수 있다.

운반 작업을 생성할 때 선택한 위치를 `destination_slot_code`로 참조하고, 목적지 좌표·방향과 포크 높이를 `transport_task`에
실행 시점 스냅샷으로 복사한다. 이후 적재 위치 설정이 바뀌어도 이미 생성된 작업의 목표값은 변경하지 않는다.

## 6. 정상 입력 계약과 단위 정규화

### 6.1 스테이션 입력

스테이션에서 input adapter로 들어오는 치수 입력은 화물만의 높이이며 단위는 `cm`다.

```text
stationCargoHeightCm
overhang
tipping.level
```

input adapter는 원시 `height_cm`를 `m`로 변환하여 placement 전용 canonical DTO를 만든다.
이 DTO부터 DB 저장과 MQTT 명령까지 길이 단위는 `m`다. `total_height_cm`, `pallet_height_cm`,
`width_cm`, `miniature_height_mm`, `miniature_total_height_mm`, `miniature_width_mm`는 목표 FR-202 계산 입력으로 사용하지 않는다.

### 6.2 현실 스케일 정규화

```text
cargoHeightM = stationCargoHeightCm / 100
palletHeightM = 0.12
totalLoadHeightM = cargoHeightM + palletHeightM
requiredHeightM = totalLoadHeightM + 0.25
```

팔레트 높이는 백엔드에서 정확히 한 번만 더한다.

### 6.3 정상 운영 입력 완전성

MVP의 정상 흐름에서는 다음 값이 모두 제공된다고 가정한다.

- 화물·측정 세션·측정 결과 식별자
- 화물 높이
- 돌출률과 전복 위험 결과
- 애플리케이션에 설정된 스테이션 픽업 좌표
- 적재 위치의 가용 높이와 상태
- 목적지 좌표·방향과 포크 높이

SQL에서 일부 값이 nullable이더라도 정상 알고리즘은 누락된 값을 임의로 보완하지 않는다.

> **현재 구현 차이:** 현재 AI handoff 문서는 `total_height_cm` 사용을 권장하지만 목표 FR-202는 cargo-only `height_cm`을 정규화한 뒤 팔레트 상수를 더한다.
> 현재 백엔드 DTO는 목표 안전·세션 필드를 모두 수신하지 못한다.

## 7. 적재 가능성과 안전 판정

### 7.1 돌출률

돌출률은 팔레트 좌우 경계를 기준으로 한 무차원 비율이다.

```text
overhang = max(leftOverflow, rightOverflow, 0) / palletWidth
```

```text
통과: overhang < 0.05
차단: overhang >= 0.05
```

현실 팔레트 너비 `1.1m` 기준 5%는 한쪽 최대 `0.055m`에 해당한다. 랙은 허용 돌출량을 고려한 좌우 여유가 있다고 가정한다.

### 7.2 전복 위험

목표 규격에서 전복 위험과 돌출률은 독립적으로 판단한다.

- `SAFE`: 자동 적재 가능
- `WARNING`: MVP에서는 자동 진행하지 않음
- `DANGER`: 적재 차단

`WARNING`의 관제 사용자 승인 기능은 후속 범위다. MVP에서는 승인을 받지 않았다고 보고 자동 배치를 차단한다.

목표 `tipping.level`은 지지면 이탈률과 종횡비 기반 결과로 취급하며, 돌출률만으로 위험등급을 다시 올리지 않는다.

> **현재 구현 차이:** 현재 `ai/src/station/tipping.py`는 돌출률이 2%를 넘으면 전복 위험등급을 한 단계 올린다.
> 목표 규격에서는 5% 돌출 판정과 전복 위험 판정을 분리하므로 AI 로직 보완이 필요하다.

### 7.3 수평 규격 전제

모든 화물은 고정 `1.1m × 1.1m` T-11 팔레트와 함께 이동한다. MVP에 등록되는 모든 적재 위치는
이 팔레트를 수용할 수 있도록 시뮬레이션에서 미리 구성되어 있다고 가정한다. 따라서
`storage_slot`에는 너비와 길이를 저장하지 않고 런타임 후보 판정에서도 다시 비교하지 않는다.

화물 깊이는 측정하지 않으며 90도 회전으로 적재 가능성을 다시 계산하지 않는다.

### 7.4 수직 적재 가능성

```text
requiredHeightM = cargoHeightM + 0.12 + 0.25

적재 가능:
requiredHeightM <= storageSlot.usableHeight
```

두 값이 정확히 같으면 적재할 수 있다.

### 7.5 후보 필터 순서

다음 조건을 모두 만족하는 위치만 후보로 사용한다.

1. 측정 세션과 결과가 유효하다.
2. 적재 위치 상태가 `EMPTY`다.
3. `tipping.level`이 `SAFE`다.
4. `overhang < 0.05`다.
5. `requiredHeightM <= storageSlot.usableHeight`다.
6. 목적지 좌표·방향과 포크 높이가 존재한다.

어느 하나라도 차단 조건이면 다른 값이 정상이더라도 후보에서 제외한다.

## 8. 최적 적재 위치 선정

### 8.1 파생값

남는 높이는 안전 여유까지 적용하고 남은 공간이다.

```text
remainingHeightM = storageSlot.usableHeight - requiredHeightM
```

이동거리는 활성 측정 세션에 연결된 스테이션 좌표와 적재 위치 접근 좌표 사이의 직선거리다.

```text
distanceM = sqrt(
    (stationX - destinationX)^2
    + (stationY - destinationY)^2
  )
```

Nav2 경로가 확정되기 전까지 직선거리를 MVP 이동 비용으로 사용한다.

### 8.2 정렬 순서

후보는 다음 순서로 오름차순 정렬한다.

1. `remainingHeightM`이 가장 작은 위치
2. `distanceM`이 가장 짧은 위치
3. `slotCode`가 사전순으로 앞선 위치

마지막 식별자 비교까지 사용해 항상 같은 입력에서 같은 결과가 나오도록 한다.
현재 구현의 `wastedVolume`, 전체 잔여 치수 합과 90도 회전 여부는 목표 정렬 기준에 포함하지 않는다.

## 9. 예약과 상태 변경

### 9.1 후보 상태

추천 대상은 `EMPTY`뿐이다.

- `EMPTY`: 추천·예약 가능
- `RESERVED`: 다른 작업이 예약했으므로 제외
- `OCCUPIED`: 화물이 적재되어 있으므로 제외
- `BLOCKED`: 사용 불가하므로 제외

### 9.2 원자적 예약

추천 위치의 예약은 작업 생성 트랜잭션 안에서 조건부 갱신으로 수행한다.

```sql
UPDATE storage_slot
SET status = 'RESERVED',
    reserved_task_id = :taskId
WHERE slot_code = :slotCode
  AND status = 'EMPTY';
```

동시에 같은 위치를 선택해도 갱신 건수가 1인 요청만 성공한다. 다른 요청은 작업 생성을 롤백하며 다음 후보를 자동으로 다시 추천하지 않는다.

### 9.3 상태 전이

```text
EMPTY → RESERVED → OCCUPIED
           └────→ EMPTY
```

- 적재 성공: `RESERVED → OCCUPIED`
- 작업 실패·취소: `RESERVED → EMPTY`

적재 성공 시 `reserved_task_id`를 비우고 `stored_cargo_id`를 설정한다. 실패·취소 시에는
`reserved_task_id`만 비우며 `stored_cargo_id`는 계속 `NULL`로 유지한다.

### 9.4 후보 없음

적재 가능한 위치가 없으면 `NO_AVAILABLE_STORAGE_SLOT`을 반환한다.
임의의 좌표를 생성하거나 기존 화물을 자동 재배치하지 않는다.

## 10. 추천 결과 규격

최종 API·DTO·MQTT 필드명은 연동 담당자 협의 후 확정한다. 다음 예시는 필요한 의미를 보여주기 위한 비구속 예시다.

```json
{
  "sessionId": "SESSION-001",
  "cargoId": "CARGO-001",
  "measurementId": "MEASUREMENT-001",
  "storageLocation": {
    "slotCode": "R01-L02-C01",
    "usableHeight": 1.1
  },
  "destination": {
    "x": 4.2,
    "y": 1.8,
    "heading": 90.0,
    "forkHeight": 1.25
  },
  "calculation": {
    "cargoHeight": 0.63,
    "palletHeight": 0.12,
    "clearance": 0.25,
    "requiredHeight": 1.0,
    "remainingHeight": 0.1,
    "distance": 5.4,
    "overhang": 0.03,
    "tippingLevel": "SAFE"
  },
  "reason": "남는 높이, 이동 거리와 위치 코드 순으로 선택"
}
```

길이와 좌표는 현실 스케일 `m`, 방향은 `degree`, 돌출률은 무차원 비율이다.

## 11. Nullable·누락 가능 속성 예외 등록부

MVP 정상 흐름에서는 필요한 데이터가 모두 들어온다고 가정한다. 다음 값은 현재 SQL 또는 연동 구조에서 누락될 수 있으므로 후속 예외 처리 대상으로 별도 관리한다.

| 구분 | 누락 가능 값 | 정상 처리 전 필요한 조건 | 후속 처리 |
|---|---|---|---|
| 스테이션 설정 | 위치 `x`, `y`, `heading` | 애플리케이션 시작 전에 설정 | 추천 실행 중단 |
| 측정 세션 | `sessionId`, `cargoId`, `measurementId` | 활성 세션·화물과 일치 | 결과 격리·재측정 |
| 측정 상태 | `status`, `tipping.level` | `OK`, level 존재 | 결과 격리·재측정 |
| 화물 분석 | 높이, 돌출률 | 유한한 정상값 | 결과 격리·재측정 |
| 픽업 위치 | 고정 스테이션 `x`, `y`, `heading` | 작업 생성 전 설정 | 추천 실행 중단 |
| 적재 위치 | `destination_x`, `destination_y`, `destination_heading` | 후보 등록·활성화 전 입력 | 후보 제외 또는 비활성 처리 |
| 적재 위치 | `usable_height`, `fork_height` | 후보 등록·활성화 전 입력 | 후보 제외 또는 비활성 처리 |
| 운반 작업 | 출발·도착 좌표, 포크 높이, 차량 ID | 실제 명령 발행 전에 확정 | 명령 발행 차단 |

구체적인 오류 코드와 복구 API는 TBD다. 누락값을 `0`, 임의 좌표 또는 임의 정렬값으로 대체하지 않는다.

## 12. 현재 구현과 목표 규격의 차이

| 항목 | 목표 규격 | 현재 구현 | 근거 | 후속 조치 |
|---|---|---|---|---|
| 여유 높이 | 현실 `0.25m` | `0.05m` | `src/main/resources/application.yml:17-21` | 설정값 변경 |
| 팔레트 높이 | cargo-only 높이에 `0.12m`를 한 번 추가 | `cargo.height + clearance` | `src/main/java/com/fast/backend/storage/placement/PlacementService.java:82-85` | 수직 판정식 변경 |
| 수평 판정 | 모든 위치가 고정 팔레트 `1.1 × 1.1m`를 수용하도록 사전 구성 | cargo width/length 및 90도 회전 | `src/main/java/com/fast/backend/storage/placement/PlacementService.java:86-115` | 런타임 수평 판정 제거 |
| 정렬 | 높이 → 거리 → 슬롯 코드 | 낭비 부피 → 잔여 치수 합 → 층 → 거리 → 코드 | `src/main/java/com/fast/backend/storage/placement/PlacementService.java:117-146` | comparator 변경 |
| 거리 누락 | 정상 입력 완전성 요구 | 좌표 누락 시 `distance = null` 허용 | `src/main/java/com/fast/backend/storage/placement/PlacementService.java:139-146` | 입력 검증 추가 |
| 후보 상태 | `EMPTY`만 조회·예약 | `EMPTY`만 조회하고 조건부 예약 | `src/main/resources/mapper/StorageSlotMapper.xml:56-106` | 유지 |
| 예약 충돌 | 롤백, 자동 재시도 없음 | 동일 | `src/main/java/com/fast/backend/transport/service/TransportTaskService.java:112-119` | 유지 |
| 돌출·전복 | 독립 gate, 돌출 `< 0.05` | 돌출 `> 0.02`이면 tipping level 상승 | `ai/src/station/tipping.py:31-32,63-68,80-88` | AI 판정 분리 |
| 스테이션 DTO | 세션·화물·tipping·overhang 수신 | 관련 필드 없음 | `src/main/java/com/fast/backend/station/dto/StationMeasurementMessage.java:20-75` | DTO 및 adapter 확장 |
| 스테이션 검증 | 원시 cargo-only cm를 adapter에서 m로 변환 | miniature scale/height/width 필수 검증 | `src/main/java/com/fast/backend/station/service/StationMeasurementService.java:112-146,212-240` | validator 변경 |
| 측정 저장 | 세션 연결과 tipping·overhang 보존 | 관련 컬럼·매핑 없음 | `src/main/resources/db/schema.sql:146-180`, `src/main/resources/mapper/StationMeasurementMapper.xml:5-83` | 목표 스키마·domain·mapper 반영 |
| 적재 위치 구조 | `storage_slot` 단일 테이블과 `slot_code` PK | 랙·층·슬롯 계층 및 숫자 슬롯 ID | `src/main/resources/db/schema.sql:311-360` | 목표 스키마와 매핑으로 평탄화 |
| 스테이션 좌표 | 애플리케이션의 단일 고정 pose를 pickup origin으로 사용 | `station_measurement`의 `station_id` 외 고정 pose 설정 없음 | 현재 설정 및 스키마 검색 | 고정 pose 설정 추가 |
| 높이 handoff | cargo-only `height_cm` 사용 | 문서는 `total_height_cm` 사용 권장 | `docs/ai/station-measurement-handoff.md:21-30,65-79` | 연동 문서 수정 |

## 13. 검증 시나리오

시나리오 ID는 승인된 FR-202 검증 명세와 연결된다.

| ID | 시나리오 | 기대 결과 |
|---|---|---|
| SC-01 | cargo `0.63m` + pallet `0.12m` + clearance `0.25m`, 위치 높이 `1.00m` | 경계값과 같으므로 통과 |
| SC-02 | 위 조건에서 위치 높이 `0.999m` | 후보 제외 |
| SC-03~05 | 돌출률 `0.049999`, `0.05`, `0.08` | 첫 값만 통과 가능, 나머지는 후보 제외 |
| SC-06~10 | `SAFE`, `WARNING`, `DANGER` 및 다른 gate와 충돌 | `SAFE`만 통과하고 차단 조건 우선 |
| SC-11~12 | 고정 팔레트 호환 위치와 비호환 위치 등록 시도 | 호환 위치만 운영 후보로 등록 |
| SC-12a | 돌출률 `0.03`, 전복 위험 `SAFE` | 두 독립 gate 통과 |
| SC-13 | `EMPTY`, `RESERVED`, `OCCUPIED`, `BLOCKED` 혼합 | `EMPTY`만 후보 유지 |
| SC-14~17 | 높이·거리·슬롯 코드 순서별 동률 | 확정 comparator 순서대로 하나를 선택 |
| SC-18 | 후보가 없음 | `NO_AVAILABLE_STORAGE_SLOT` |
| SC-19~21 | 동시 예약, 완료, 실패·취소 | 한 작업만 예약; 완료 시 `OCCUPIED`, 실패·취소 시 `EMPTY` |
| SC-22~23 | stale session과 세 식별자가 일치하는 session | stale 결과 격리, 일치 결과만 사용 |
| SC-24 | 운영 필수 좌표가 null | 임의 fallback 없이 예외 등록부로 라우팅 |
| SC-24a~c | status 비정상, 위험등급 누락, 필수 측정값 누락 | fallback 없이 재측정·격리 |
| SC-24d~f | 고정 pose 정상, 잘못된 설정, pose 누락 | 정상 설정만 사용하고 잘못된 pose는 차단 |
| SC-25~28 | 현실·모형 길이, clearance, heading 변환 | 길이만 `0.1`, heading 유지, clearance 중복 적용 금지 |
| SC-29 | 돌출률·지지면 이탈률·margin·종횡비 | 무차원 값이므로 변환하지 않음 |
| SC-30 | 현재 AI에서 돌출률 `0.03`, 기본 전복 위험 `SAFE` | 현재 mismatch를 표시하고 목표에서는 독립 gate로 통과 |
| SC-31 | 원시 `height_cm=63` | adapter 결과 `0.63m`, 필요 높이 `1.00m` |
| SC-32 | cargo·total·miniature 높이가 함께 전달됨 | cargo-only `height_cm / 100`만 canonical 입력으로 사용 |

## 14. MVP 제외 범위

- 화물 깊이 측정과 깊이 기반 회전 판정
- 화물 무게와 랙 하중 최적화
- `WARNING` 작업의 관제 승인 UI 및 승인 API
- 적재 완료 화물의 자동 재배치
- 여러 화물의 입고 순서를 함께 계산하는 전역 최적화
- Nav2 실제 경로 길이·예상 시간 기반 비용 계산
- 동적 랙 탐색과 자동 맵 생성
- 임의 임시 적재 좌표 생성

## 15. 다른 파일의 후속 수정 사항

목표 규격을 실제 애플리케이션에 구현할 때는 다음 파일 또는 영역의 수정이 필요하다.

| 파일 또는 영역 | 필요한 수정 |
|---|---|
| `src/main/resources/application.yml` | `storage.placement.height-clearance`를 목표 현실값 `0.25`로 변경 |
| `src/main/java/com/fast/backend/storage/placement/PlacementProperties.java` | 고정 팔레트 상수와 돌출 임계값의 설정·상수 관리 방법 정리 |
| `src/main/java/com/fast/backend/storage/placement/PlacementService.java` | cargo-only 높이에 팔레트와 여유 높이를 더하고, 안전 gate와 높이·거리·슬롯 코드 정렬 적용 |
| `src/main/java/com/fast/backend/storage/placement/PlacementRecommendation.java` | 필요 높이, 남는 높이, 거리, 안전 판정과 선정 이유 등 목표 응답 정보 추가 검토 |
| `src/main/java/com/fast/backend/transport/service/TransportTaskService.java` | 단일 스테이션의 고정 pose를 pickup origin으로 사용하고 필수 입력 완전성 검증 |
| `src/main/java/com/fast/backend/station/dto/StationMeasurementMessage.java` | `sessionId`, `cargoId`, `tipping`, `overhang` 수신 구조 추가 |
| `src/main/java/com/fast/backend/station/service/StationMeasurementService.java` | 현실 cargo-only 입력 검증, 세션 일치·status·assessable 검증, legacy miniature 필수 조건 제거 검토 |
| `src/main/java/com/fast/backend/station/domain/StationMeasurement.java` | 세션과 안전 분석 결과 보존 필드 추가 검토 |
| `src/main/resources/mapper/StationMeasurementMapper.xml` | 신규 측정·세션·안전 필드 매핑 추가 |
| `src/main/resources/db/schema-fr202-placement-draft.sql` | 본 문서의 목표 테이블·키·제약조건과 함께 최종 검토 |
| `src/main/resources/db/schema.sql` | 초기 스키마 확정 시 목표 초안의 cargo·세션·측정·슬롯·운반 구조 반영 |
| `src/main/resources/mapper/StorageSlotMapper.xml` 및 관련 domain | 랙·층 조인을 제거하고 `slot_code`, `usable_height`, `fork_height`, pose 기반으로 평탄화 |
| `ai/src/station/tipping.py` | 돌출률에 의한 2% 위험등급 상승을 제거하거나 별도 gate로 분리하여 목표 5% 정책과 일치 |
| `docs/ai/station-measurement-handoff.md` | cargo-only 높이 계약, 세션 식별자, 독립 overhang/tipping 계약으로 갱신 |
| `docs/ai/samples/station_measurement_*.json` | 목표 세션·화물·안전 필드를 포함하는 예시로 갱신 |
| placement·station·transport 관련 테스트 | 경계 높이, 5% 돌출, 안전등급, 정렬 tie-breaker, stale session, 동시 예약 회귀 테스트 추가 |
| MQTT/API 연동 규격 | 최종 요청·응답 필드명과 station session 전달 토픽 확정 |

위 항목은 구현 순서와 담당자를 정한 뒤 별도 이슈로 분리한다. 이 문서에 기록했다는 이유만으로 구현 완료로 간주하지 않는다.
