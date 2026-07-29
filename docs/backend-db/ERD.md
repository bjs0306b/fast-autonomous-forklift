# F.A.S.T. ERD

## 기준

- 근거 파일: `src/main/resources/db/schema.sql` (**origin/develop 기준**, 2026-07-29 확인 — 20개 테이블)
  - ⚠️ 현재 브랜치(`feature/S15P11A304-dim-eval-onboard`)의 schema.sql에는 `cargo`·`pallet`·`rack`·`rack_level`·`storage_slot`·`transport_task`·`transport_command` 7개가 아직 없다. 이 문서는 develop을 따른다.
- 컬럼·제약은 DDL에 있는 것만 옮겼다. 코드에 없는 관계는 그리지 않았다.
- 선 표기
  - **실선** = DB에 FK 제약이 실제로 걸린 관계
  - **점선** = FK 없이 문자열 키로만 이어지는 논리적 관계 (`vehicle_id` / `cargo_id` / `forklift_id` 등)
    → AI·스테이션·임베디드 테이블은 MQTT로 들어온 결과를 그대로 적재하는 성격이라 FK를 걸지 않았다(`schema.sql` 주석 근거).

---

## 1. 전체 관계도 (키 컬럼만)

```mermaid
erDiagram
    vehicle                    ||--o| vehicle_current_status : "현재 상태 1행"
    vehicle                    ||--o{ vehicle_status_history : "상태 이력"
    vehicle                    ||..o{ embedded_vehicle_command : "forklift_id"
    vehicle                    ||..o| vehicle_fork_current_status : "forklift_id"
    vehicle                    ||..o{ embedded_error_history : "forklift_id"
    vehicle                    ||..o{ ai_cargo_analysis : "vehicle_id"
    vehicle                    ||..o{ transport_task : "vehicle_id"

    ai_cargo_analysis          ||--o{ ai_cargo_detection_box : "박스 0..N"
    station_measurement        ||--o{ station_measurement_box : "박스 0..N"

    cargo                      ||--o{ pallet : "적재 화물"
    cargo                      ||--o{ transport_task : "운반 대상"
    cargo                      ||..o{ ai_cargo_analysis : "cargo_id"
    cargo                      ||..o{ storage_slot : "stored_cargo_id"

    pallet                     ||--o{ transport_task : "픽업 파렛트"

    rack                       ||--o{ rack_level : "단"
    rack_level                 ||--o{ storage_slot : "슬롯"
    storage_slot               ||--o{ transport_task : "목적 슬롯"
    transport_task             ||..o{ storage_slot : "reserved_task_id"
    transport_task             ||--o{ transport_command : "발행 명령 1..N"

    vehicle {
        bigint   id PK
        varchar  vehicle_id UK
        varchar  name
        varchar  source
        varchar  vehicle_type
        boolean  active
    }
    vehicle_current_status {
        varchar  vehicle_id PK,FK
        varchar  status
        int      battery
    }
    vehicle_status_history {
        bigint   id PK
        varchar  vehicle_id FK
        varchar  status
        datetime message_at
    }
    ai_cargo_analysis {
        bigint   id PK
        varchar  analysis_id UK
        varchar  vehicle_id
        varchar  cargo_id
        varchar  status
    }
    ai_cargo_detection_box {
        bigint   id PK
        bigint   analysis_id FK
        varchar  class_name
    }
    station_measurement {
        bigint   id PK
        varchar  measurement_id UK
        varchar  station_id
        varchar  status
    }
    station_measurement_box {
        bigint   id PK
        bigint   station_measurement_id FK
        int      box_order
    }
    embedded_vehicle_command {
        bigint   id PK
        varchar  command_id UK
        varchar  forklift_id
        varchar  command
        varchar  status
    }
    vehicle_fork_current_status {
        varchar  forklift_id PK
        varchar  fork_state
        boolean  limit_bottom
    }
    embedded_error_history {
        bigint   id PK
        varchar  forklift_id
        varchar  error_code
        varchar  severity
    }
    cargo {
        bigint   id PK
        varchar  cargo_id UK
        double   volume
    }
    pallet {
        bigint   id PK
        varchar  pallet_id UK
        varchar  cargo_id FK
        varchar  status
    }
    rack {
        bigint   id PK
        varchar  rack_code UK
    }
    rack_level {
        bigint   id PK
        bigint   rack_id FK
        int      level_number UK
    }
    storage_slot {
        bigint   id PK
        varchar  slot_code UK
        bigint   rack_level_id FK
        varchar  status
        varchar  reserved_task_id
        varchar  stored_cargo_id
    }
    transport_task {
        bigint   id PK
        varchar  task_code UK
        varchar  cargo_id FK
        varchar  pallet_id FK
        varchar  vehicle_id
        bigint   destination_slot_id FK
        varchar  status
    }
    transport_command {
        bigint   id PK
        varchar  command_id UK
        bigint   task_id FK
        varchar  vehicle_id
        varchar  status
    }
```

---

## 2. 도메인별 상세

### 2.1 차량 (vehicle 계열)

`vehicle`이 등록 원장이고, 현재 상태는 차량당 1행(`vehicle_current_status`), 변경 과정은 `vehicle_status_history`에 누적한다.
`fork_height`·`has_cargo`·`cargo_id`·`footprint_*`는 Isaac 확장 필드로 전부 nullable이며, ROS2 상태 메시지가 null로 덮어쓰지 않도록 `VehicleStatusService`가 "기존 값 보존"으로 병합한다.

```mermaid
erDiagram
    vehicle ||--o| vehicle_current_status : ""
    vehicle ||--o{ vehicle_status_history : ""

    vehicle {
        bigint   id PK
        varchar  vehicle_id UK "업무 키"
        varchar  name
        varchar  source "ISAAC / ROS2 등"
        varchar  vehicle_type
        boolean  active
        datetime created_at
        datetime updated_at
    }
    vehicle_current_status {
        varchar  vehicle_id PK,FK
        varchar  status "기본 UNKNOWN"
        int      battery
        double   position_x
        double   position_y
        double   heading
        double   speed
        double   fork_height "Isaac 확장"
        boolean  has_cargo "Isaac 확장"
        varchar  cargo_id "Isaac 확장"
        double   footprint_length "Isaac 확장"
        double   footprint_width "Isaac 확장"
        datetime message_at
        datetime received_at
        datetime updated_at
    }
    vehicle_status_history {
        bigint   id PK
        varchar  vehicle_id FK
        varchar  status
        int      battery
        double   position_x
        double   position_y
        double   heading
        double   speed
        double   fork_height
        boolean  has_cargo
        varchar  cargo_id
        double   footprint_length
        double   footprint_width
        datetime message_at "idx(vehicle_id, message_at DESC)"
        datetime received_at
        datetime created_at
    }
```

### 2.2 AI 화물 분석 (`cargo/detected`)

`analysis_id`가 AI 채번 키라 UNIQUE로 중복 적재를 막는다. `load_direction`은 `["left","front"]` 같은 배열을 쉼표 문자열로 저장한다(JSON 컬럼 미사용 원칙 — H2 호환).

```mermaid
erDiagram
    ai_cargo_analysis ||--o{ ai_cargo_detection_box : ""

    ai_cargo_analysis {
        bigint   id PK
        varchar  analysis_id UK
        varchar  schema_version
        varchar  vehicle_id "FK 아님"
        varchar  cargo_id "FK 아님"
        varchar  status
        double   distance_cm
        double   distance_std_cm
        double   width_cm
        double   height_cm
        double   depth_cm
        double   volume_cm3
        varchar  dimension_scale
        varchar  load_direction "쉼표 구분"
        varchar  load_message
        double   ratio_horizontal
        double   ratio_vertical
        varchar  message
        datetime captured_at
        datetime processed_at "idx(cargo_id, processed_at DESC)"
        datetime received_at
        datetime created_at
    }
    ai_cargo_detection_box {
        bigint   id PK
        bigint   analysis_id FK
        varchar  class_name
        double   confidence
        int      bbox_x
        int      bbox_y
        int      bbox_width
        int      bbox_height
        datetime created_at
    }
```

### 2.3 측정 스테이션 (`fast/station/+/measurement`)

AI 분석과 규격(키 표기·시각 타입·pallet 의미)이 달라 별도 도메인으로 분리했다.
시각은 UTC(`measured_at_utc`) + 오프셋 분(`measured_at_offset_minutes`, +09:00 → 540)으로 쪼개 저장해 `OffsetDateTime`을 손실 없이 복원한다. 파렛트는 박스와 의미가 달라 부모 테이블 컬럼으로 보존한다.

```mermaid
erDiagram
    station_measurement ||--o{ station_measurement_box : ""

    station_measurement {
        bigint   id PK
        varchar  measurement_id UK
        varchar  station_id "idx(station_id, measured_at_utc DESC)"
        varchar  schema_version
        datetime measured_at_utc
        int      measured_at_offset_minutes
        varchar  status
        int      box_count
        int      pallet_bbox_x
        int      pallet_bbox_y
        int      pallet_bbox_width
        int      pallet_bbox_height
        double   pallet_score
        double   front_cm
        double   distance_std_cm
        int      frames_used
        double   height_cm
        double   width_cm
        double   depth_cm "정책상 항상 null"
        int      miniature_scale
        double   miniature_height_mm
        double   miniature_width_mm
        boolean  eccentric
        varchar  load_direction
        double   ratio_x
        double   ratio_y
        double   magnitude
        double   threshold
        varchar  load_message
        datetime received_at
        datetime created_at
    }
    station_measurement_box {
        bigint   id PK
        bigint   station_measurement_id FK
        int      box_order "payload 배열 순서 보존"
        int      bbox_x
        int      bbox_y
        int      bbox_width
        int      bbox_height
        double   score
        datetime created_at
    }
```

### 2.4 차량 명령·임베디드 상태

테이블명 `embedded_vehicle_command`와 컬럼 `forklift_id`는 이름이 도메인과 어긋나지만 운영 DB 이관 위험 때문에 유지한다(애플리케이션은 `vehicleId`, Mapper가 매핑). ROS2 이동 명령까지 이 테이블에 저장한다.

```mermaid
erDiagram
    vehicle ||..o{ embedded_vehicle_command : "forklift_id (FK 없음)"
    vehicle ||..o| vehicle_fork_current_status : "forklift_id (FK 없음)"
    vehicle ||..o{ embedded_error_history : "forklift_id (FK 없음)"

    vehicle {
        varchar vehicle_id UK
    }
    embedded_vehicle_command {
        bigint   id PK
        varchar  command_id UK
        varchar  forklift_id "idx(forklift_id, issued_at DESC)"
        varchar  command
        varchar  target_system "ROS2 / EMBEDDED / ALL"
        varchar  command_category "MOVE / FORK / LOAD / SAFETY"
        varchar  payload_json "JSON 문자열"
        varchar  reason
        varchar  status
        datetime issued_at
        datetime published_at
        datetime completed_at
        varchar  error_code
        varchar  result_message
        varchar  stopped_actions "쉼표 구분"
        boolean  emergency_stop_applied
        boolean  requires_reset
        datetime created_at
        datetime updated_at
    }
    vehicle_fork_current_status {
        varchar  forklift_id PK
        varchar  fork_state
        boolean  limit_bottom
        varchar  error_code
        datetime message_at
        datetime received_at
        datetime updated_at
    }
    embedded_error_history {
        bigint   id PK
        varchar  forklift_id "idx(forklift_id, occurred_at DESC)"
        varchar  error_code
        varchar  error_source
        varchar  severity
        varchar  message
        datetime occurred_at
        datetime received_at
        datetime created_at
    }
```

### 2.5 창고·적재 위치·운반 작업

좌표·크기는 m, heading은 degree로 차량 위치 규격과 동일하다.
`rack` → `rack_level`(단) → `storage_slot`(슬롯) 3단 구조이고, 화물 크기와 슬롯 여유치수(`clear_*`)를 비교해 적재 위치를 추천한다.
`transport_task`가 업무 단위, `transport_command`가 실제 MQTT 발행 단위다 — 재시도하면 한 Task에 여러 command가 붙는다.

```mermaid
erDiagram
    cargo          ||--o{ pallet : ""
    cargo          ||--o{ transport_task : ""
    pallet         ||--o{ transport_task : ""
    rack           ||--o{ rack_level : ""
    rack_level     ||--o{ storage_slot : ""
    storage_slot   ||--o{ transport_task : "destination_slot_id"
    transport_task ||--o{ transport_command : ""
    transport_task ||..o{ storage_slot : "reserved_task_id (FK 없음)"
    cargo          ||..o{ storage_slot : "stored_cargo_id (FK 없음)"

    cargo {
        bigint   id PK
        varchar  cargo_id UK
        double   width
        double   length
        double   height
        double   volume
        datetime created_at
        datetime updated_at
    }
    pallet {
        bigint   id PK
        varchar  pallet_id UK
        varchar  cargo_id FK
        double   pickup_x
        double   pickup_y
        double   pickup_heading
        varchar  status
        datetime created_at
        datetime updated_at
    }
    rack {
        bigint   id PK
        varchar  rack_code UK
        varchar  rack_name
        double   position_x
        double   position_y
        datetime created_at
        datetime updated_at
    }
    rack_level {
        bigint   id PK
        bigint   rack_id FK
        int      level_number "UK(rack_id, level_number)"
        double   clear_width
        double   clear_length
        double   clear_height
        double   fork_height
        datetime created_at
        datetime updated_at
    }
    storage_slot {
        bigint   id PK
        varchar  slot_code UK
        bigint   rack_level_id FK
        double   width
        double   length
        double   height
        double   destination_x
        double   destination_y
        double   destination_heading
        varchar  status "기본 EMPTY"
        varchar  reserved_task_id
        varchar  stored_cargo_id
        datetime created_at
        datetime updated_at
    }
    transport_task {
        bigint   id PK
        varchar  task_code UK
        varchar  cargo_id FK
        varchar  pallet_id FK
        varchar  vehicle_id "FK 아님"
        double   source_x
        double   source_y
        double   source_heading
        bigint   destination_slot_id FK
        double   destination_x
        double   destination_y
        double   destination_heading
        double   fork_height
        varchar  cargo_orientation
        varchar  status
        datetime assigned_at
        datetime started_at
        datetime picked_up_at
        datetime completed_at
        datetime failed_at
        datetime created_at
        datetime updated_at
    }
    transport_command {
        bigint   id PK
        varchar  command_id UK "command-result 역추적 키"
        bigint   task_id FK
        varchar  task_code "조회 편의용 중복 보관"
        varchar  vehicle_id
        varchar  command_type
        varchar  stage "ROS2 단계 결과, 규격 미확정"
        varchar  status
        varchar  payload
        varchar  failure_reason
        datetime published_at
        datetime acknowledged_at
        datetime completed_at
        datetime created_at
        datetime updated_at
    }
```

---

## 3. DB에 저장하지 않는 것

- 차량 **위치**(`forklift/+/location`)와 **경로**(`forklift/+/path`)는 이력 테이블 없이 WebSocket으로만 중계한다. 필요해지면 `vehicle_location_history`로 별도 분리하기로 했다.
- 인덱스는 전부 `CREATE TABLE` 인라인이다. MySQL이 `CREATE INDEX IF NOT EXISTS`를 지원하지 않는데, 테스트에서 schema.sql이 컨텍스트마다 재실행돼 "이미 존재" 오류가 나기 때문이다.
