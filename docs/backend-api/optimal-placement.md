# 최적 위치 산출

## 1. 목적

FR-201에서 관리하는 랙·적재 위치 정보와 측정 스테이션의 화물 치수를 이용해,
현재 적재 가능한 위치 중 화물에 가장 적합한 위치를 선택하는 기준을 정의한다.

FR-202는 다음 두 환경을 모두 지원하는 것을 목표로 한다.

- `MINIATURE`: 모형 지게차와 모형 랙을 사용하는 실제 데모 환경
- `REAL_SCALE_SIM`: 현실 크기의 지게차·팔레트·랙을 배치한 Isaac Sim 환경

적재 위치 선정과 예약은 관제 백엔드가 담당한다. 선정된 위치까지의 경로 생성과 차량 제어는
ROS2/Nav2 또는 Isaac Sim 담당 영역으로 구분한다.

## 2. MVP 전제

- 랙 구조와 적재 위치는 환경별로 사전에 정의하고 관제 DB에 등록한다.
- 한 적재 위치에는 팔레트 한 개만 적재한다.
- 적재 가능 여부는 팔레트를 포함한 화물의 총높이를 기준으로 판단한다.
- 화물 깊이와 무게는 측정하지 않으므로 MVP 추천 조건에서 제외한다.
- 적재 위치 상태는 `EMPTY`, `RESERVED`, `OCCUPIED`, `UNKNOWN`으로 관리한다.
- 작업 생성과 동시에 선택한 위치를 예약하여 다른 작업의 중복 선택을 방지한다.
- 이미 적재된 화물은 작은 적재 위치가 비었다는 이유만으로 자동 재배치하지 않는다.

## 3. 단위 규격

### 3.1 치수 단위

FR-202에서 사용하는 다음 값은 모두 `mm`로 통일한다.

- 화물 가로·세로·높이
- 팔레트 가로·세로·높이
- 랙과 적재 위치의 내부 가용 크기
- 적재 여유 높이
- 남는 높이
- 포크 적재 높이

필드명에도 단위를 붙여 `widthMm`, `heightMm`, `clearanceMm`처럼 표현한다.

### 3.2 좌표 단위 예외

ROS2/Nav2의 `map` 좌표는 기존 생태계와 프로젝트 규격에 맞춰 `m`를 유지한다.

- `destination.x`, `destination.y`: m
- `destination.heading`: degree
- `forkHeightMm`: mm

추천 로직에서 치수와 좌표를 직접 더하거나 비교하지 않으며, 이동거리 계산 결과만 `m`로 관리한다.

## 4. 환경별 규격

### 4.1 팔레트 규격

현재 프로젝트의 T-11 규격을 기준으로 다음 값을 사용한다.

| 환경 | 가로 | 세로 | 높이 | 축척 |
|---|---:|---:|---:|---:|
| `REAL_SCALE_SIM` | 1100mm | 1100mm | 120mm | 1:1 |
| `MINIATURE` | 110mm | 110mm | 12mm | 1:10 |

팔레트 포함 총높이를 입력받는 경우 팔레트 높이를 추천 로직에서 다시 더하지 않는다.

### 4.2 적재 여유 높이

`MINIATURE` 환경의 적재 여유 높이는 `35mm`로 정한다.

| 구성 | 여유값 | 적용 이유 |
|---|---:|---|
| 포크 상승·기울어짐 여유 | 15mm | 팔레트를 선반 바닥보다 높게 들어 올려 진입할 때 발생하는 기울어짐과 높이 편차 대응 |
| 측정·통합 안전 여유 | 20mm | 일부 측정 오차와 카메라·거리 센서·랙 모델·제어 오차를 보수적으로 흡수 |
| 합계 | **35mm** | 미니어처 적재 가능 판정에 적용 |

`20mm`는 실물 측정 오차를 1:10로 단순 환산한 값이 아니다. 통제된 미니어처 환경에서 발생할 수
있는 측정·모델링·제어 오차를 함께 흡수하기 위해 정한 보수적인 통합 안전 마진이다.

`REAL_SCALE_SIM`은 미니어처의 `35mm`를 단순히 10배로 확대하지 않는다. 현실 스케일 지게차의
포크 제어 오차, 랙 구조, 팔레트 변형과 필요한 상승량이 확정된 뒤 별도의 `clearanceMm`를 설정한다.

| 환경 | `clearanceMm` |
|---|---:|
| `MINIATURE` | 35 |
| `REAL_SCALE_SIM` | TBD |

## 5. 측정값 정규화

측정 스테이션의 현재 결과에는 `cm`와 `mm`가 함께 존재하므로, FR-202를 호출하기 전에 환경에 맞는
`totalHeightMm`로 변환한다.

### 5.1 현실 스케일 Isaac Sim

```text
totalHeightMm = total_height_cm × 10
```

### 5.2 미니어처 환경

```text
totalHeightMm = miniature_total_height_mm
```

두 환경 모두 추천 서비스에는 다음과 같이 동일한 단위로 전달한다.

```json
{
  "environmentType": "MINIATURE",
  "totalHeightMm": 156.0,
  "palletWidthMm": 110.0,
  "palletLengthMm": 110.0
}
```

`totalHeightMm`에는 팔레트 높이가 포함되어 있어야 한다. 화물만의 높이를 전달하는 경우에는
정규화 단계에서 환경별 팔레트 높이를 한 번만 더한다.

## 6. 적재 가능 판정

환경별 설정에서 여유 높이를 가져와 필요한 높이를 계산한다.

```text
requiredHeightMm = totalHeightMm + clearanceMm

적재 가능 조건:
requiredHeightMm <= locationHeightMm
```

다음 조건을 모두 만족하는 위치만 추천 후보로 사용한다.

1. 적재 위치 상태가 `EMPTY`다.
2. `locationWidthMm`과 `locationLengthMm`이 환경별 팔레트 규격 이상이다.
3. `requiredHeightMm`이 `locationHeightMm` 이하이다.
4. 랙 접근 좌표가 등록되어 있다.
5. 차량 진입 방향과 `forkHeightMm`가 등록되어 있다.
6. 사용 중지 또는 상태 확인 불가 위치가 아니다.

후보가 하나도 없으면 임의의 위치를 반환하지 않고 `적재 가능한 위치 없음`으로 처리한다.

## 7. 추천 우선순위

MVP는 다음 순서로 최적 위치를 선택한다.

1. 적재 가능한 위치만 필터링한다.
2. 적재 후 남는 높이가 가장 작은 위치를 선택한다.
3. 남는 높이가 같으면 픽업 위치에서 이동거리가 짧은 위치를 선택한다.
4. 이동거리도 같으면 낮은 층을 선택한다.
5. 모든 조건이 같으면 적재 위치 식별자 순으로 선택한다.

```text
remainingHeightMm
= locationHeightMm
- totalHeightMm
- clearanceMm
```

이동거리는 SLAM 지도가 확정되기 전까지 두 좌표 사이의 직선거리로 계산할 수 있다.
Nav2 연동 이후에는 실제 경로 길이 또는 예상 이동시간으로 교체할 수 있다.

```text
distanceM = sqrt((pickupX - destinationX)^2 + (pickupY - destinationY)^2)
```

## 8. 위치 예약과 상태 변경

추천과 예약 사이에 다른 작업이 같은 위치를 선택할 수 있으므로 작업 생성 시 조건부 갱신을 사용한다.

```text
EMPTY -> RESERVED -> OCCUPIED
```

- 추천 직후 상태가 `EMPTY`인 경우에만 `RESERVED`로 변경한다.
- 예약 경쟁에서 실패하면 후보를 다시 조회하고 추천을 다시 수행한다.
- 적재 성공 시 `OCCUPIED`로 변경한다.
- 작업 실패 또는 취소 시 `EMPTY`로 되돌린다.

## 9. 재배치 정책

작은 랙에 빈자리가 생겨도 큰 랙에 이미 적재한 작은 화물을 자동으로 이동하지 않는다.
재배치는 추가 운송 작업과 충돌 위험을 발생시키기 때문이다.

다음 조건을 모두 만족할 때만 후속 확장 기능으로 재배치를 검토한다.

- 새 화물이 현재 빈 위치에 들어가지 않는다.
- 큰 적재 위치를 작은 화물이 점유하고 있다.
- 작은 화물을 새로 빈 작은 위치로 옮기면 큰 화물을 적재할 수 있다.
- 재배치 이동 비용이 작업 지연보다 작다.

재배치 작업 순서 계획은 FR-203의 후속 범위로 둔다.

## 10. 추천 결과 참고 규격

```json
{
  "environmentType": "MINIATURE",
  "rackId": "TBD",
  "level": null,
  "column": null,
  "destination": {
    "x": null,
    "y": null,
    "heading": null,
    "forkHeightMm": null
  },
  "totalHeightMm": null,
  "clearanceMm": 35,
  "requiredHeightMm": null,
  "remainingHeightMm": null,
  "distanceM": null,
  "reason": "TBD"
}
```

정확한 API·MQTT 필드명은 백엔드와 통신 담당자가 합의한다.

## 11. SQL 및 연동 규격

SQL 스키마에서도 모든 화물·팔레트·랙·적재 위치 치수를 `mm`로 통일한다.
단위가 불명확한 `width`, `height` 대신 컬럼명에 `_mm`를 붙인다.

```text
cargo.width_mm
cargo.length_mm
cargo.height_mm
rack_level.clear_width_mm
rack_level.clear_length_mm
rack_level.clear_height_mm
rack_level.fork_height_mm
storage_slot.width_mm
storage_slot.length_mm
storage_slot.height_mm
```

기존 `m` 단위 데이터가 있다면 스키마 변경과 값 변환을 같은 마이그레이션에서 수행한다.
좌표 컬럼 `position_x`, `destination_x` 등은 ROS2/Nav2의 `map` 좌표 규격에 따라 `m`를 유지하고,
치수 컬럼과 혼동되지 않도록 API·SQL 문서에 단위를 명시한다.

측정 결과에는 다음 표준 필드가 필요하다.

```text
height_mm
width_mm
total_height_mm
pallet_height_mm
environment_type
```

## 12. 현재 구현에서 확인할 변경점

현재 `PlacementService`는 화물과 적재 위치의 치수를 `m`로 비교하고, 남는 부피가 가장 작은 위치를
우선 선택한다. FR-202 규격과 맞추려면 다음 내용을 검토해야 한다.

- 치수 입력과 계산을 `mm`로 통일
- 환경별 팔레트 규격과 `clearanceMm` 설정 추가
- 팔레트 포함 `totalHeightMm`을 적재 판단 입력으로 사용
- 미니어처 환경에 `clearanceMm = 35` 적용
- 현실 스케일 Isaac Sim의 `clearanceMm`를 별도 설정으로 관리
- 고정 팔레트 규격으로 가로·세로 적합성 판정
- `wastedVolume` 우선 정렬을 제거하고 `remainingHeightMm`을 최우선으로 사용
- 깊이와 90도 회전 방향을 추천 점수에서 제외
- 접근 좌표·방향·포크 높이가 없는 위치를 후보에서 제외
- 추천 결과에 환경, 적용 여유 높이, 필요 높이, 남는 높이와 선정 이유 포함
- SQL·DTO·MQTT 사이의 치수 단위를 `mm`로 통일

## 13. 검증 항목

두 환경에 대해 다음 항목을 각각 검증한다.

- `totalHeightMm + clearanceMm`과 랙 가용 높이가 같으면 적재 가능하다.
- 랙 가용 높이가 `requiredHeightMm`보다 작으면 후보에서 제외된다.
- `RESERVED`, `OCCUPIED`, `UNKNOWN` 위치는 추천되지 않는다.
- 여러 후보가 있으면 `remainingHeightMm`이 가장 작은 위치가 선택된다.
- 남는 높이가 같으면 가까운 위치가 선택된다.
- 같은 위치를 동시에 예약할 때 하나의 작업만 성공한다.
- 접근 좌표 또는 포크 높이가 없는 위치는 추천되지 않는다.
- 적재 가능한 위치가 없으면 명확한 실패 결과를 반환한다.
- 팔레트 포함 높이에 팔레트 높이가 중복으로 더해지지 않는다.
- 미니어처와 현실 스케일 값이 같은 단위로 혼합되지 않는다.

## 14. 완료 조건

- `MINIATURE`, `REAL_SCALE_SIM` 환경을 구분해 추천할 수 있다.
- 모든 치수 계산과 인터페이스가 `mm`를 사용한다.
- 미니어처 환경은 `35mm` 여유 높이를 반영한다.
- 현실 스케일 환경은 별도로 확정한 `clearanceMm`를 반영한다.
- 빈 위치 중 남는 높이와 이동거리를 기준으로 추천 위치를 산출한다.
- 추천 위치가 작업 생성과 함께 원자적으로 예약된다.
- 적재 성공·실패·취소 결과에 따라 위치 상태가 갱신된다.
- 경계값, 환경별 규격, 후보 없음, 상태 제외, 동시 예약 테스트가 통과한다.

## 15. MVP 제외 범위

- 화물 무게와 랙 하중 최적화
- 화물 깊이 측정
- 실시간 랙 자동 탐색
- 적재 완료 화물의 상시 재배치
- 실제 Nav2 경로 비용을 이용한 전역 최적화
- 여러 화물의 입고 순서를 함께 고려하는 스케줄링
