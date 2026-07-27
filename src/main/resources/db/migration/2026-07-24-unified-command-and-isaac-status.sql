-- =============================================================================
-- 운영/공용 MySQL 적용용 마이그레이션 (2026-07-24)
-- prompt32.md "확정된 통신 규격" 반영에 필요한 스키마 변경
-- =============================================================================
--
-- 적용 대상: 이미 이전 버전 schema.sql로 테이블이 생성되어 있는 팀 공용 MySQL(EC2/로컬).
-- 새로 구축하는 DB라면 이 파일 대신 src/main/resources/db/schema.sql 전체를 실행하면 된다
-- (schema.sql은 이미 아래 변경이 반영된 최신 전체 정의다).
--
-- 이 저장소는 애플리케이션 기동만으로 공용 DB 스키마가 바뀌는 것을 막기 위해 schema.sql을 자동
-- 실행하지 않는다(application-local.yml 주석 참고). 이 마이그레이션도 동일하게 **수동 실행 전제**다.
--
-- 주의: MySQL 8은 ALTER TABLE ... ADD COLUMN IF NOT EXISTS 를 지원하지 않는다. 이미 적용된 DB에
-- 다시 실행하면 "Duplicate column name" 오류가 난다 — 그 오류는 이미 적용됐다는 뜻이므로 무시하고
-- 넘어가면 된다. 실행 전 아래 확인 쿼리로 현재 상태를 먼저 점검할 것을 권장한다.
--
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME IN ('vehicle_current_status','vehicle_status_history','embedded_vehicle_command')
--    ORDER BY TABLE_NAME, ORDINAL_POSITION;
--
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Isaac 상태 확장 필드 저장 (prompt32.md 1장 4번)
--
-- Isaac Sim 상태 메시지의 forkHeight / hasCargo / cargoId / footprint.length / footprint.width 를
-- WebSocket 중계뿐 아니라 DB에도 저장한다. 전부 nullable이다 — ROS2 일반 상태 메시지에는 이 필드가
-- 없으며, 그 경우 애플리케이션이 기존 값을 보존한다(null로 덮어쓰지 않는다).
-- -----------------------------------------------------------------------------

ALTER TABLE vehicle_current_status
    ADD COLUMN fork_height      DOUBLE      NULL AFTER speed,
    ADD COLUMN has_cargo        BOOLEAN     NULL AFTER fork_height,
    ADD COLUMN cargo_id         VARCHAR(50) NULL AFTER has_cargo,
    ADD COLUMN footprint_length DOUBLE      NULL AFTER cargo_id,
    ADD COLUMN footprint_width  DOUBLE      NULL AFTER footprint_length;

ALTER TABLE vehicle_status_history
    ADD COLUMN fork_height      DOUBLE      NULL AFTER speed,
    ADD COLUMN has_cargo        BOOLEAN     NULL AFTER fork_height,
    ADD COLUMN cargo_id         VARCHAR(50) NULL AFTER has_cargo,
    ADD COLUMN footprint_length DOUBLE      NULL AFTER cargo_id,
    ADD COLUMN footprint_width  DOUBLE      NULL AFTER footprint_length;

-- -----------------------------------------------------------------------------
-- 2. 통합 명령 envelope 컬럼 (prompt32.md 1장 8번, 3장 3번)
--
-- target_system / command_category 는 NOT NULL이다. 기존 행에는 값이 없으므로 아래 순서로 적용한다.
--   (1) nullable로 추가
--   (2) 기존 데이터 backfill — 이전 버전은 실물 임베디드 명령만 저장했으므로 command 값으로 역산 가능
--   (3) NOT NULL로 승격
--
-- payload_json 은 MySQL JSON 컬럼이 아니라 VARCHAR다(H2 테스트 호환, 프로젝트의 JSON 컬럼 미사용 원칙).
-- -----------------------------------------------------------------------------

ALTER TABLE embedded_vehicle_command
    ADD COLUMN target_system    VARCHAR(20)   NULL AFTER command,
    ADD COLUMN command_category VARCHAR(20)   NULL AFTER target_system,
    ADD COLUMN payload_json     VARCHAR(1000) NULL AFTER command_category;

-- 기존 데이터 backfill: 확정 규격의 명령 조합 검증 규칙(prompt32.md 1장 8번)과 동일하게 매핑한다.
UPDATE embedded_vehicle_command
   SET target_system = CASE
           WHEN command IN ('EMERGENCY_STOP', 'RESET_ESTOP') THEN 'ALL'
           WHEN command = 'MOVE'                             THEN 'ROS2'
           ELSE 'EMBEDDED'
       END,
       command_category = CASE
           WHEN command IN ('EMERGENCY_STOP', 'RESET_ESTOP') THEN 'SAFETY'
           WHEN command = 'MOVE'                             THEN 'MOVE'
           WHEN command IN ('FORK_UP', 'FORK_DOWN')          THEN 'FORK'
           WHEN command IN ('LOAD', 'UNLOAD')                THEN 'LOAD'
           ELSE 'FORK'
       END
 WHERE target_system IS NULL
    OR command_category IS NULL;

-- STOP은 확정 규격의 조합표에 명시되지 않았다(1장 8번 목록에는 있으나 조합 규칙이 없음).
-- 애플리케이션은 STOP을 EMBEDDED + SAFETY로 처리한다(VehicleCommandType Javadoc 참고).
UPDATE embedded_vehicle_command
   SET target_system = 'EMBEDDED',
       command_category = 'SAFETY'
 WHERE command = 'STOP';

ALTER TABLE embedded_vehicle_command
    MODIFY COLUMN target_system    VARCHAR(20) NOT NULL,
    MODIFY COLUMN command_category VARCHAR(20) NOT NULL;

-- -----------------------------------------------------------------------------
-- 3. 확장 VehicleStatus enum (prompt32.md 1장 3번)
--
-- status 컬럼은 원래부터 VARCHAR(20)이고 MySQL ENUM 타입을 쓰지 않으므로 **DDL 변경이 필요 없다**.
-- 새 값(MOVING/LIFTING/LOADING/UNLOADING/ESTOP)은 그대로 저장된다.
--
-- 다만 이전 버전은 MOVING을 ACTIVE로 강제 변환해 저장했다. 과거에 저장된 ACTIVE 행 중 실제로는
-- 주행 중이었던 것을 사후에 구분할 방법이 없으므로 **기존 데이터는 변환하지 않는다**(추측 금지).
-- 이 시점 이후 수신되는 메시지부터 MOVING이 그대로 보존된다.
-- -----------------------------------------------------------------------------

-- (DDL 변경 없음)

-- -----------------------------------------------------------------------------
-- 4. 적용 확인
-- -----------------------------------------------------------------------------

-- SELECT COLUMN_NAME, IS_NULLABLE, DATA_TYPE FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vehicle_current_status'
--    AND COLUMN_NAME IN ('fork_height','has_cargo','cargo_id','footprint_length','footprint_width');
--
-- SELECT COLUMN_NAME, IS_NULLABLE FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'embedded_vehicle_command'
--    AND COLUMN_NAME IN ('target_system','command_category','payload_json');
--
-- SELECT command, target_system, command_category, COUNT(*)
--   FROM embedded_vehicle_command GROUP BY command, target_system, command_category;
