# MVP 데이터베이스 스키마

실행 기준 파일은 `src/main/resources/db/schema.sql`이다. 신규 DB를 생성해 사용하는 MVP 기준이며 기존 데이터 마이그레이션은 범위에서 제외한다.

## 단위

- 길이·좌표·포크 높이: `m`
- 속도: `m/s`
- 방향: degree, `[0, 360)`
- 화물 높이: 팔레트 제외
- 팔레트 높이: DB에 저장하지 않고 설정값 `0.12m` 사용

## 테이블

### cargo

| 컬럼 | 의미 |
|---|---|
| `cargo_id` | 입하부터 출하까지 사용하는 화물 식별자. 팔레트와 화물을 분리하지 않는다. |
| `created_at` | 화물 최초 등록 시각 |

### station_session

| 컬럼 | 의미 |
|---|---|
| `session_id` | 한 번의 측정 요청 식별자 |
| `cargo_id` | 측정 대상 화물 |

세션 행은 측정 이력과 화물을 연결하기 위해 삭제하지 않는다.

### station_state

단일 측정 위치의 점유 상태를 나타내는 한 행짜리 테이블이다.

| 컬럼 | 의미 |
|---|---|
| `singleton_id` | 항상 `1` |
| `active_session_id` | 현재 측정 중인 세션, 유휴 시 null |
| `acquired_at` | 점유 시작 시각, TTL 회수 기준 |

### station_measurement

| 컬럼 | 의미 |
|---|---|
| `sequence_no` | 백엔드 수신 순서 |
| `measurement_id` | 측정 생산자가 만든 중복 방지 키 |
| `session_id` | 측정 세션, 세션당 최종 결과 1건 |
| `status` | `OK`, `DIMENSIONS_ONLY`, `NO_DETECTION`, `UNRELIABLE` |
| `cargo_height` | 팔레트 제외 화물 높이(m) |
| `tipping_level` | `SAFE`, `WARNING`, `DANGER` |
| `overhang_ratio` | 팔레트 대비 돌출 비율 |
| `created_at` | 백엔드 저장 시각 |

폭·길이·부피와 AI 원본 bbox는 현재 배치 계산에 사용하지 않으므로 저장하지 않는다.

### vehicle

| 컬럼 | 의미 |
|---|---|
| `vehicle_id` | 차량 식별자 |
| `name` | 관제 표시 이름 |
| `active` | 차량 사용 가능 여부 |
| `created_at`, `updated_at` | 등록·수정 시각 |

### vehicle_current_status

차량별 최신 상태 한 행만 유지한다.

| 컬럼 | 의미 |
|---|---|
| `vehicle_id` | 차량 식별자 |
| `status`, `battery` | 주행 상태와 배터리 잔량 |
| `position_x`, `position_y`, `position_frame` | 최신 위치와 좌표계 |
| `heading`, `speed` | 진행 방향과 속도 |
| `fork_height`, `fork_state`, `fork_error_code` | 최신 포크 상태 |
| `has_cargo`, `cargo_id` | 차량이 보고한 화물 적재 상태 |
| `message_at` | 위치 생산 시각. stale 위치 차단에만 사용 |
| `received_at` | 마지막으로 수락한 메시지의 백엔드 수신 시각 |

차량 크기(`footprint`)와 포크 하단 리미트는 DB 값이 아니다. 차량 크기는 설정 상수, 하단 정지는 임베디드 리미트 스위치 책임이다.

### storage_slot

랙 구조를 별도 테이블로 분리하지 않고 실제 적재 위치만 관리한다.

| 컬럼 | 의미 |
|---|---|
| `slot_code` | 적재 위치 식별자 |
| `usable_height` | 사용할 수 있는 수직 높이 |
| `fork_height` | 적재 시 목표 포크 높이 |
| `destination_x`, `destination_y`, `destination_heading` | Nav2 접근 목표 |
| `status` | `EMPTY`, `RESERVED`, `OCCUPIED`, `BLOCKED` |
| `reserved_task_id` | 현재 예약을 소유한 운반 작업 |
| `stored_cargo_id` | 현재 적재된 화물 |

### transport_task

| 컬럼 | 의미 |
|---|---|
| `id`, `task_code` | 내부 키와 외부 작업 식별자 |
| `cargo_id` | 운반 대상 화물 |
| `measurement_session_id` | AI의 세션 생성 요청 후 작업과 결과를 연결하는 세션 |
| `measurement_id` | 측정 완료 후 연결되는 배치 판단 근거. 측정 전에는 null |
| `vehicle_id` | 배정 차량, 배정 전 null |
| `destination_slot_code` | 측정 완료 후 선택한 적재 위치. 측정 전에는 null |
| `destination_x`, `destination_y`, `destination_heading`, `fork_height` | 측정 완료 시점의 목적지 스냅샷. 측정 전에는 null |
| `status` | 작업 진행 상태 |
| `assigned_at` ~ `failed_at`, `created_at` | 상태별 이력 시각 |

작업은 측정 전에 생성된다. MOVE 성공 후 AI가 측정 세션을 생성하면 `measurement_session_id`가 연결되고, 최종 측정 결과가 적합할 때 목적지 예약과 스냅샷 저장을 함께 처리한다. 목적지 스냅샷은 슬롯 설정이 나중에 바뀌어도 이미 생성된 작업의 실행 목표를 보존한다.

### vehicle_command

| 컬럼 | 의미 |
|---|---|
| `command_id` | MQTT 명령과 결과를 연결하는 식별자 |
| `task_id` | 관련 운반 작업, 없으면 null |
| `vehicle_id` | 명령 대상 차량 |
| `command` | 실행 명령 |
| `target_system` | `ROS2`, `EMBEDDED`, `ALL` |
| `status` | 발행·처리 상태 |
| `result_message` | 수신한 처리 결과 설명 |
| `completed_at`, `created_at` | 완료·생성 시각 |

MQTT의 `payload`, `commandCategory`, 요청 사유는 실행 중 전달값이며 DB에는 저장하지 않는다.

## 삭제한 구조

- `pallet`, `rack`, `rack_level`: MVP에서 화물과 팔레트가 분리되지 않고 적재 위치만 필요함
- `ai_cargo_analysis`, detection box: 최종 측정값만 사용함
- 차량 상태 이력·포크 별도 상태·load safety: 최신 통합 상태로 충분함
- `transport_command`, `embedded_vehicle_command`: `vehicle_command`로 통합함
