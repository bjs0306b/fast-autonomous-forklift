# 적재 공간 인식

## 목적

통제된 Isaac Sim 창고에서 사전에 정의한 적재 위치와 현재 점유 상태를 관제 DB로 관리한다. MVP는 카메라나 SLAM으로 랙을 자동 발견하지 않는다.

## 전제

- 창고 맵과 적재 위치는 실행 중 바뀌지 않는다.
- 모든 적재 위치는 동일한 1.1m × 1.1m 팔레트를 수용하도록 구성한다.
- Isaac Sim 환경이 확정되면 각 위치의 접근 좌표·방향·포크 높이·가용 높이를 DB 초기 데이터로 넣는다.
- 적재와 하역은 반드시 관제 작업을 통해 수행하고, 작업 결과로 점유 상태를 갱신한다.
- 현실 스케일 좌표와 길이는 `m`, 방향은 degree를 사용한다.

## 적재 위치 데이터

MVP는 별도 `rack`, `rack_level` 테이블을 두지 않고 `storage_slot` 한 테이블로 관리한다.

| 속성 | 의미 |
|---|---|
| `slot_code` | 화면과 시스템에서 사용하는 적재 위치 식별자 |
| `usable_height` | 화물과 팔레트가 들어갈 수 있는 수직 높이 |
| `fork_height` | 적재 시 목표 포크 높이 |
| `destination_x`, `destination_y` | 위치 접근 좌표 |
| `destination_heading` | 적재 위치를 바라보는 차량 방향 |
| `status` | `EMPTY`, `RESERVED`, `OCCUPIED`, `BLOCKED` |
| `reserved_task_id` | 위치를 예약한 운반 작업 |
| `stored_cargo_id` | 현재 적재된 화물 |

랙 이름·층·열은 MVP 계산에 사용하지 않는다. 사람이 알아볼 수 있는 규칙을 `slot_code`에 적용한다. 예: `R1-L2-C3`.

## 상태 전이

```text
초기 등록             → EMPTY 또는 BLOCKED
운반 작업 생성        → RESERVED
적재 성공             → OCCUPIED
작업 실패·취소        → EMPTY
하역 완료             → EMPTY
점검 또는 사용 중지   → BLOCKED
```

작업 생성 시 `EMPTY → RESERVED`를 조건부 갱신하므로 두 작업이 같은 위치를 동시에 예약할 수 없다.

## 구현 순서

1. Isaac Sim 담당자가 창고 맵과 적재 위치를 배치한다.
2. 각 위치의 `slot_code`, 가용 높이, 접근 pose, 포크 높이를 확정한다.
3. 값을 `storage_slot` 초기 데이터로 등록한다.
4. FR-202가 안전 조건과 높이 조건을 만족하는 `EMPTY` 위치를 선택한다.
5. ROS2/Nav2가 저장된 접근 pose로 이동한다.
6. 작업 완료 결과에 따라 `storage_slot` 상태를 변경한다.

## MVP 이후 범위

- 실제 센서 기반 랙·빈 공간 자동 탐색
- 창고 구조의 동적 추가·변경
- 평면 폭·길이 적합성 판단
- 하역 API와 재배치 최적화
