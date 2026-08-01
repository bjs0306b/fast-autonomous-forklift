# MVP 데이터베이스 ERD

정본 SQL은 `src/main/resources/db/schema.sql`이다. 모든 길이·좌표는 `m`, 속도는 `m/s`, 방향은 `degree`를 사용한다.

```mermaid
erDiagram
    cargo {
        varchar cargo_id PK
        datetime created_at
    }

    station_session {
        varchar session_id PK
        varchar cargo_id FK
    }

    station_state {
        int singleton_id PK
        varchar active_session_id FK
        datetime acquired_at
    }

    station_measurement {
        bigint sequence_no PK
        varchar measurement_id UK
        varchar session_id FK,UK
        varchar status
        double cargo_height
        varchar tipping_level
        double overhang_ratio
        datetime created_at
    }

    vehicle {
        varchar vehicle_id PK
        varchar name
        boolean active
        datetime created_at
        datetime updated_at
    }

    vehicle_current_status {
        varchar vehicle_id PK,FK
        varchar status
        int battery
        double position_x
        double position_y
        varchar position_frame
        double heading
        double speed
        double fork_height
        varchar fork_state
        varchar fork_error_code
        boolean has_cargo
        varchar cargo_id
        datetime message_at
        datetime received_at
    }

    storage_slot {
        varchar slot_code PK
        double usable_height
        double fork_height
        double destination_x
        double destination_y
        double destination_heading
        varchar status
        bigint reserved_task_id
        varchar stored_cargo_id FK,UK
    }

    transport_task {
        bigint id PK
        varchar task_code UK
        varchar cargo_id FK
        varchar measurement_id FK
        varchar vehicle_id FK
        varchar destination_slot_code FK
        double destination_x
        double destination_y
        double destination_heading
        double fork_height
        varchar status
        datetime assigned_at
        datetime started_at
        datetime picked_up_at
        datetime completed_at
        datetime failed_at
        datetime created_at
    }

    vehicle_command {
        varchar command_id PK
        bigint task_id FK
        varchar vehicle_id FK
        varchar command
        varchar target_system
        varchar status
        varchar result_message
        datetime completed_at
        datetime created_at
    }

    cargo ||--o{ station_session : measured_for
    station_session o|--o| station_state : active
    station_session ||--o| station_measurement : produces

    vehicle ||--o| vehicle_current_status : has
    vehicle o|--o{ transport_task : assigned
    vehicle ||--o{ vehicle_command : receives

    cargo ||--o{ transport_task : transported
    cargo o|--o| storage_slot : stored
    station_measurement ||--o{ transport_task : evidence
    storage_slot ||--o{ transport_task : destination
    transport_task o|--o{ vehicle_command : issues
```

## 관계 해설

- 화물 하나는 여러 번 측정 세션을 가질 수 있지만 세션 하나의 최종 측정 결과는 최대 한 건이다.
- `station_state`는 `singleton_id=1`인 단일 행으로 현재 활성 세션만 가리킨다.
- 차량 현재 상태는 차량별 한 행만 유지한다.
- 적재 위치는 최대 한 화물을 보관하고, 운반 작업 생성 시 한 작업이 예약한다.
- 운반 작업은 실제 사용한 측정 결과와 선택한 목적지 정보를 보존한다.
- 차량 명령은 운반 작업과 연결될 수도 있고 독립 명령일 수도 있다.

`storage_slot.reserved_task_id`는 생성 순환을 피하기 위해 물리 FK를 두지 않는다. 애플리케이션이 `EMPTY → RESERVED` 조건부 갱신으로 소유권을 보장한다.
