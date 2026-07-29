-- FR-501-1 다중 차량 등록·상태 집계: vehicle / vehicle_current_status DDL
-- (prompt16.md 7장 "실행 가능한 MySQL DDL")
--
-- 이 파일은 두 용도로 쓰인다.
--   1) 팀 EC2/로컬 MySQL에 실제 테이블을 만들 때 이 SQL을 그대로 복사해 실행한다(수동 실행 전제,
--      Spring Boot가 이 파일을 자동으로 실 MySQL에 실행하도록 설정하지 않았다 — 팀이 공유하는 DB에
--      애플리케이션 기동만으로 스키마가 바뀌는 것을 막기 위한 의도적 설계. answer15.md 5장 참고).
--   2) application-test.yml에서 spring.sql.init.schema-locations로 지정해, mvnw test 실행 시
--      임베디드 H2(MySQL 호환 모드)에 자동 적용된다 — 이 DDL이 실제로 실행 가능한지 매 테스트마다
--      검증된다.
--
-- ENGINE=InnoDB, DEFAULT CHARSET=utf8mb4 같은 MySQL 전용 절은 일부러 넣지 않았다. AWS RDS/MySQL 8
-- 기본값이 이미 InnoDB·utf8mb4이고, 이 절을 빼면 H2에서도 그대로 실행할 수 있어 테스트로 실제 검증이
-- 가능하기 때문이다. 팀 MySQL 서버의 기본 엔진/문자셋이 다르면 CREATE TABLE 뒤에 팀이 별도로
-- ALTER TABLE ... ENGINE=InnoDB, CONVERT TO CHARACTER SET utf8mb4 등을 추가하면 된다.

CREATE TABLE IF NOT EXISTS vehicle (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id   VARCHAR(50)  NOT NULL,
    name         VARCHAR(100) NOT NULL,
    source       VARCHAR(20)  NOT NULL,
    vehicle_type VARCHAR(30)  NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NOT NULL,
    CONSTRAINT uk_vehicle_vehicle_id UNIQUE (vehicle_id)
);

-- 차량당 최신 상태 한 행만 유지(vehicle_id가 PK). 상태 이력 테이블(vehicle_status_history)은
-- 이번 FR-501-1 범위에 포함하지 않는다 — 필요해지면 후속 Story로 분리한다(prompt16.md 7장 조건,
-- answer15.md 15장 참고).
-- Isaac 확장 필드(fork_height/has_cargo/cargo_id/footprint_length/footprint_width)는 prompt32.md 1장
-- 4번 확정에 따라 추가했다. Isaac 상태 메시지에만 들어오는 값이라 전부 nullable이며, ROS2 상태 메시지가
-- 이 컬럼들을 null로 덮어쓰지 않도록 VehicleStatusService가 "기존 값 보존" 정책으로 병합한다
-- (그 클래스 Javadoc의 "Isaac 확장 필드 병합 정책" 참고).
CREATE TABLE IF NOT EXISTS vehicle_current_status (
    vehicle_id       VARCHAR(50)  NOT NULL PRIMARY KEY,
    status           VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    battery          INT          NULL,
    position_x       DOUBLE       NULL,
    position_y       DOUBLE       NULL,
    heading          DOUBLE       NULL,
    speed            DOUBLE       NULL,
    fork_height      DOUBLE       NULL,
    has_cargo        BOOLEAN      NULL,
    cargo_id         VARCHAR(50)  NULL,
    footprint_length DOUBLE       NULL,
    footprint_width  DOUBLE       NULL,
    message_at       DATETIME     NULL,
    received_at      DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,
    CONSTRAINT fk_vehicle_current_status_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id)
);

-- 차량 상태 변경 이력(FR-504, prompt22.md). vehicle_current_status는 차량당 최신 상태 1행만 유지하므로,
-- 상태가 바뀌어 온 과정을 그대로 남기기 위해 별도 테이블에 누적 저장한다. 위치 전용(MQTT 위치 메시지)
-- 이력 저장은 이번 범위에서 제외하고, 필요해지면 vehicle_location_history로 별도 분리한다(prompt22.md 4장).
-- 조회 인덱스(vehicle_id, message_at DESC)는 별도 CREATE INDEX 문 대신 CREATE TABLE에 인라인으로
-- 넣었다. MySQL은 CREATE INDEX에 IF NOT EXISTS를 지원하지 않는데, 테스트에서 같은 임베디드 H2
-- 인스턴스에 대해 이 schema.sql이 Spring 컨텍스트마다 다시 실행될 수 있어(mode=always) 별도 문으로
-- 두면 "인덱스가 이미 존재함" 오류로 두 번째 컨텍스트 기동이 실패한다. 인라인으로 두면 테이블
-- 전체가 CREATE TABLE IF NOT EXISTS 하나로 함께 보호된다.
CREATE TABLE IF NOT EXISTS vehicle_status_history (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id       VARCHAR(50) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    battery          INT NULL,
    position_x       DOUBLE NULL,
    position_y       DOUBLE NULL,
    heading          DOUBLE NULL,
    speed            DOUBLE NULL,
    fork_height      DOUBLE NULL,
    has_cargo        BOOLEAN NULL,
    cargo_id         VARCHAR(50) NULL,
    footprint_length DOUBLE NULL,
    footprint_width  DOUBLE NULL,
    message_at       DATETIME NULL,
    received_at      DATETIME NOT NULL,
    created_at       DATETIME NOT NULL,
    CONSTRAINT fk_vehicle_status_history_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id),
    INDEX idx_vehicle_status_history_vehicle_message (vehicle_id, message_at DESC)
);

-- AI 화물·파렛트 인식 결과(prompt26.md). analysis_id는 AI가 채번한 고유 식별자로 중복 저장을 막는다
-- (UNIQUE). vehicle_id/cargo_id는 이 저장소에 아직 cargo/pallet/task 도메인 테이블이 없어(전부 새로
-- 확인한 결과 존재하지 않음) FK로 강제하지 않고 느슨한 문자열로만 보관한다 — vehicle_id도 동일하게
-- FK를 걸지 않았다(answer26.md 3장에 이 결정을 문서화).
--
-- load_direction은 loadBalance.direction 배열(예: ["left","front"])을 쉼표로 이어붙인 문자열로
-- 저장한다(answer26.md 9.3장 근거: JSON 컬럼 타입은 이 프로젝트에서 한 번도 쓰인 적이 없어 H2/MySQL
-- 호환성 문제를 새로 만들지 않기 위해 피했고, 별도 자식 테이블을 두기엔 원소가 최대 2개뿐이라 과함).
CREATE TABLE IF NOT EXISTS ai_cargo_analysis (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    analysis_id        VARCHAR(100) NOT NULL,
    schema_version     VARCHAR(20) NOT NULL,
    vehicle_id         VARCHAR(50) NULL,
    cargo_id           VARCHAR(50) NULL,
    status             VARCHAR(30) NOT NULL,
    distance_cm        DOUBLE NULL,
    distance_std_cm    DOUBLE NULL,
    width_cm           DOUBLE NULL,
    height_cm          DOUBLE NULL,
    depth_cm           DOUBLE NULL,
    volume_cm3         DOUBLE NULL,
    dimension_scale    VARCHAR(20) NULL,
    load_direction     VARCHAR(50) NULL,
    load_message       VARCHAR(500) NULL,
    ratio_horizontal   DOUBLE NULL,
    ratio_vertical     DOUBLE NULL,
    message            VARCHAR(500) NULL,
    captured_at        DATETIME NULL,
    processed_at       DATETIME NOT NULL,
    received_at        DATETIME NOT NULL,
    created_at         DATETIME NOT NULL,
    CONSTRAINT uk_ai_cargo_analysis_analysis_id UNIQUE (analysis_id),
    INDEX idx_ai_cargo_analysis_cargo_processed (cargo_id, processed_at DESC)
);

-- 다중 박스 감지 결과. 분석 결과 1건에 박스 0~N개가 붙는다(9.4장: 같은 트랜잭션에서 처리, 박스 insert
-- 실패 시 분석 결과 insert도 함께 롤백).
CREATE TABLE IF NOT EXISTS ai_cargo_detection_box (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    analysis_id    BIGINT NOT NULL,
    class_name     VARCHAR(50) NOT NULL,
    confidence     DOUBLE NULL,
    bbox_x         INT NOT NULL,
    bbox_y         INT NOT NULL,
    bbox_width     INT NOT NULL,
    bbox_height    INT NOT NULL,
    created_at     DATETIME NOT NULL,
    CONSTRAINT fk_detection_box_analysis
        FOREIGN KEY (analysis_id) REFERENCES ai_cargo_analysis (id)
);

-- 측정 스테이션 측정 결과 v1.0 (prompt16.md, MR !36, FR-101-5). 스테이션 PC가 추론·판정을 마친 결과를
-- fast/station/{station_id}/measurement 토픽으로 발행하고 EC2 백엔드는 결과만 저장한다. 기존
-- ai_cargo_analysis(cargo/detected)와 규격(snake_case 키, OffsetDateTime, pallet 별도 의미, miniature/
-- eccentric/magnitude/threshold 등)이 달라 무리하게 확장하지 않고 별도 도메인 테이블로 분리했다.
--
-- measured_at 오프셋 보존: MySQL/H2 공용 DATETIME은 타임존을 담지 못하므로, UTC 변환 시각
-- (measured_at_utc)과 오프셋 분(measured_at_offset_minutes, 예: +09:00 → 540)을 분리 저장해 응답 시
-- 원래 OffsetDateTime을 손실 없이 복원한다. depth_cm은 정책상 항상 null이지만(정면 단일 카메라) 컬럼은
-- 유지한다. pallet은 detection box와 의미가 달라(적재 파렛트) 부모의 단일 컬럼 세트로 보존한다.
CREATE TABLE IF NOT EXISTS station_measurement (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    measurement_id             VARCHAR(100) NOT NULL,
    station_id                 VARCHAR(50)  NOT NULL,
    schema_version             VARCHAR(20)  NOT NULL,
    measured_at_utc            DATETIME     NOT NULL,
    measured_at_offset_minutes INT          NOT NULL,
    status                     VARCHAR(20)  NOT NULL,
    box_count                  INT          NULL,
    pallet_bbox_x              INT          NULL,
    pallet_bbox_y              INT          NULL,
    pallet_bbox_width          INT          NULL,
    pallet_bbox_height         INT          NULL,
    pallet_score               DOUBLE       NULL,
    front_cm                   DOUBLE       NULL,
    distance_std_cm            DOUBLE       NULL,
    frames_used                INT          NULL,
    height_cm                  DOUBLE       NULL,
    width_cm                   DOUBLE       NULL,
    depth_cm                   DOUBLE       NULL,
    miniature_scale            INT          NULL,
    miniature_height_mm        DOUBLE       NULL,
    miniature_width_mm         DOUBLE       NULL,
    eccentric                  BOOLEAN      NULL,
    load_direction             VARCHAR(50)  NULL,
    ratio_x                    DOUBLE       NULL,
    ratio_y                    DOUBLE       NULL,
    magnitude                  DOUBLE       NULL,
    threshold                  DOUBLE       NULL,
    load_message               VARCHAR(500) NULL,
    received_at                DATETIME     NOT NULL,
    created_at                 DATETIME     NOT NULL,
    CONSTRAINT uk_station_measurement_measurement_id UNIQUE (measurement_id),
    INDEX idx_station_measurement_station_measured (station_id, measured_at_utc DESC)
);

-- 다중 detection box(1:N). box_order로 payload 배열 순서를 보존한다. bbox 좌표는 nullable(관제 오버레이
-- 미사용 시 bbox_px 생략 가능, 정책 7번). pallet은 부모 테이블에 별도 보관하므로 이 테이블에는 넣지 않는다.
CREATE TABLE IF NOT EXISTS station_measurement_box (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_measurement_id BIGINT NOT NULL,
    box_order              INT    NOT NULL,
    bbox_x                 INT    NULL,
    bbox_y                 INT    NULL,
    bbox_width             INT    NULL,
    bbox_height            INT    NULL,
    score                  DOUBLE NULL,
    created_at             DATETIME NOT NULL,
    CONSTRAINT fk_station_measurement_box_measurement
        FOREIGN KEY (station_measurement_id) REFERENCES station_measurement (id)
);

-- 차량 제어 명령 통합 테이블(prompt29.md 17장 → prompt32.md 1장 7~11번, 3장 3번으로 확장).
-- command_id는 REST 응답·MQTT 결과 연결의 유일한 키라 UNIQUE로 이중 방어한다(애플리케이션 사전 확인 +
-- DB 제약). stopped_actions는 loadBalance.direction(prompt26.md)과 동일하게 쉼표 구분 문자열로 저장한다
-- (JSON 컬럼 미사용 원칙 유지, 원소가 최대 3개뿐이라 자식 테이블도 과함).
--
-- prompt32.md 확정으로 추가된 컬럼:
--   target_system    ROS2 / EMBEDDED / ALL
--   command_category MOVE / FORK / LOAD / SAFETY
--   payload_json     명령별로 구조가 다른 payload를 JSON "문자열"로 보관. MySQL JSON 컬럼 타입을 쓰지
--                    않은 이유는 이 프로젝트가 한 번도 JSON 컬럼을 쓴 적이 없고(load_direction/
--                    stopped_actions 모두 문자열), 테스트가 도는 H2와의 호환 문제를 새로 만들지 않기
--                    위해서다(prompt32.md 3장 4번 "H2 테스트 호환성을 고려해 안전한 방식을 선택").
--
-- 테이블/컬럼 이름은 embedded_vehicle_command · forklift_id를 그대로 유지한다(prompt32.md 3장 3번이
-- 허용한 "기존 forklift_id 유지 정책"). 이름을 바꾸면 운영 DB 마이그레이션과 기존 데이터 이관 위험이
-- 커지는 데 비해 얻는 게 이름 일관성뿐이기 때문이다. 애플리케이션 계층(Domain/DTO/JSON)에서는 확정
-- 규격대로 vehicleId를 쓰고, Mapper XML이 forklift_id 컬럼과 매핑한다.
CREATE TABLE IF NOT EXISTS embedded_vehicle_command (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_id               VARCHAR(100) NOT NULL,
    forklift_id              VARCHAR(50) NOT NULL,
    command                  VARCHAR(30) NOT NULL,
    target_system            VARCHAR(20) NOT NULL,
    command_category         VARCHAR(20) NOT NULL,
    payload_json             VARCHAR(1000) NULL,
    reason                   VARCHAR(100) NULL,
    status                   VARCHAR(20) NOT NULL,
    issued_at                DATETIME NOT NULL,
    published_at             DATETIME NULL,
    completed_at             DATETIME NULL,
    error_code               VARCHAR(50) NULL,
    result_message           VARCHAR(500) NULL,
    stopped_actions          VARCHAR(100) NULL,
    emergency_stop_applied   BOOLEAN NULL,
    requires_reset           BOOLEAN NULL,
    created_at               DATETIME NOT NULL,
    updated_at               DATETIME NOT NULL,
    CONSTRAINT uk_embedded_vehicle_command_command_id UNIQUE (command_id),
    INDEX idx_embedded_vehicle_command_forklift_issued (forklift_id, issued_at DESC)
);

-- 실물 포크 현재 상태 1행/차량(prompt29.md 17장). GET /api/vehicles/{forkliftId}/fork-status 조회를
-- WebSocket 없이도 서빙하기 위해 최소 현재값만 유지한다. 포크 높이·limitTop 컬럼은 절대 추가하지 않는다
-- (작업 원칙 12·13번).
CREATE TABLE IF NOT EXISTS vehicle_fork_current_status (
    forklift_id   VARCHAR(50) NOT NULL PRIMARY KEY,
    fork_state    VARCHAR(20) NOT NULL,
    limit_bottom  BOOLEAN NOT NULL,
    error_code    VARCHAR(50) NULL,
    message_at    DATETIME NOT NULL,
    received_at   DATETIME NOT NULL,
    updated_at    DATETIME NOT NULL
);

-- 적재 화물 안전 상태 1행/차량(prompt63.md 3장). vehicle_fork_current_status와 같은 "차량당 최신 1행"
-- upsert 구조다 — 관제 화면이 필요로 하는 것은 "지금 이 차량의 적재가 안전한가"이며, prompt63.md는
-- 이력 보관을 요구하지 않았다(필요해지면 vehicle_status_history 패턴으로 별도 이력 테이블을 만든다).
--
-- 측정값 컬럼이 전부 NULL 허용인 이유: 비전이 화물을 찾지 못했거나 IMU가 없는 차량도 "위험 아님"을
-- 보고할 수 있어야 한다. 값이 없다는 사실을 0으로 위조하지 않는다.
-- roll/pitch는 단위를 컬럼명에 드러내고 예약어 충돌도 피하려고 roll_deg/pitch_deg로 둔다.
-- risk_level은 백엔드가 계산하지 않는다 — 비전·센서가 판정해 보낸 값을 그대로 저장한다.
CREATE TABLE IF NOT EXISTS vehicle_load_safety (
    vehicle_id     VARCHAR(50)  NOT NULL PRIMARY KEY,
    cargo_id       VARCHAR(50)  NULL,
    fork_height    DOUBLE       NULL,
    cargo_height   DOUBLE       NULL,
    roll_deg       DOUBLE       NULL,
    pitch_deg      DOUBLE       NULL,
    load_offset_x  DOUBLE       NULL,
    load_offset_y  DOUBLE       NULL,
    risk_level     VARCHAR(20)  NOT NULL,
    risk_code      VARCHAR(50)  NULL,
    message        VARCHAR(255) NULL,
    source         VARCHAR(20)  NOT NULL,
    detected_at    DATETIME     NOT NULL,
    received_at    DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,
    INDEX idx_vehicle_load_safety_risk (risk_level)
);

-- 실물 임베디드 오류 이력(prompt29.md 17장). 상태처럼 누적 기록이 필요해 이력 테이블로 분리했다
-- (vehicle_status_history와 동일한 설계 패턴).
CREATE TABLE IF NOT EXISTS embedded_error_history (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    forklift_id    VARCHAR(50) NOT NULL,
    error_code     VARCHAR(50) NOT NULL,
    error_source   VARCHAR(20) NOT NULL,
    severity       VARCHAR(20) NOT NULL,
    message        VARCHAR(500) NULL,
    occurred_at    DATETIME NOT NULL,
    received_at    DATETIME NOT NULL,
    created_at     DATETIME NOT NULL,
    INDEX idx_embedded_error_history_forklift_occurred (forklift_id, occurred_at DESC)
);

-- =====================================================================================
-- 화물 크기 기반 적재 위치 추천 및 운반 작업(prompt46.md 핵심 로직 → prompt47.md 영속 슬라이스).
-- 좌표·크기 단위는 m, heading은 degree로 기존 차량 위치 규격(prompt32.md 1장 5번)과 동일하다.
-- 기존 방식과 동일하게 ENGINE/CHARSET 절은 넣지 않고(H2 MySQL 호환 모드에서 그대로 실행 가능),
-- 인덱스는 CREATE TABLE 인라인으로 둔다(mode=always 재실행 시 "이미 존재" 오류 방지).
-- =====================================================================================

CREATE TABLE IF NOT EXISTS cargo (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    cargo_id   VARCHAR(50)  NOT NULL,
    width      DOUBLE       NOT NULL,
    length     DOUBLE       NOT NULL,
    height     DOUBLE       NOT NULL,
    volume     DOUBLE       NOT NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    CONSTRAINT uk_cargo_cargo_id UNIQUE (cargo_id)
);

CREATE TABLE IF NOT EXISTS pallet (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    pallet_id      VARCHAR(50) NOT NULL,
    cargo_id       VARCHAR(50) NOT NULL,
    pickup_x       DOUBLE      NULL,
    pickup_y       DOUBLE      NULL,
    pickup_heading DOUBLE      NULL,
    status         VARCHAR(20) NOT NULL,
    created_at     DATETIME    NOT NULL,
    updated_at     DATETIME    NOT NULL,
    CONSTRAINT uk_pallet_pallet_id UNIQUE (pallet_id),
    CONSTRAINT fk_pallet_cargo FOREIGN KEY (cargo_id) REFERENCES cargo (cargo_id)
);

CREATE TABLE IF NOT EXISTS rack (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    rack_code  VARCHAR(50)  NOT NULL,
    rack_name  VARCHAR(100) NULL,
    position_x DOUBLE       NULL,
    position_y DOUBLE       NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    CONSTRAINT uk_rack_rack_code UNIQUE (rack_code)
);

CREATE TABLE IF NOT EXISTS rack_level (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    rack_id      BIGINT   NOT NULL,
    level_number INT      NOT NULL,
    clear_width  DOUBLE   NOT NULL,
    clear_length DOUBLE   NOT NULL,
    clear_height DOUBLE   NOT NULL,
    fork_height  DOUBLE   NULL,
    created_at   DATETIME NOT NULL,
    updated_at   DATETIME NOT NULL,
    CONSTRAINT uk_rack_level_rack_level UNIQUE (rack_id, level_number),
    CONSTRAINT fk_rack_level_rack FOREIGN KEY (rack_id) REFERENCES rack (id)
);

CREATE TABLE IF NOT EXISTS storage_slot (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    slot_code           VARCHAR(50) NOT NULL,
    rack_level_id       BIGINT      NOT NULL,
    width               DOUBLE      NOT NULL,
    length              DOUBLE      NOT NULL,
    height              DOUBLE      NOT NULL,
    destination_x       DOUBLE      NULL,
    destination_y       DOUBLE      NULL,
    destination_heading DOUBLE      NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'EMPTY',
    reserved_task_id    VARCHAR(50) NULL,
    stored_cargo_id     VARCHAR(50) NULL,
    created_at          DATETIME    NOT NULL,
    updated_at          DATETIME    NOT NULL,
    CONSTRAINT uk_storage_slot_slot_code UNIQUE (slot_code),
    CONSTRAINT fk_storage_slot_rack_level FOREIGN KEY (rack_level_id) REFERENCES rack_level (id),
    INDEX idx_storage_slot_status (status),
    INDEX idx_storage_slot_rack_level (rack_level_id),
    INDEX idx_storage_slot_reserved_task (reserved_task_id)
);

CREATE TABLE IF NOT EXISTS transport_task (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_code           VARCHAR(50) NOT NULL,
    cargo_id            VARCHAR(50) NOT NULL,
    pallet_id           VARCHAR(50) NOT NULL,
    vehicle_id          VARCHAR(50) NULL,
    source_x            DOUBLE      NULL,
    source_y            DOUBLE      NULL,
    source_heading      DOUBLE      NULL,
    destination_slot_id BIGINT      NULL,
    destination_x       DOUBLE      NULL,
    destination_y       DOUBLE      NULL,
    destination_heading DOUBLE      NULL,
    fork_height         DOUBLE      NULL,
    cargo_orientation   VARCHAR(20) NULL,
    status              VARCHAR(20) NOT NULL,
    assigned_at         DATETIME    NULL,
    started_at          DATETIME    NULL,
    picked_up_at        DATETIME    NULL,
    completed_at        DATETIME    NULL,
    failed_at           DATETIME    NULL,
    created_at          DATETIME    NOT NULL,
    updated_at          DATETIME    NOT NULL,
    CONSTRAINT uk_transport_task_task_code UNIQUE (task_code),
    CONSTRAINT fk_transport_task_cargo FOREIGN KEY (cargo_id) REFERENCES cargo (cargo_id),
    CONSTRAINT fk_transport_task_pallet FOREIGN KEY (pallet_id) REFERENCES pallet (pallet_id),
    CONSTRAINT fk_transport_task_slot FOREIGN KEY (destination_slot_id) REFERENCES storage_slot (id),
    INDEX idx_transport_task_status (status),
    INDEX idx_transport_task_vehicle (vehicle_id),
    INDEX idx_transport_task_pallet (pallet_id),
    INDEX idx_transport_task_slot (destination_slot_id),
    INDEX idx_transport_task_created (created_at DESC)
);

-- 차량에 실제 발행한 MQTT 운반 명령 단위(prompt48.md 3·5장). TransportTask(업무 단위) 1 : N TransportCommand.
-- 하나의 Task에 재시도로 여러 command가 붙을 수 있어 Task와 분리한다. command_id로 command-result를 역추적한다.
-- task_id(BIGINT)와 task_code(VARCHAR)를 함께 보관해 조회 편의를 준다. stage는 ROS2가 단계 결과를 줄 때만
-- 채워지며(현재 규격 미확정) nullable로 두고 강제하지 않는다.
CREATE TABLE IF NOT EXISTS transport_command (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_id      VARCHAR(50)   NOT NULL,
    task_id         BIGINT        NOT NULL,
    task_code       VARCHAR(50)   NOT NULL,
    vehicle_id      VARCHAR(50)   NOT NULL,
    command_type    VARCHAR(30)   NOT NULL,
    stage           VARCHAR(30)   NULL,
    status          VARCHAR(20)   NOT NULL,
    payload         VARCHAR(2000) NULL,
    failure_reason  VARCHAR(500)  NULL,
    published_at    DATETIME      NULL,
    acknowledged_at DATETIME      NULL,
    completed_at    DATETIME      NULL,
    created_at      DATETIME      NOT NULL,
    updated_at      DATETIME      NOT NULL,
    CONSTRAINT uk_transport_command_command_id UNIQUE (command_id),
    CONSTRAINT fk_transport_command_task FOREIGN KEY (task_id) REFERENCES transport_task (id),
    INDEX idx_transport_command_task (task_id),
    INDEX idx_transport_command_vehicle (vehicle_id),
    INDEX idx_transport_command_status (status),
    INDEX idx_transport_command_created (created_at DESC)
);