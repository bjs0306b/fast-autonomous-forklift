-- =====================================================================================
-- 롤백: 최종 FR-202 스키마 → 기존 스키마(schema.sql) — prompt85 8항
-- 작성: 2026-07-30
--
-- ★ 전제: 2026-07-30-migrate-to-fr202.sql 의 PHASE 0 백업 테이블(backup_*_20260730)이
--   **그대로 남아 있어야** 한다. PHASE 8 에서 백업을 지웠다면 이 파일로는 복구할 수 없다.
--
-- ★ 복구되는 것과 안 되는 것을 먼저 분명히 한다(prompt85 8항 "가능한 범위에서").
--
--   복구된다
--     - 삭제된 테이블 9개의 구조와 데이터 (백업에서 되돌림)
--     - 삭제된 컬럼의 구조와, 마이그레이션 시점까지의 데이터 (백업에서 되돌림)
--     - PK/FK/인덱스 구성
--
--   복구되지 않는다
--     - **마이그레이션 이후 새로 쌓인 행의 "사라졌던 컬럼" 값.**
--       예: 마이그레이션 후 등록된 차량에는 source/vehicle_type 값이 애초에 없었다 → NULL 로 남는다.
--       vehicle.source 는 NOT NULL 이므로 아래에서 임시로 'UNKNOWN' 을 채운다(실제 값이 아니다).
--     - **PHASE 5-2 에서 재생성한 station_measurement 에 새로 쌓인 세션 기반 측정 결과.**
--       옛 구조에 대응 컬럼이 없어 옮길 수 없다. 아래에서 backup_station_measurement_new_* 로
--       따로 떠 두고, 옛 구조는 백업본으로 되돌린다.
--     - station_session / station_state 데이터(옛 스키마에 대응 테이블이 없다. 백업만 뜬다).
--     - AUTO_INCREMENT 로 재발급되는 대리키(id)는 원래 값과 달라질 수 있다 →
--       그래서 아래는 id 컬럼도 백업에서 그대로 INSERT 한다.
-- =====================================================================================


-- =====================================================================================
-- R0. 롤백 직전 상태 백업 (되돌리기의 되돌리기를 위해)
-- =====================================================================================

CREATE TABLE backup_rollback_vehicle_20260730                AS SELECT * FROM vehicle;
CREATE TABLE backup_rollback_vehicle_current_status_20260730 AS SELECT * FROM vehicle_current_status;
CREATE TABLE backup_rollback_cargo_20260730                  AS SELECT * FROM cargo;
CREATE TABLE backup_rollback_storage_slot_20260730           AS SELECT * FROM storage_slot;
CREATE TABLE backup_rollback_transport_task_20260730         AS SELECT * FROM transport_task;
CREATE TABLE backup_rollback_transport_command_20260730      AS SELECT * FROM transport_command;
CREATE TABLE backup_rollback_evc_20260730                    AS SELECT * FROM embedded_vehicle_command;
CREATE TABLE backup_rollback_error_history_20260730          AS SELECT * FROM embedded_error_history;
-- 새 구조에서만 존재하던 데이터(옛 구조로 옮길 수 없다 — 보관만 한다)
CREATE TABLE backup_rollback_station_measurement_new_20260730 AS SELECT * FROM station_measurement;
CREATE TABLE backup_rollback_station_session_20260730         AS SELECT * FROM station_session;
CREATE TABLE backup_rollback_station_state_20260730           AS SELECT * FROM station_state;


-- =====================================================================================
-- R1. 새 FK 제거 (구조를 되돌리기 전에 참조부터 끊는다)
-- =====================================================================================

ALTER TABLE storage_slot              DROP FOREIGN KEY fk_storage_slot_reserved_task;
ALTER TABLE storage_slot              DROP FOREIGN KEY fk_storage_slot_stored_cargo;
ALTER TABLE transport_task            DROP FOREIGN KEY fk_transport_task_cargo;
ALTER TABLE transport_task            DROP FOREIGN KEY fk_transport_task_vehicle;
ALTER TABLE transport_command         DROP FOREIGN KEY fk_transport_command_task;
ALTER TABLE transport_command         DROP FOREIGN KEY fk_transport_command_vehicle;
ALTER TABLE embedded_vehicle_command  DROP FOREIGN KEY fk_embedded_vehicle_command_vehicle;
ALTER TABLE embedded_error_history    DROP FOREIGN KEY fk_embedded_error_history_vehicle;
ALTER TABLE vehicle_current_status    DROP FOREIGN KEY fk_vehicle_current_status_vehicle;
ALTER TABLE station_measurement       DROP FOREIGN KEY fk_station_measurement_session;
ALTER TABLE station_state             DROP FOREIGN KEY fk_station_state_active_session;

-- PHASE 7-A 를 실행했다면 아래 두 개도 존재한다(없으면 오류 → 무시).
-- ALTER TABLE transport_task DROP FOREIGN KEY fk_transport_task_measurement;
-- ALTER TABLE transport_task DROP FOREIGN KEY fk_transport_task_slot;

-- CHECK 제약 제거(옛 스키마에는 CHECK 가 하나도 없었다)
ALTER TABLE embedded_vehicle_command DROP CHECK chk_embedded_vehicle_command_target_system;
ALTER TABLE embedded_vehicle_command DROP CHECK chk_embedded_vehicle_command_status;
ALTER TABLE embedded_error_history   DROP CHECK chk_embedded_error_history_source;
ALTER TABLE embedded_error_history   DROP CHECK chk_embedded_error_history_severity;
ALTER TABLE storage_slot             DROP CHECK chk_storage_slot_geometry;
ALTER TABLE storage_slot             DROP CHECK chk_storage_slot_status;
ALTER TABLE storage_slot             DROP CHECK chk_storage_slot_state_shape;
ALTER TABLE transport_task           DROP CHECK chk_transport_task_status;
ALTER TABLE transport_command        DROP CHECK chk_transport_command_status;
ALTER TABLE station_state            DROP CHECK chk_station_state_singleton;
ALTER TABLE station_measurement      DROP CHECK chk_station_measurement_status;
ALTER TABLE station_measurement      DROP CHECK chk_station_measurement_height;
ALTER TABLE station_measurement      DROP CHECK chk_station_measurement_tipping_level;
ALTER TABLE station_measurement      DROP CHECK chk_station_measurement_overhang;


-- =====================================================================================
-- R2. 최종 스키마에만 있던 테이블 제거
-- =====================================================================================

DROP TABLE IF EXISTS station_state;
DROP TABLE IF EXISTS station_session;
DROP TABLE IF EXISTS station_measurement;   -- 새 구조. 옛 구조는 R4 에서 백업으로 재생성한다.


-- =====================================================================================
-- R3. 옛 스키마에서 삭제됐던 테이블 9개 재생성 + 데이터 복구
--   구조는 schema.sql 원본과 동일하다.
-- =====================================================================================

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
    INDEX idx_vehicle_status_history_vehicle_message (vehicle_id, message_at DESC)
);
INSERT INTO vehicle_status_history SELECT * FROM backup_vehicle_status_history_20260730;

CREATE TABLE IF NOT EXISTS vehicle_fork_current_status (
    forklift_id   VARCHAR(50) NOT NULL PRIMARY KEY,
    fork_state    VARCHAR(20) NOT NULL,
    limit_bottom  BOOLEAN NOT NULL,
    error_code    VARCHAR(50) NULL,
    message_at    DATETIME NOT NULL,
    received_at   DATETIME NOT NULL,
    updated_at    DATETIME NOT NULL
);
INSERT INTO vehicle_fork_current_status SELECT * FROM backup_vehicle_fork_current_status_20260730;

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
INSERT INTO vehicle_load_safety SELECT * FROM backup_vehicle_load_safety_20260730;

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
INSERT INTO ai_cargo_analysis SELECT * FROM backup_ai_cargo_analysis_20260730;

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
INSERT INTO ai_cargo_detection_box SELECT * FROM backup_ai_cargo_detection_box_20260730;

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
INSERT INTO rack SELECT * FROM backup_rack_20260730;

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
INSERT INTO rack_level SELECT * FROM backup_rack_level_20260730;

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
    CONSTRAINT uk_pallet_pallet_id UNIQUE (pallet_id)
);
INSERT INTO pallet SELECT * FROM backup_pallet_20260730;

-- station_measurement 는 옛 구조로 재생성한 뒤 백업 데이터를 되돌린다.
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
INSERT INTO station_measurement SELECT * FROM backup_station_measurement_20260730;

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
INSERT INTO station_measurement_box SELECT * FROM backup_station_measurement_box_20260730;


-- =====================================================================================
-- R4. 남아 있는 테이블을 옛 구조로 되돌린다
--   컬럼 구조를 먼저 복원하고, 마이그레이션 시점 데이터는 백업에서 덮어쓴다.
-- =====================================================================================

-- 4-1. vehicle
ALTER TABLE vehicle ADD COLUMN source       VARCHAR(20) NULL AFTER name;
ALTER TABLE vehicle ADD COLUMN vehicle_type VARCHAR(30) NULL AFTER source;
UPDATE vehicle v JOIN backup_vehicle_20260730 b ON b.vehicle_id = v.vehicle_id
   SET v.source = b.source, v.vehicle_type = b.vehicle_type;
-- 마이그레이션 이후 새로 생긴 차량은 원래 값이 없다. NOT NULL 승격을 위해 임시값을 넣는다
-- (실제 값이 아니라는 점을 반드시 기록할 것).
UPDATE vehicle SET source = 'UNKNOWN' WHERE source IS NULL;
ALTER TABLE vehicle MODIFY COLUMN source VARCHAR(20) NOT NULL;

ALTER TABLE vehicle DROP PRIMARY KEY;
ALTER TABLE vehicle ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;
ALTER TABLE vehicle ADD CONSTRAINT uk_vehicle_vehicle_id UNIQUE (vehicle_id);
ALTER TABLE vehicle
    MODIFY COLUMN created_at DATETIME NOT NULL,
    MODIFY COLUMN updated_at DATETIME NOT NULL;
-- 원래 id 값 복원(대리키를 참조하는 곳은 없지만 값 자체를 되살린다)
UPDATE vehicle v JOIN backup_vehicle_20260730 b ON b.vehicle_id = v.vehicle_id SET v.id = b.id;

-- 4-2. vehicle_current_status
ALTER TABLE vehicle_current_status
    ADD COLUMN message_at DATETIME NULL AFTER footprint_width,
    ADD COLUMN updated_at DATETIME NULL AFTER received_at;
UPDATE vehicle_current_status s JOIN backup_vehicle_current_status_20260730 b
        ON b.vehicle_id = s.vehicle_id
   SET s.message_at = b.message_at, s.updated_at = b.updated_at;
-- 마이그레이션 이후 새로 생긴 행은 updated_at 이 없다 → received_at 으로 채운다(근사값).
UPDATE vehicle_current_status SET updated_at = received_at WHERE updated_at IS NULL;
ALTER TABLE vehicle_current_status
    MODIFY COLUMN updated_at  DATETIME NOT NULL,
    MODIFY COLUMN received_at DATETIME NOT NULL;
ALTER TABLE vehicle_current_status DROP COLUMN fork_state;
ALTER TABLE vehicle_current_status DROP COLUMN fork_error_code;
ALTER TABLE vehicle_current_status
    ADD CONSTRAINT fk_vehicle_current_status_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id);

-- 4-3. cargo
ALTER TABLE cargo
    ADD COLUMN width      DOUBLE NULL AFTER cargo_id,
    ADD COLUMN length     DOUBLE NULL AFTER width,
    ADD COLUMN height     DOUBLE NULL AFTER length,
    ADD COLUMN volume     DOUBLE NULL AFTER height,
    ADD COLUMN updated_at DATETIME NULL AFTER created_at;
UPDATE cargo c JOIN backup_cargo_20260730 b ON b.cargo_id = c.cargo_id
   SET c.width = b.width, c.length = b.length, c.height = b.height,
       c.volume = b.volume, c.updated_at = b.updated_at;
-- 새로 생긴 화물은 치수를 알 수 없다. NOT NULL 승격 전에 사람이 확인할 것:
--   SELECT cargo_id FROM cargo WHERE width IS NULL OR length IS NULL OR height IS NULL OR volume IS NULL;
-- 확인 후에만 아래를 실행한다(값을 0 으로 채우면 "치수 0" 이라는 거짓 데이터가 된다).
-- ALTER TABLE cargo
--     MODIFY COLUMN width DOUBLE NOT NULL, MODIFY COLUMN length DOUBLE NOT NULL,
--     MODIFY COLUMN height DOUBLE NOT NULL, MODIFY COLUMN volume DOUBLE NOT NULL,
--     MODIFY COLUMN updated_at DATETIME NOT NULL;
ALTER TABLE cargo DROP PRIMARY KEY;
ALTER TABLE cargo ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;
ALTER TABLE cargo ADD CONSTRAINT uk_cargo_cargo_id UNIQUE (cargo_id);
ALTER TABLE cargo MODIFY COLUMN created_at DATETIME NOT NULL;

-- 4-4. storage_slot
ALTER TABLE storage_slot
    ADD COLUMN width      DOUBLE NULL AFTER slot_code,
    ADD COLUMN length     DOUBLE NULL AFTER width,
    ADD COLUMN height     DOUBLE NULL AFTER length,
    ADD COLUMN rack_level_id BIGINT NULL AFTER slot_code,
    ADD COLUMN created_at DATETIME NULL,
    ADD COLUMN updated_at DATETIME NULL;
UPDATE storage_slot s JOIN backup_storage_slot_20260730 b ON b.slot_code = s.slot_code
   SET s.width = b.width, s.length = b.length, s.height = b.height,
       s.rack_level_id = b.rack_level_id, s.created_at = b.created_at, s.updated_at = b.updated_at;

-- reserved_task_id: BIGINT(task id) → VARCHAR(50)(task_code)
ALTER TABLE storage_slot ADD COLUMN reserved_task_code VARCHAR(50) NULL;
UPDATE storage_slot s JOIN transport_task t ON t.id = s.reserved_task_id
   SET s.reserved_task_code = t.task_code;
ALTER TABLE storage_slot DROP COLUMN reserved_task_id;
ALTER TABLE storage_slot CHANGE COLUMN reserved_task_code reserved_task_id VARCHAR(50) NULL;

ALTER TABLE storage_slot DROP PRIMARY KEY;
ALTER TABLE storage_slot ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;
ALTER TABLE storage_slot DROP INDEX uk_storage_slot_stored_cargo;
ALTER TABLE storage_slot DROP COLUMN usable_height;
ALTER TABLE storage_slot DROP COLUMN fork_height;
ALTER TABLE storage_slot
    MODIFY COLUMN destination_x       DOUBLE NULL,
    MODIFY COLUMN destination_y       DOUBLE NULL,
    MODIFY COLUMN destination_heading DOUBLE NULL,
    MODIFY COLUMN status              VARCHAR(20) NOT NULL DEFAULT 'EMPTY';
ALTER TABLE storage_slot
    ADD CONSTRAINT uk_storage_slot_slot_code UNIQUE (slot_code),
    ADD INDEX idx_storage_slot_rack_level (rack_level_id),
    ADD INDEX idx_storage_slot_reserved_task (reserved_task_id);
-- rack_level 이 R3 에서 복구된 뒤에만 붙는다.
ALTER TABLE storage_slot
    ADD CONSTRAINT fk_storage_slot_rack_level FOREIGN KEY (rack_level_id) REFERENCES rack_level (id);

-- 4-5. transport_task
ALTER TABLE transport_task
    ADD COLUMN pallet_id           VARCHAR(50) NULL AFTER cargo_id,
    ADD COLUMN cargo_orientation   VARCHAR(20) NULL,
    ADD COLUMN updated_at          DATETIME NULL,
    ADD COLUMN destination_slot_id BIGINT NULL;
UPDATE transport_task t JOIN backup_transport_task_20260730 b ON b.id = t.id
   SET t.pallet_id = b.pallet_id, t.cargo_orientation = b.cargo_orientation,
       t.updated_at = b.updated_at, t.destination_slot_id = b.destination_slot_id;
-- 마이그레이션 이후 생긴 작업은 slot_code 만 있다 → 복구된 storage_slot.id 로 되돌린다.
UPDATE transport_task t JOIN storage_slot s ON s.slot_code = t.destination_slot_code
   SET t.destination_slot_id = s.id
 WHERE t.destination_slot_id IS NULL;
UPDATE transport_task SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE transport_task DROP COLUMN destination_slot_code;
ALTER TABLE transport_task DROP COLUMN measurement_id;
ALTER TABLE transport_task DROP INDEX idx_transport_task_cargo_status;
ALTER TABLE transport_task DROP INDEX idx_transport_task_measurement;
ALTER TABLE transport_task
    MODIFY COLUMN created_at DATETIME NOT NULL,
    MODIFY COLUMN updated_at DATETIME NOT NULL,
    MODIFY COLUMN assigned_at DATETIME NULL, MODIFY COLUMN started_at DATETIME NULL,
    MODIFY COLUMN picked_up_at DATETIME NULL, MODIFY COLUMN completed_at DATETIME NULL,
    MODIFY COLUMN failed_at DATETIME NULL,
    MODIFY COLUMN status VARCHAR(20) NOT NULL,
    MODIFY COLUMN source_x DOUBLE NULL, MODIFY COLUMN source_y DOUBLE NULL,
    MODIFY COLUMN source_heading DOUBLE NULL,
    MODIFY COLUMN destination_x DOUBLE NULL, MODIFY COLUMN destination_y DOUBLE NULL,
    MODIFY COLUMN destination_heading DOUBLE NULL, MODIFY COLUMN fork_height DOUBLE NULL;
-- pallet_id 는 옛 스키마에서 NOT NULL + FK 였다. 새로 생긴 작업에는 값이 없으므로
-- 확인 후에만 승격한다:  SELECT id, task_code FROM transport_task WHERE pallet_id IS NULL;
-- ALTER TABLE transport_task MODIFY COLUMN pallet_id VARCHAR(50) NOT NULL;
-- ALTER TABLE transport_task ADD CONSTRAINT fk_transport_task_pallet
--     FOREIGN KEY (pallet_id) REFERENCES pallet (pallet_id);
ALTER TABLE transport_task
    ADD CONSTRAINT fk_transport_task_cargo FOREIGN KEY (cargo_id) REFERENCES cargo (cargo_id),
    ADD CONSTRAINT fk_transport_task_slot  FOREIGN KEY (destination_slot_id) REFERENCES storage_slot (id),
    ADD INDEX idx_transport_task_pallet (pallet_id),
    ADD INDEX idx_transport_task_slot (destination_slot_id);

-- 4-6. transport_command
ALTER TABLE transport_command
    ADD COLUMN task_code       VARCHAR(50)   NULL AFTER task_id,
    ADD COLUMN command_type    VARCHAR(30)   NULL,
    ADD COLUMN stage           VARCHAR(30)   NULL,
    ADD COLUMN payload         VARCHAR(2000) NULL,
    ADD COLUMN published_at    DATETIME      NULL,
    ADD COLUMN acknowledged_at DATETIME      NULL,
    ADD COLUMN updated_at      DATETIME      NULL;
UPDATE transport_command c JOIN backup_transport_command_20260730 b ON b.command_id = c.command_id
   SET c.task_code = b.task_code, c.command_type = b.command_type, c.stage = b.stage,
       c.payload = b.payload, c.published_at = b.published_at,
       c.acknowledged_at = b.acknowledged_at, c.updated_at = b.updated_at;
UPDATE transport_command c JOIN transport_task t ON t.id = c.task_id
   SET c.task_code = t.task_code WHERE c.task_code IS NULL;
UPDATE transport_command SET updated_at = created_at WHERE updated_at IS NULL;
-- command_type 은 옛 스키마에서 NOT NULL 이었다. 새로 생긴 명령에는 값이 없다:
--   SELECT command_id FROM transport_command WHERE command_type IS NULL;
-- 확인 후 승격:  ALTER TABLE transport_command MODIFY COLUMN command_type VARCHAR(30) NOT NULL;
ALTER TABLE transport_command DROP PRIMARY KEY;
ALTER TABLE transport_command ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;
ALTER TABLE transport_command
    MODIFY COLUMN created_at DATETIME NOT NULL,
    MODIFY COLUMN updated_at DATETIME NOT NULL,
    MODIFY COLUMN completed_at DATETIME NULL,
    MODIFY COLUMN task_code VARCHAR(50) NOT NULL,
    MODIFY COLUMN status VARCHAR(20) NOT NULL;
ALTER TABLE transport_command
    ADD CONSTRAINT uk_transport_command_command_id UNIQUE (command_id),
    ADD CONSTRAINT fk_transport_command_task FOREIGN KEY (task_id) REFERENCES transport_task (id),
    ADD INDEX idx_transport_command_created (created_at DESC);

-- 4-7. embedded_vehicle_command
ALTER TABLE embedded_vehicle_command
    ADD COLUMN command_category       VARCHAR(20)   NULL AFTER target_system,
    ADD COLUMN payload_json           VARCHAR(1000) NULL AFTER command_category,
    ADD COLUMN reason                 VARCHAR(100)  NULL AFTER payload_json,
    ADD COLUMN published_at           DATETIME      NULL,
    ADD COLUMN stopped_actions        VARCHAR(100)  NULL,
    ADD COLUMN emergency_stop_applied BOOLEAN       NULL,
    ADD COLUMN requires_reset         BOOLEAN       NULL,
    ADD COLUMN created_at             DATETIME      NULL,
    ADD COLUMN updated_at             DATETIME      NULL;
UPDATE embedded_vehicle_command c JOIN backup_embedded_vehicle_command_20260730 b
        ON b.command_id = c.command_id
   SET c.command_category = b.command_category, c.payload_json = b.payload_json,
       c.reason = b.reason, c.published_at = b.published_at,
       c.stopped_actions = b.stopped_actions, c.emergency_stop_applied = b.emergency_stop_applied,
       c.requires_reset = b.requires_reset, c.created_at = b.created_at, c.updated_at = b.updated_at;
-- 새로 생긴 명령의 command_category 는 알 수 없다 → 명령어에서 역산한다(마이그레이션 backfill 과 동일 규칙).
UPDATE embedded_vehicle_command
   SET command_category = CASE
           WHEN command IN ('EMERGENCY_STOP','RESET_ESTOP','STOP') THEN 'SAFETY'
           WHEN command = 'MOVE'                                   THEN 'MOVE'
           WHEN command IN ('FORK_UP','FORK_DOWN')                 THEN 'FORK'
           WHEN command IN ('LOAD','UNLOAD')                       THEN 'LOAD'
           ELSE 'FORK' END
 WHERE command_category IS NULL;
UPDATE embedded_vehicle_command SET created_at = issued_at WHERE created_at IS NULL;
UPDATE embedded_vehicle_command SET updated_at = issued_at WHERE updated_at IS NULL;
ALTER TABLE embedded_vehicle_command
    MODIFY COLUMN command_category VARCHAR(20) NOT NULL,
    MODIFY COLUMN created_at DATETIME NOT NULL,
    MODIFY COLUMN updated_at DATETIME NOT NULL,
    MODIFY COLUMN issued_at DATETIME NOT NULL,
    MODIFY COLUMN completed_at DATETIME NULL;
ALTER TABLE embedded_vehicle_command DROP INDEX idx_embedded_vehicle_command_vehicle_issued;
ALTER TABLE embedded_vehicle_command
    CHANGE COLUMN vehicle_id forklift_id VARCHAR(50) NOT NULL;
ALTER TABLE embedded_vehicle_command DROP PRIMARY KEY;
ALTER TABLE embedded_vehicle_command ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;
ALTER TABLE embedded_vehicle_command
    MODIFY COLUMN command_id VARCHAR(100) NOT NULL,
    ADD CONSTRAINT uk_embedded_vehicle_command_command_id UNIQUE (command_id),
    ADD INDEX idx_embedded_vehicle_command_forklift_issued (forklift_id, issued_at DESC);

-- 4-8. embedded_error_history
ALTER TABLE embedded_error_history
    ADD COLUMN received_at DATETIME NULL,
    ADD COLUMN created_at  DATETIME NULL;
UPDATE embedded_error_history e JOIN backup_embedded_error_history_20260730 b ON b.id = e.id
   SET e.received_at = b.received_at, e.created_at = b.created_at;
UPDATE embedded_error_history SET received_at = occurred_at WHERE received_at IS NULL;
UPDATE embedded_error_history SET created_at  = occurred_at WHERE created_at IS NULL;
ALTER TABLE embedded_error_history
    MODIFY COLUMN received_at DATETIME NOT NULL,
    MODIFY COLUMN created_at  DATETIME NOT NULL,
    MODIFY COLUMN occurred_at DATETIME NOT NULL;
ALTER TABLE embedded_error_history DROP INDEX idx_embedded_error_history_vehicle_occurred;
ALTER TABLE embedded_error_history
    CHANGE COLUMN vehicle_id forklift_id VARCHAR(50) NOT NULL;
ALTER TABLE embedded_error_history
    ADD INDEX idx_embedded_error_history_forklift_occurred (forklift_id, occurred_at DESC);


-- =====================================================================================
-- R5. 확인
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME NOT LIKE 'backup_%' ORDER BY TABLE_NAME;
--   -- 기대: 옛 스키마의 18개 테이블
--
--   복구되지 않은 값 점검(위 주석의 NOT NULL 승격을 보류한 컬럼들):
--     SELECT COUNT(*) FROM vehicle WHERE source = 'UNKNOWN';           -- 임시값이 들어간 행
--     SELECT COUNT(*) FROM cargo WHERE width IS NULL;                  -- 치수 미복구
--     SELECT COUNT(*) FROM transport_task WHERE pallet_id IS NULL;     -- 파렛트 미복구
--     SELECT COUNT(*) FROM transport_command WHERE command_type IS NULL;
--     SELECT COUNT(*) FROM backup_rollback_station_measurement_new_20260730;  -- 버려지는 새 측정 데이터
-- =====================================================================================
