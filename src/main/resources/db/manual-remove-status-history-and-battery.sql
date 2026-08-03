-- =============================================================================
-- WARNING
-- 자동 실행 금지
-- 백엔드 배포 및 안정화 확인 후 수동 승인하에 실행
-- 실행 전 반드시 fast_backend 백업
-- =============================================================================
--
-- 목적
--   차량 상태 과거 이력 기능과 배터리 필드를 백엔드에서 제거한 뒤, 남아 있는 DB 구조를 정리한다.
--
-- 실행 시점
--   **코드 배포가 먼저다.** 신형 백엔드는 battery 컬럼을 읽지도 쓰지도 않으므로 컬럼이 남아 있어도
--   정상 동작한다(컬럼이 nullable). 반대로 컬럼을 먼저 지우면 구버전 백엔드의 upsert 가 실패한다.
--   → 배포 → 안정화 확인(최소 1스프린트 권장) → 이 파일 실행 순서를 지킬 것.
--
-- 되돌리기
--   DROP 은 되돌릴 수 없다. 실행 전 백업이 유일한 롤백 수단이다.
--     mysqldump -u fastbackend -p --databases fast_backend --single-transaction \
--       --result-file=fast_backend_before_cleanup.sql
--
-- 이 파일은 spring.sql.init 대상이 아니다(application-*.yml 의 schema/data-locations 에 넣지 말 것).
-- =============================================================================

USE fast_backend;

SELECT DATABASE() AS current_database, NOW(6) AS started_at;


-- =============================================================================
-- 1단계 — 현재 상태 확인 (읽기 전용). 결과를 보고 2단계 실행 여부를 판단한다.
-- =============================================================================

SHOW CREATE TABLE vehicle_current_status;

SHOW TABLES LIKE 'vehicle_status_history';

-- 이력 테이블이 없으면 이 쿼리는 1146 에러가 난다(= 이미 정리됨). 그 경우 2-1 은 건너뛴다.
SELECT COUNT(*) AS history_count FROM vehicle_status_history;

SHOW COLUMNS FROM vehicle_current_status LIKE 'battery';

-- battery 에 실제 값이 남아 있는지(보존할 데이터가 있는지) 확인
SELECT COUNT(*) AS rows_with_battery
FROM vehicle_current_status
WHERE battery IS NOT NULL;

-- CHECK 제약의 실제 이름 확인 — 환경에 따라 이름이 다를 수 있다.
-- MySQL 8.0.16+ 는 CHECK 를 실제로 강제하며 이 뷰에 나타난다. 그 이전 버전은 파싱만 하고 무시한다.
SELECT CONSTRAINT_NAME, CHECK_CLAUSE
FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE()
  AND CHECK_CLAUSE LIKE '%battery%';

SELECT VERSION() AS mysql_version;


-- =============================================================================
-- 2단계 — 실제 삭제 (1단계 확인 후 수동 실행)
-- =============================================================================

-- 2-1. 과거 이력 테이블 제거
--   이 테이블을 FK 로 참조하는 다른 테이블은 없다(참조 방향이 history → vehicle 단방향).
--   아래 쿼리가 0행이어야 안전하다.
SELECT TABLE_NAME, CONSTRAINT_NAME
FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
WHERE REFERENCED_TABLE_SCHEMA = DATABASE()
  AND REFERENCED_TABLE_NAME = 'vehicle_status_history';

DROP TABLE IF EXISTS vehicle_status_history;


-- 2-2. battery CHECK 제약 제거
--   MySQL 은 DROP CHECK 에 IF EXISTS 를 지원하지 않는다. 제약이 없을 때 실패하지 않도록
--   INFORMATION_SCHEMA 로 확인한 뒤에만 실행한다(중복 실행 안전).
--   ※ 제약 이름이 chk_vehicle_status_battery 가 아닐 수 있어 이름을 조회해서 사용한다.
SET @constraint_name = (
    SELECT CONSTRAINT_NAME
    FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND CHECK_CLAUSE LIKE '%battery%'
    LIMIT 1);

SET @sql = IF(@constraint_name IS NULL,
    'SELECT ''SKIP: battery CHECK constraint not found'' AS message',
    CONCAT('ALTER TABLE vehicle_current_status DROP CHECK ', @constraint_name));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- 2-3. battery 컬럼 제거
--   컬럼이 없으면 건너뛴다(중복 실행 안전).
SET @sql = (
    SELECT IF(COUNT(*) = 1,
        'ALTER TABLE vehicle_current_status DROP COLUMN battery',
        'SELECT ''SKIP: vehicle_current_status.battery already removed'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'vehicle_current_status'
      AND COLUMN_NAME = 'battery');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- =============================================================================
-- 3단계 — 검증 (기대: 아래 세 쿼리 모두 0행)
-- =============================================================================

SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vehicle_status_history';

SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'battery';

SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE() AND CHECK_CLAUSE LIKE '%battery%';

-- 현재 상태 테이블이 정상인지 확인
SELECT vehicle_id, status, position_x, position_y, position_frame, heading, speed, received_at
FROM vehicle_current_status ORDER BY vehicle_id;

SELECT 'cleanup finished' AS message, NOW(6) AS finished_at;
