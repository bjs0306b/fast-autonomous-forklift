-- =====================================================================================
-- 마이그레이션: 기존 스키마(schema.sql) → 최종 FR-202 스키마(schema-final.sql)
-- 작성: 2026-07-30 (prompt85)
--
-- 적용 대상: 이미 schema.sql 로 테이블이 만들어진 팀 공용 MySQL(EC2/로컬).
-- **수동 실행 전제**다. 이 저장소는 애플리케이션 기동만으로 공용 DB 스키마를 바꾸지 않는다.
--
-- ★★ 실행 전에 반드시 읽을 것 ★★
--   이 마이그레이션은 **되돌리기 어려운 데이터 손실**을 포함한다. 손실 단계는 PHASE 6·7로
--   따로 떼어 두었고(prompt85 7항), PHASE 0~5 까지만 실행해도 구조는 대부분 정리된다.
--   PHASE 6·7 은 백업 테이블을 확인한 뒤 사람이 판단해서 실행한다.
--
--   또한 이 스키마로 갈아타면 **현재 애플리케이션 코드는 동작하지 않는다.**
--   Mapper XML 18개 중 사실상 전부가 사라진 컬럼·테이블을 참조한다(answer85.md 5장).
--   코드 마이그레이션과 **같은 배포에서** 함께 적용해야 한다.
--
-- MySQL 8 기준. 아래 문법은 지원되지 않으므로 쓰지 않았다.
--   - ALTER TABLE ... ADD COLUMN IF NOT EXISTS
--   - ALTER TABLE ... DROP COLUMN IF EXISTS
--   - ADD CONSTRAINT IF NOT EXISTS
--   재실행 시 "Duplicate column"/"Can't DROP ... check that it exists" 오류가 나면
--   그 단계가 이미 적용됐다는 뜻이다.
--
-- 실행 전 현재 상태 점검:
--   SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
--     FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
--    ORDER BY TABLE_NAME, ORDINAL_POSITION;
--   SELECT CONSTRAINT_NAME, TABLE_NAME, REFERENCED_TABLE_NAME
--     FROM information_schema.KEY_COLUMN_USAGE
--    WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL;
-- =====================================================================================

-- 스키마 지정은 실행하는 사람이 세션에서 하도록 남겨 둔다(USE 문을 파일에 넣지 않는다 —
-- 이 파일이 H2 테스트에 섞여 들어가는 사고를 막기 위해서다. schema.sql 의 USE 문이 지금
-- 테스트 253개를 깨뜨리고 있는 것과 같은 종류의 문제다).


-- =====================================================================================
-- PHASE 0. 전체 백업 (데이터 손실 대비 — prompt85 10항)
--   구조 변경/삭제 대상 전부를 그대로 복사한다. AS SELECT 는 PK/FK/인덱스를 복제하지 않는
--   "데이터 사본"이다(복구는 rollback SQL 이 구조를 다시 만든 뒤 INSERT SELECT 로 되돌린다).
-- =====================================================================================

CREATE TABLE backup_vehicle_20260730                    AS SELECT * FROM vehicle;
CREATE TABLE backup_vehicle_current_status_20260730     AS SELECT * FROM vehicle_current_status;
CREATE TABLE backup_vehicle_status_history_20260730     AS SELECT * FROM vehicle_status_history;
CREATE TABLE backup_vehicle_fork_current_status_20260730 AS SELECT * FROM vehicle_fork_current_status;
CREATE TABLE backup_vehicle_load_safety_20260730        AS SELECT * FROM vehicle_load_safety;
CREATE TABLE backup_ai_cargo_analysis_20260730          AS SELECT * FROM ai_cargo_analysis;
CREATE TABLE backup_ai_cargo_detection_box_20260730     AS SELECT * FROM ai_cargo_detection_box;
CREATE TABLE backup_station_measurement_20260730        AS SELECT * FROM station_measurement;
CREATE TABLE backup_station_measurement_box_20260730    AS SELECT * FROM station_measurement_box;
CREATE TABLE backup_embedded_vehicle_command_20260730   AS SELECT * FROM embedded_vehicle_command;
CREATE TABLE backup_embedded_error_history_20260730     AS SELECT * FROM embedded_error_history;
CREATE TABLE backup_cargo_20260730                      AS SELECT * FROM cargo;
CREATE TABLE backup_pallet_20260730                     AS SELECT * FROM pallet;
CREATE TABLE backup_rack_20260730                       AS SELECT * FROM rack;
CREATE TABLE backup_rack_level_20260730                 AS SELECT * FROM rack_level;
CREATE TABLE backup_storage_slot_20260730               AS SELECT * FROM storage_slot;
CREATE TABLE backup_transport_task_20260730             AS SELECT * FROM transport_task;
CREATE TABLE backup_transport_command_20260730          AS SELECT * FROM transport_command;

-- 백업 행 수 확인(원본과 같아야 한다):
--   SELECT 'vehicle' t, (SELECT COUNT(*) FROM vehicle) src, (SELECT COUNT(*) FROM backup_vehicle_20260730) bak
--   UNION ALL SELECT 'transport_task', (SELECT COUNT(*) FROM transport_task), (SELECT COUNT(*) FROM backup_transport_task_20260730);


-- =====================================================================================
-- PHASE 1. 기존 FK·인덱스 제거 (prompt85 9항 — FK → 인덱스 → 데이터 → 컬럼 순서)
--   PK/컬럼 타입을 바꾸기 전에 이 컬럼들을 가리키는 참조를 먼저 끊는다.
-- =====================================================================================

ALTER TABLE vehicle_current_status      DROP FOREIGN KEY fk_vehicle_current_status_vehicle;
ALTER TABLE vehicle_status_history      DROP FOREIGN KEY fk_vehicle_status_history_vehicle;
ALTER TABLE ai_cargo_detection_box      DROP FOREIGN KEY fk_detection_box_analysis;
ALTER TABLE station_measurement_box     DROP FOREIGN KEY fk_station_measurement_box_measurement;
ALTER TABLE pallet                      DROP FOREIGN KEY fk_pallet_cargo;
ALTER TABLE rack_level                  DROP FOREIGN KEY fk_rack_level_rack;
ALTER TABLE storage_slot                DROP FOREIGN KEY fk_storage_slot_rack_level;
ALTER TABLE transport_task              DROP FOREIGN KEY fk_transport_task_cargo;
ALTER TABLE transport_task              DROP FOREIGN KEY fk_transport_task_pallet;
ALTER TABLE transport_task              DROP FOREIGN KEY fk_transport_task_slot;
ALTER TABLE transport_command           DROP FOREIGN KEY fk_transport_command_task;


-- =====================================================================================
-- PHASE 2. 최종 스키마에서 제거된 테이블 삭제
--   FK 자식 → 부모 순서로 지운다(PHASE 1 에서 FK 를 이미 끊었지만 순서를 유지한다).
--   ※ 데이터는 PHASE 0 백업에 남아 있다. 코드 영향은 answer85.md 5장.
-- =====================================================================================

DROP TABLE IF EXISTS ai_cargo_detection_box;
DROP TABLE IF EXISTS ai_cargo_analysis;
DROP TABLE IF EXISTS station_measurement_box;
DROP TABLE IF EXISTS vehicle_status_history;
DROP TABLE IF EXISTS vehicle_fork_current_status;
DROP TABLE IF EXISTS vehicle_load_safety;
DROP TABLE IF EXISTS pallet;
DROP TABLE IF EXISTS rack_level;
DROP TABLE IF EXISTS rack;


-- =====================================================================================
-- PHASE 3. vehicle — 대리키(id) 제거, vehicle_id 를 PK 로
--   ★ 여기서 source / vehicle_type 데이터가 사라진다(백업에만 남는다).
--     REAL/SIMULATION 구분을 계속 쓰려면 이 컬럼을 지우지 말고 별도 논의가 필요하다
--     (answer85.md 6장 "확인이 필요한 결정" 참고).
-- =====================================================================================

ALTER TABLE vehicle DROP PRIMARY KEY;
ALTER TABLE vehicle DROP COLUMN id;
-- uk_vehicle_vehicle_id 는 PK 로 승격되므로 별도 UNIQUE 를 남기지 않는다.
ALTER TABLE vehicle DROP INDEX uk_vehicle_vehicle_id;
ALTER TABLE vehicle ADD PRIMARY KEY (vehicle_id);
ALTER TABLE vehicle
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL;
-- (source / vehicle_type 삭제는 PHASE 6 — 데이터 손실 단계)


-- =====================================================================================
-- PHASE 4. vehicle_current_status — fork 컬럼 추가, 타입 정리, FK 재생성
--   message_at / updated_at 삭제는 PHASE 6(손실 단계)로 분리했다.
--   ※ message_at 은 위치 메시지 stale 판정의 기준 컬럼이다(prompt83·84). 지우면 그 방어가
--     사라지므로 코드 대안을 정한 뒤에 실행할 것.
-- =====================================================================================

ALTER TABLE vehicle_current_status
    ADD COLUMN fork_state      VARCHAR(20) NULL AFTER fork_height,
    ADD COLUMN fork_error_code VARCHAR(50) NULL AFTER fork_state;

-- 기존 vehicle_fork_current_status(포크 상태 전용 테이블)의 최신 값을 새 컬럼으로 이관한다.
-- PHASE 2 에서 원본 테이블은 이미 삭제됐으므로 백업에서 읽는다.
UPDATE vehicle_current_status s
   JOIN backup_vehicle_fork_current_status_20260730 f
     ON f.forklift_id = s.vehicle_id
   SET s.fork_state      = f.fork_state,
       s.fork_error_code = f.error_code;

ALTER TABLE vehicle_current_status
    MODIFY COLUMN received_at DATETIME(6) NOT NULL;

ALTER TABLE vehicle_current_status
    ADD CONSTRAINT fk_vehicle_current_status_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT;


-- =====================================================================================
-- PHASE 5. 신규 테이블 + 재구성 테이블
-- =====================================================================================

-- 5-1. 측정 세션·설비 점유 (신규)
CREATE TABLE IF NOT EXISTS station_session (
    session_id VARCHAR(100) NOT NULL PRIMARY KEY COMMENT '측정 세션 고유 식별자',
    cargo_id   VARCHAR(50)  NOT NULL COMMENT '측정 대상 화물 식별자'
);

CREATE TABLE IF NOT EXISTS station_state (
    singleton_id      INT          NOT NULL PRIMARY KEY COMMENT '단일 스테이션 행 고정값',
    active_session_id VARCHAR(100) NULL COMMENT '현재 점유 중인 측정 세션',
    CONSTRAINT chk_station_state_singleton CHECK (singleton_id = 1),
    CONSTRAINT fk_station_state_active_session
        FOREIGN KEY (active_session_id) REFERENCES station_session (session_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

INSERT IGNORE INTO station_state (singleton_id, active_session_id) VALUES (1, NULL);

-- 5-2. station_measurement — 31컬럼 → 8컬럼, 세션 기반으로 **재생성**
--   기존 구조와 의미가 너무 달라 ALTER 로 좁히지 않는다.
--   · 기존 행은 소속 session_id 가 없다(그 개념이 없던 시절 데이터) → NOT NULL FK 를 만족할 수 없다.
--   · 따라서 **기존 데이터는 이관하지 않고** backup_station_measurement_20260730 에만 남긴다.
--     (측정 상세값 height_cm/width_cm/bbox/miniature/eccentric ... 은 새 구조에 대응 컬럼이 없다.
--      대응되는 것은 height_cm → cargo_height 하나뿐이며, 그마저 "팔레트 제외 높이"로 의미가 다르다.)
DROP TABLE IF EXISTS station_measurement;

CREATE TABLE station_measurement (
    sequence_no    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '측정 결과 수신 순서',
    measurement_id VARCHAR(100) NOT NULL COMMENT '외부 측정 결과 고유 식별자',
    session_id     VARCHAR(100) NOT NULL COMMENT '측정 결과가 속한 세션',
    status         VARCHAR(30)  NOT NULL COMMENT '측정 결과 처리 상태',
    cargo_height   DOUBLE       NULL COMMENT '팔레트를 제외한 화물 높이',
    tipping_level  VARCHAR(20)  NULL COMMENT '화물 전복 위험 등급',
    overhang_ratio DOUBLE       NULL COMMENT '팔레트 기준 화물 돌출 비율',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '측정 결과 저장 시각',
    CONSTRAINT uk_station_measurement_measurement_id UNIQUE (measurement_id),
    CONSTRAINT chk_station_measurement_status
        CHECK (status IN ('OK', 'DIMENSIONS_ONLY', 'NO_DETECTION', 'UNRELIABLE')),
    CONSTRAINT chk_station_measurement_height
        CHECK (cargo_height IS NULL OR cargo_height > 0),
    CONSTRAINT chk_station_measurement_tipping_level
        CHECK (tipping_level IS NULL OR tipping_level IN ('SAFE', 'WARNING', 'DANGER')),
    CONSTRAINT chk_station_measurement_overhang
        CHECK (overhang_ratio IS NULL OR overhang_ratio >= 0),
    CONSTRAINT fk_station_measurement_session
        FOREIGN KEY (session_id) REFERENCES station_session (session_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    INDEX idx_station_measurement_session_latest (session_id, sequence_no DESC)
);

-- 5-3. cargo — 대리키 제거, cargo_id 를 PK 로
--   ★ width/length/height/volume 삭제는 PHASE 6(손실 단계). 여기서는 키 구조만 바꾼다.
ALTER TABLE cargo DROP PRIMARY KEY;
ALTER TABLE cargo DROP COLUMN id;
ALTER TABLE cargo DROP INDEX uk_cargo_cargo_id;
ALTER TABLE cargo ADD PRIMARY KEY (cargo_id);
ALTER TABLE cargo
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

-- 5-4. storage_slot — PK id → slot_code, 랙 계층 제거, 높이 컬럼 재정의
--   데이터 변환: usable_height ← rack_level.clear_height, fork_height ← rack_level.fork_height
--   (랙 계층이 사라지므로 그 값을 슬롯으로 내려 보관한다. 대응 값이 없으면 NULL 로 남고
--    NOT NULL 승격 전에 확인이 필요하다 — 아래 점검 쿼리를 반드시 볼 것.)
ALTER TABLE storage_slot
    ADD COLUMN usable_height DOUBLE NULL AFTER slot_code,
    ADD COLUMN fork_height   DOUBLE NULL AFTER usable_height;

UPDATE storage_slot s
   JOIN backup_rack_level_20260730 rl ON rl.id = s.rack_level_id
   SET s.usable_height = rl.clear_height,
       s.fork_height   = COALESCE(rl.fork_height, 0);

-- 점검: 아래 쿼리가 0행이어야 다음 단계로 갈 수 있다.
--   SELECT slot_code, usable_height, fork_height FROM storage_slot
--    WHERE usable_height IS NULL OR usable_height <= 0 OR fork_height IS NULL OR fork_height < 0;
--   SELECT slot_code, destination_x, destination_y, destination_heading FROM storage_slot
--    WHERE destination_x IS NULL OR destination_y IS NULL OR destination_heading IS NULL;

-- reserved_task_id: VARCHAR(50)(task_code 문자열) → BIGINT(transport_task.id)
ALTER TABLE storage_slot ADD COLUMN reserved_task_id_new BIGINT NULL AFTER reserved_task_id;
UPDATE storage_slot s
   JOIN transport_task t ON t.task_code = s.reserved_task_id
   SET s.reserved_task_id_new = t.id;
-- 변환되지 않은 값 확인(코드가 아닌 문자열이 들어 있었다면 여기서 드러난다):
--   SELECT slot_code, reserved_task_id FROM storage_slot
--    WHERE reserved_task_id IS NOT NULL AND reserved_task_id_new IS NULL;
ALTER TABLE storage_slot DROP COLUMN reserved_task_id;
ALTER TABLE storage_slot CHANGE COLUMN reserved_task_id_new reserved_task_id BIGINT NULL
    COMMENT '현재 위치를 예약한 운반 작업';

ALTER TABLE storage_slot DROP INDEX idx_storage_slot_rack_level;
ALTER TABLE storage_slot DROP COLUMN rack_level_id;
ALTER TABLE storage_slot DROP PRIMARY KEY;
ALTER TABLE storage_slot DROP COLUMN id;
ALTER TABLE storage_slot DROP INDEX uk_storage_slot_slot_code;
ALTER TABLE storage_slot ADD PRIMARY KEY (slot_code);

ALTER TABLE storage_slot
    MODIFY COLUMN usable_height       DOUBLE NOT NULL COMMENT '화물을 넣을 수 있는 수직 가용 높이',
    MODIFY COLUMN fork_height         DOUBLE NOT NULL COMMENT '적재 시 목표 포크 높이',
    MODIFY COLUMN destination_x       DOUBLE NOT NULL,
    MODIFY COLUMN destination_y       DOUBLE NOT NULL,
    MODIFY COLUMN destination_heading DOUBLE NOT NULL;

ALTER TABLE storage_slot
    ADD CONSTRAINT uk_storage_slot_stored_cargo UNIQUE (stored_cargo_id),
    ADD CONSTRAINT chk_storage_slot_geometry CHECK (usable_height > 0 AND fork_height >= 0),
    ADD CONSTRAINT chk_storage_slot_status
        CHECK (status IN ('EMPTY', 'RESERVED', 'OCCUPIED', 'BLOCKED')),
    ADD CONSTRAINT chk_storage_slot_state_shape
        CHECK (
            (status IN ('EMPTY', 'BLOCKED') AND reserved_task_id IS NULL AND stored_cargo_id IS NULL)
            OR (status = 'RESERVED'  AND reserved_task_id IS NOT NULL AND stored_cargo_id IS NULL)
            OR (status = 'OCCUPIED'  AND reserved_task_id IS NULL AND stored_cargo_id IS NOT NULL)
        ),
    ADD CONSTRAINT fk_storage_slot_stored_cargo
        FOREIGN KEY (stored_cargo_id) REFERENCES cargo (cargo_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT;
-- CHECK 위반 행이 있으면 ADD CONSTRAINT 가 실패한다. 먼저 확인:
--   SELECT slot_code, status, reserved_task_id, stored_cargo_id FROM storage_slot
--    WHERE NOT (
--      (status IN ('EMPTY','BLOCKED') AND reserved_task_id IS NULL AND stored_cargo_id IS NULL)
--      OR (status='RESERVED' AND reserved_task_id IS NOT NULL AND stored_cargo_id IS NULL)
--      OR (status='OCCUPIED' AND reserved_task_id IS NULL AND stored_cargo_id IS NOT NULL));

-- 5-5. transport_task — destination_slot_id(BIGINT) → destination_slot_code(VARCHAR)
ALTER TABLE transport_task ADD COLUMN destination_slot_code VARCHAR(50) NULL AFTER source_heading;
UPDATE transport_task t
   JOIN backup_storage_slot_20260730 s ON s.id = t.destination_slot_id
   SET t.destination_slot_code = s.slot_code;
ALTER TABLE transport_task DROP INDEX idx_transport_task_slot;
ALTER TABLE transport_task DROP COLUMN destination_slot_id;

ALTER TABLE transport_task
    ADD COLUMN measurement_id VARCHAR(100) NULL AFTER cargo_id;
-- ★ measurement_id 는 최종 스키마에서 NOT NULL + FK 다. 기존 작업 행에는 대응 측정 결과가 없다
--   (station_measurement 를 재생성했고 세션 개념도 새로 생겼다). NOT NULL 승격은 PHASE 7 로 분리한다.

ALTER TABLE transport_task
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    MODIFY COLUMN assigned_at  DATETIME(6) NULL,
    MODIFY COLUMN started_at   DATETIME(6) NULL,
    MODIFY COLUMN picked_up_at DATETIME(6) NULL,
    MODIFY COLUMN completed_at DATETIME(6) NULL,
    MODIFY COLUMN failed_at    DATETIME(6) NULL,
    MODIFY COLUMN status       VARCHAR(20) NOT NULL DEFAULT 'PENDING';

ALTER TABLE transport_task
    ADD CONSTRAINT fk_transport_task_cargo
        FOREIGN KEY (cargo_id) REFERENCES cargo (cargo_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT fk_transport_task_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD INDEX idx_transport_task_cargo_status (cargo_id, status),
    ADD INDEX idx_transport_task_measurement (measurement_id);

-- 5-6. transport_command — PK id → command_id, 컬럼 축소(축소는 PHASE 6)
ALTER TABLE transport_command DROP PRIMARY KEY;
ALTER TABLE transport_command DROP COLUMN id;
ALTER TABLE transport_command DROP INDEX uk_transport_command_command_id;
ALTER TABLE transport_command
    MODIFY COLUMN command_id VARCHAR(50) NOT NULL,
    ADD PRIMARY KEY (command_id);
ALTER TABLE transport_command
    MODIFY COLUMN created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    MODIFY COLUMN completed_at DATETIME(6) NULL,
    MODIFY COLUMN status       VARCHAR(20) NOT NULL DEFAULT 'CREATED';
ALTER TABLE transport_command
    ADD CONSTRAINT fk_transport_command_task
        FOREIGN KEY (task_id) REFERENCES transport_task (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT fk_transport_command_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD INDEX idx_transport_command_task_order (task_id, created_at DESC, command_id DESC);

-- 5-7. storage_slot.reserved_task_id → transport_task.id FK (순환 참조라 마지막에)
ALTER TABLE storage_slot
    ADD CONSTRAINT fk_storage_slot_reserved_task
    FOREIGN KEY (reserved_task_id) REFERENCES transport_task (id)
    ON UPDATE RESTRICT ON DELETE RESTRICT;

-- 5-8. embedded_vehicle_command — PK id → command_id, forklift_id → vehicle_id(이름 변경)
--   ※ 이름 변경이다. 삭제·신설이 아니므로 CHANGE COLUMN 으로 데이터를 그대로 옮긴다.
ALTER TABLE embedded_vehicle_command DROP PRIMARY KEY;
ALTER TABLE embedded_vehicle_command DROP COLUMN id;
ALTER TABLE embedded_vehicle_command DROP INDEX uk_embedded_vehicle_command_command_id;
ALTER TABLE embedded_vehicle_command ADD PRIMARY KEY (command_id);
ALTER TABLE embedded_vehicle_command DROP INDEX idx_embedded_vehicle_command_forklift_issued;
ALTER TABLE embedded_vehicle_command
    CHANGE COLUMN forklift_id vehicle_id VARCHAR(50) NOT NULL COMMENT '명령 대상 차량 식별자';
ALTER TABLE embedded_vehicle_command
    MODIFY COLUMN issued_at    DATETIME(6) NOT NULL,
    MODIFY COLUMN completed_at DATETIME(6) NULL;
ALTER TABLE embedded_vehicle_command
    ADD INDEX idx_embedded_vehicle_command_vehicle_issued (vehicle_id, issued_at DESC);

-- FK 를 붙이기 전에 미등록 차량 참조를 확인한다(기존에는 FK 가 없어 아무 문자열이나 들어갈 수 있었다).
--   SELECT DISTINCT c.vehicle_id FROM embedded_vehicle_command c
--    LEFT JOIN vehicle v ON v.vehicle_id = c.vehicle_id WHERE v.vehicle_id IS NULL;
-- 위 결과가 있으면 그 행을 정리하거나 차량을 등록한 뒤 아래를 실행한다.
ALTER TABLE embedded_vehicle_command
    ADD CONSTRAINT fk_embedded_vehicle_command_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT chk_embedded_vehicle_command_target_system
        CHECK (target_system IN ('ROS2', 'EMBEDDED', 'ALL')),
    ADD CONSTRAINT chk_embedded_vehicle_command_status
        CHECK (status IN ('PENDING','PUBLISHED','PUBLISH_FAILED','ACCEPTED','IN_PROGRESS',
                          'SUCCESS','FAILED','REJECTED','CANCELLED'));

-- 5-9. embedded_error_history — forklift_id → vehicle_id(이름 변경) + FK/CHECK
ALTER TABLE embedded_error_history DROP INDEX idx_embedded_error_history_forklift_occurred;
ALTER TABLE embedded_error_history
    CHANGE COLUMN forklift_id vehicle_id VARCHAR(50) NOT NULL COMMENT '오류가 발생한 차량 식별자';
ALTER TABLE embedded_error_history
    MODIFY COLUMN occurred_at DATETIME(6) NOT NULL;
ALTER TABLE embedded_error_history
    ADD INDEX idx_embedded_error_history_vehicle_occurred (vehicle_id, occurred_at DESC);
--   SELECT DISTINCT e.vehicle_id FROM embedded_error_history e
--    LEFT JOIN vehicle v ON v.vehicle_id = e.vehicle_id WHERE v.vehicle_id IS NULL;
ALTER TABLE embedded_error_history
    ADD CONSTRAINT fk_embedded_error_history_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT chk_embedded_error_history_source
        CHECK (error_source IN ('DRIVE','STEERING','FORK','LIMIT_SWITCH','UART','SYSTEM')),
    ADD CONSTRAINT chk_embedded_error_history_severity
        CHECK (severity IN ('WARNING','ERROR','CRITICAL'));


-- =====================================================================================
-- ★★ PHASE 6. 컬럼 삭제 — 여기서부터 데이터가 사라진다 (prompt85 7항: 별도 단계) ★★
--   백업 테이블을 확인하고, 해당 컬럼을 쓰는 코드를 정리한 뒤에 실행한다.
--   PHASE 5 까지만 적용된 상태에서도 최종 스키마의 "필수 구조"는 모두 갖춰진다
--   (남는 차이는 쓰이지 않는 잉여 컬럼뿐이다).
-- =====================================================================================

-- 6-1. vehicle: 실물/시뮬 구분과 차량 종류가 사라진다
--      영향: VehicleMapper.xml(INSERT·SELECT), data-local.sql, 프론트 VehicleSource 타입
ALTER TABLE vehicle DROP COLUMN source;
ALTER TABLE vehicle DROP COLUMN vehicle_type;

-- 6-2. vehicle_current_status: 메시지 시각·갱신 시각이 사라진다
--      영향: 위치 메시지 stale/중복 판정(prompt83·84)이 기준 컬럼을 잃는다
ALTER TABLE vehicle_current_status DROP COLUMN message_at;
ALTER TABLE vehicle_current_status DROP COLUMN updated_at;

-- 6-3. cargo: 치수 정보가 사라진다
--      영향: 슬롯 적합성 판단 입력값. 최종 스키마에서 치수를 담는 컬럼은 어디에도 없다
--            (station_measurement.cargo_height 만 남는다)
ALTER TABLE cargo DROP COLUMN width;
ALTER TABLE cargo DROP COLUMN length;
ALTER TABLE cargo DROP COLUMN height;
ALTER TABLE cargo DROP COLUMN volume;
ALTER TABLE cargo DROP COLUMN updated_at;

-- 6-4. storage_slot: 슬롯 자체 치수와 시각 컬럼
ALTER TABLE storage_slot DROP COLUMN width;
ALTER TABLE storage_slot DROP COLUMN length;
ALTER TABLE storage_slot DROP COLUMN height;
ALTER TABLE storage_slot DROP COLUMN created_at;
ALTER TABLE storage_slot DROP COLUMN updated_at;

-- 6-5. transport_task: 파렛트 참조와 화물 방향
ALTER TABLE transport_task DROP INDEX idx_transport_task_pallet;
ALTER TABLE transport_task DROP COLUMN pallet_id;
ALTER TABLE transport_task DROP COLUMN cargo_orientation;
ALTER TABLE transport_task DROP COLUMN updated_at;

-- 6-6. transport_command: 명령 종류·단계·payload·발행/ACK 시각
ALTER TABLE transport_command DROP COLUMN task_code;
ALTER TABLE transport_command DROP COLUMN command_type;
ALTER TABLE transport_command DROP COLUMN stage;
ALTER TABLE transport_command DROP COLUMN payload;
ALTER TABLE transport_command DROP COLUMN published_at;
ALTER TABLE transport_command DROP COLUMN acknowledged_at;
ALTER TABLE transport_command DROP COLUMN updated_at;
ALTER TABLE transport_command
    ADD CONSTRAINT chk_transport_command_status
        CHECK (status IN ('CREATED','PUBLISHED','SUCCEEDED','FAILED','PUBLISH_FAILED','TIMEOUT'));

-- 6-7. embedded_vehicle_command: 분류·payload·사유·안전 결과 플래그
ALTER TABLE embedded_vehicle_command DROP COLUMN command_category;
ALTER TABLE embedded_vehicle_command DROP COLUMN payload_json;
ALTER TABLE embedded_vehicle_command DROP COLUMN reason;
ALTER TABLE embedded_vehicle_command DROP COLUMN published_at;
ALTER TABLE embedded_vehicle_command DROP COLUMN stopped_actions;
ALTER TABLE embedded_vehicle_command DROP COLUMN emergency_stop_applied;
ALTER TABLE embedded_vehicle_command DROP COLUMN requires_reset;
ALTER TABLE embedded_vehicle_command DROP COLUMN created_at;
ALTER TABLE embedded_vehicle_command DROP COLUMN updated_at;

-- 6-8. embedded_error_history: 수신/생성 시각
ALTER TABLE embedded_error_history DROP COLUMN received_at;
ALTER TABLE embedded_error_history DROP COLUMN created_at;


-- =====================================================================================
-- ★★ PHASE 7. 기존 운반 작업 데이터 처리 — 실행 전 반드시 결정할 것 ★★
--   transport_task.measurement_id 는 최종 스키마에서 NOT NULL + FK 다.
--   기존 작업 행은 대응 측정 결과가 없어 이 제약을 만족할 수 없다. 셋 중 하나를 택한다.
--
--   (A) 기존 작업을 이력으로 보고 삭제한다 — 가장 단순하다.
--       DELETE FROM transport_command;
--       DELETE FROM transport_task;
--       ALTER TABLE transport_task MODIFY COLUMN measurement_id VARCHAR(100) NOT NULL;
--       ALTER TABLE transport_task ADD CONSTRAINT fk_transport_task_measurement
--           FOREIGN KEY (measurement_id) REFERENCES station_measurement (measurement_id)
--           ON UPDATE RESTRICT ON DELETE RESTRICT;
--       ALTER TABLE transport_task ADD CONSTRAINT fk_transport_task_slot
--           FOREIGN KEY (destination_slot_code) REFERENCES storage_slot (slot_code)
--           ON UPDATE RESTRICT ON DELETE RESTRICT;
--       ALTER TABLE transport_task
--           MODIFY COLUMN destination_slot_code VARCHAR(50) NOT NULL,
--           MODIFY COLUMN source_x DOUBLE NOT NULL, MODIFY COLUMN source_y DOUBLE NOT NULL,
--           MODIFY COLUMN source_heading DOUBLE NOT NULL,
--           MODIFY COLUMN destination_x DOUBLE NOT NULL, MODIFY COLUMN destination_y DOUBLE NOT NULL,
--           MODIFY COLUMN destination_heading DOUBLE NOT NULL,
--           MODIFY COLUMN fork_height DOUBLE NOT NULL;
--
--   (B) 기존 작업마다 자리표시 세션·측정 행을 만들어 연결한다 — 데이터는 남지만 없는 측정을
--       있는 것처럼 꾸미게 된다. 통계·감사에서 거짓이 되므로 권하지 않는다.
--
--   (C) measurement_id 를 NULL 허용으로 남긴다 — 최종 스키마와 어긋난다. 임시 방편일 때만.
--
--   좌표 NOT NULL 승격도 같은 문제를 갖는다. 배정 전 작업은 좌표가 NULL 일 수 있다:
--     SELECT COUNT(*) FROM transport_task
--      WHERE source_x IS NULL OR destination_x IS NULL OR fork_height IS NULL
--         OR destination_slot_code IS NULL;
-- =====================================================================================


-- =====================================================================================
-- PHASE 8. 정리 (백업 삭제는 사람이 판단해서 실행)
--   rollback SQL 이 이 테이블들에 의존한다. 롤백 가능성이 사라져도 좋다고 판단할 때만 지운다.
--
--   DROP TABLE backup_vehicle_20260730;  ... (나머지 동일)
-- =====================================================================================

-- 적용 확인
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME NOT LIKE 'backup_%' ORDER BY TABLE_NAME;
--   -- 기대: cargo, embedded_error_history, embedded_vehicle_command, station_measurement,
--   --       station_session, station_state, storage_slot, transport_command, transport_task,
--   --       vehicle, vehicle_current_status  (11개)
