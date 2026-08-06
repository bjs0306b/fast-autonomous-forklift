-- =============================================================================
-- 기존 로컬 DB(fast_backend) 보정용 마이그레이션 — 로컬 수동 실행 전용
-- =============================================================================
--
-- 목적
--   과거 스키마로 만들어진 기존 `fast_backend` 데이터베이스를 현재 백엔드 코드가 요구하는
--   구조에 맞춘다. 새 DB를 만들지 않고 기존 데이터를 보존한다.
--
-- 이 파일과 schema.sql 의 역할 구분
--   schema.sql  : **신규 DB 생성용**. 파일 머리말에 "운영 데이터 마이그레이션은 범위에서 제외"라고
--                 명시돼 있다. 이미 있는 테이블에는 CREATE TABLE IF NOT EXISTS 가 아무 일도 하지
--                 않으므로, 기존 DB의 컬럼 차이를 메우지 못한다.
--   이 파일      : **기존 DB 보정용**. 없는 컬럼을 채우고, INSERT 를 막는 NOT NULL 을 완화한다.
--
-- 실행 방법 (둘 중 하나)
--   1) MySQL Workbench 에서 이 파일을 열고 전체 실행
--   2) mysql -u fastbackend -p fast_backend < src/main/resources/db/migration-local-fast-backend.sql
--
--   ※ 먼저 schema.sql 을 같은 DB에 실행해 **없는 테이블**을 만든 뒤 이 파일을 실행한다.
--     (schema.sql 은 CREATE TABLE IF NOT EXISTS 라 기존 테이블을 건드리지 않는다)
--
-- 안전 원칙
--   - DROP DATABASE / DROP TABLE / DROP COLUMN 없음
--   - DELETE / TRUNCATE 없음
--   - 기존 PK 삭제·변경 없음, AUTO_INCREMENT 제거 없음
--   - 위험한 타입 변환(storage_slot.reserved_task_id VARCHAR→BIGINT 등) 없음 — 문서의 "위험 작업" 참고
--   - 모든 변경은 INFORMATION_SCHEMA 로 현재 상태를 확인한 뒤에만 실행하므로 **중복 실행해도 안전**하다
--   - 이미 현재 스키마와 같은 DB에 실행하면 전부 no-op 이며 'already ...' 메시지만 출력된다
--
-- 자동 실행 금지
--   이 파일은 spring.sql.init 대상이 아니다(application-local.yml 의 data-locations 에 넣지 말 것).
--   운영 DB에 적용하지 않는다. 실행 전 반드시 백업할 것:
--     mysqldump -u fastbackend -p --databases fast_backend --single-transaction \
--       --result-file=fast_backend_backup.sql
-- =============================================================================

USE fast_backend;

SELECT DATABASE() AS current_database, NOW(6) AS started_at;


-- =============================================================================
-- 1. station_state.acquired_at  — 보고된 오류 [42S22][1054] 의 직접 원인
-- =============================================================================
-- 현재 코드가 사용하는 위치
--   StationSessionMapper.xml  findStateForUpdate / 점유·해제 UPDATE
--   StationState.isExpired()  TTL 만료 판정 기준
--   StationMeasurementTimeoutService.expireActiveSession()  5초 주기 스케줄러
-- 컬럼이 없으면 스케줄러가 5초마다 같은 오류를 반복한다.

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE station_state ADD COLUMN acquired_at DATETIME(6) NULL COMMENT ''측정 설비 점유 시작 시각''',
        'SELECT ''SKIP: station_state.acquired_at already exists'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'station_state' AND COLUMN_NAME = 'acquired_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 단일 행 보장. 없을 때만 넣으므로 중복 실행에 안전하다.
INSERT INTO station_state (singleton_id, active_session_id, acquired_at)
SELECT 1, NULL, NULL
WHERE NOT EXISTS (SELECT 1 FROM station_state WHERE singleton_id = 1);

-- 점유 중인데 acquired_at 이 비어 있는 행 보정.
-- StationState.isExpired() 는 acquired_at 이 NULL 이면 "만료"로 보고 다음 정리 주기에 회수하므로
-- 그대로 두어도 잠기지는 않는다. 다만 로그에 점유 시각이 남도록 현재 시각을 채운다.
UPDATE station_state
   SET acquired_at = NOW(6)
 WHERE singleton_id = 1 AND active_session_id IS NOT NULL AND acquired_at IS NULL;


-- =============================================================================
-- 2. vehicle_current_status  — 차량 위치 저장 경로 복구
-- =============================================================================
-- 2-1. position_frame
--   VehicleCurrentStatusMapper.updateLocationIfNewer 가 INSERT/UPDATE 양쪽에서 사용한다.
--   없으면 MQTT 위치 메시지를 받을 때마다 Unknown column 오류가 난다.

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE vehicle_current_status ADD COLUMN position_frame VARCHAR(10) NULL COMMENT ''좌표계(map 또는 odom)''',
        'SELECT ''SKIP: vehicle_current_status.position_frame already exists'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vehicle_current_status' AND COLUMN_NAME = 'position_frame');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2-2. updated_at 완화
--   구 스키마에는 updated_at DATETIME NOT NULL(기본값 없음)이 있는데 현재 upsert 는 이 컬럼을 쓰지
--   않는다. STRICT 모드에서 INSERT 가 1364(Field doesn't have a default value)로 실패하므로
--   기본값을 준다. 컬럼이 없거나 이미 nullable 이면 건너뛴다.

SET @sql = (
    SELECT IF(COUNT(*) = 1,
        'ALTER TABLE vehicle_current_status MODIFY COLUMN updated_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6)',
        'SELECT ''SKIP: vehicle_current_status.updated_at absent or already relaxed'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vehicle_current_status'
      AND COLUMN_NAME = 'updated_at' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- =============================================================================
-- 3. vehicle  — 차량 등록/시드 INSERT 복구
-- =============================================================================
-- 구 스키마의 source VARCHAR(20) NOT NULL 은 현재 코드가 쓰지 않는다(VehicleMapper.insert 는
-- vehicle_id, name, active, created_at, updated_at 만 넣는다). 기본값이 없으면 INSERT 가 실패한다.
-- PK(id)와 UNIQUE(vehicle_id)는 건드리지 않는다 — 현재 코드는 vehicle_id 로만 조회·조인하므로
-- PK 를 바꿀 이유가 없다(위험 작업으로 분리).

SET @sql = (
    SELECT IF(COUNT(*) = 1,
        'ALTER TABLE vehicle MODIFY COLUMN source VARCHAR(20) NULL COMMENT ''(구 컬럼) 현재 코드 미사용''',
        'SELECT ''SKIP: vehicle.source absent or already nullable'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vehicle'
      AND COLUMN_NAME = 'source' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(COUNT(*) = 1,
        'ALTER TABLE vehicle MODIFY COLUMN vehicle_type VARCHAR(30) NULL COMMENT ''(구 컬럼) 현재 코드 미사용''',
        'SELECT ''SKIP: vehicle.vehicle_type absent or already nullable'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vehicle'
      AND COLUMN_NAME = 'vehicle_type' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- =============================================================================
-- 5. cargo  — 화물 등록 INSERT 복구
-- =============================================================================
-- 구 스키마의 width/length/height/volume 는 NOT NULL(기본값 없음)인데 현재 CargoMapper.insert 는
-- cargo_id, created_at 만 넣는다. 화물 치수는 이제 station_measurement 가 관리한다.

SET @sql = (SELECT IF(COUNT(*) = 1, 'ALTER TABLE cargo MODIFY COLUMN width DOUBLE NULL',
        'SELECT ''SKIP: cargo.width'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cargo'
      AND COLUMN_NAME = 'width' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 1, 'ALTER TABLE cargo MODIFY COLUMN length DOUBLE NULL',
        'SELECT ''SKIP: cargo.length'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cargo'
      AND COLUMN_NAME = 'length' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 1, 'ALTER TABLE cargo MODIFY COLUMN height DOUBLE NULL',
        'SELECT ''SKIP: cargo.height'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cargo'
      AND COLUMN_NAME = 'height' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 1, 'ALTER TABLE cargo MODIFY COLUMN volume DOUBLE NULL',
        'SELECT ''SKIP: cargo.volume'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cargo'
      AND COLUMN_NAME = 'volume' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 1,
        'ALTER TABLE cargo MODIFY COLUMN updated_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6)',
        'SELECT ''SKIP: cargo.updated_at'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cargo'
      AND COLUMN_NAME = 'updated_at' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- =============================================================================
-- 6. station_measurement  — 측정 결과 저장 복구
-- =============================================================================
-- 구 스키마에는 40개가 넘는 컬럼이 있으나 대부분 nullable 이라 그대로 두어도 무해하다.
-- created_at 만 기본값이 없어 INSERT 를 막는다(현재 Mapper 는 created_at 을 넣지만, 구 DB의
-- 다른 NOT NULL 컬럼이 있으면 0-3 확인 쿼리로 찾아 같은 방식으로 완화할 것).

SET @sql = (
    SELECT IF(COUNT(*) = 1,
        'ALTER TABLE station_measurement MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)',
        'SELECT ''SKIP: station_measurement.created_at already has default'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'station_measurement'
      AND COLUMN_NAME = 'created_at' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- =============================================================================
-- 7. transport_task  — 측정 연동 컬럼 추가
-- =============================================================================
-- TransportTaskMapper 가 사용하는 컬럼 4개. 구 스키마에는 없다.

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE transport_task ADD COLUMN measurement_session_id VARCHAR(100) NULL COMMENT ''AI 세션 생성 후 연결된 세션 식별자''',
        'SELECT ''SKIP: transport_task.measurement_session_id exists'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'transport_task' AND COLUMN_NAME = 'measurement_session_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE transport_task ADD COLUMN measurement_id VARCHAR(100) NULL COMMENT ''측정 완료 후 연결되는 배치 판단 결과''',
        'SELECT ''SKIP: transport_task.measurement_id exists'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'transport_task' AND COLUMN_NAME = 'measurement_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE transport_task ADD COLUMN destination_slot_code VARCHAR(50) NULL COMMENT ''측정 완료 후 선택되는 목적지 적재 위치''',
        'SELECT ''SKIP: transport_task.destination_slot_code exists'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'transport_task' AND COLUMN_NAME = 'destination_slot_code');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE transport_task ADD COLUMN measurement_requested_at DATETIME(6) NULL COMMENT ''AI 측정 요청 발행 시각. TTL 실패 판정 기준''',
        'SELECT ''SKIP: transport_task.measurement_requested_at exists'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'transport_task' AND COLUMN_NAME = 'measurement_requested_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 실패 원인 코드 (S15P11A304-114). CHECK 제약은 새 코드 추가 시 함께 넓혀야 하므로
-- 여기서는 컬럼만 추가한다 — 값 검증은 애플리케이션 enum 이 하고, 신규 DB 는 schema.sql 이 건다.
SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE transport_task ADD COLUMN failure_code VARCHAR(40) NULL COMMENT ''실패 원인 코드. 관제 화면이 원인별 문구를 고르는 근거''',
        'SELECT ''SKIP: transport_task.failure_code exists'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'transport_task' AND COLUMN_NAME = 'failure_code');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 구 필수 컬럼 완화 (현재 코드는 pallet_id / updated_at 을 INSERT 하지 않는다)
SET @sql = (
    SELECT IF(COUNT(*) = 1, 'ALTER TABLE transport_task MODIFY COLUMN pallet_id VARCHAR(50) NULL COMMENT ''(구 컬럼) 현재 코드 미사용''',
        'SELECT ''SKIP: transport_task.pallet_id'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_task'
      AND COLUMN_NAME = 'pallet_id' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(COUNT(*) = 1, 'ALTER TABLE transport_task MODIFY COLUMN updated_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6)',
        'SELECT ''SKIP: transport_task.updated_at'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_task'
      AND COLUMN_NAME = 'updated_at' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- TTL 스케줄러 조회 인덱스
SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE transport_task ADD INDEX idx_transport_task_measurement_timeout (status, measurement_requested_at)',
        'SELECT ''SKIP: idx_transport_task_measurement_timeout exists'' AS message')
    FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'transport_task' AND INDEX_NAME = 'idx_transport_task_measurement_timeout');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- =============================================================================
-- 8. storage_slot  — 적재 위치
-- =============================================================================
-- 8-1. 현재 코드가 요구하는 컬럼 추가. 기존 행이 있을 수 있으므로 우선 NULL 허용으로 넣는다
--      (NOT NULL 로 조이는 것은 값을 채운 뒤 수동으로 — 문서의 "선택 변경" 참고).

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE storage_slot ADD COLUMN usable_height DOUBLE NULL COMMENT ''수직 가용 높이(m)''',
        'SELECT ''SKIP: storage_slot.usable_height exists'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'storage_slot' AND COLUMN_NAME = 'usable_height');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE storage_slot ADD COLUMN fork_height DOUBLE NULL COMMENT ''목표 포크 높이(m)''',
        'SELECT ''SKIP: storage_slot.fork_height exists'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'storage_slot' AND COLUMN_NAME = 'fork_height');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 8-2. 기존 행 값 채우기. 구 height 를 usable_height 로 옮긴다.
--      ※ 이 대응이 맞는지는 팀 확인이 필요하다(랙 단 높이 대비 가용 높이 정의).
SET @sql = (
    SELECT IF(COUNT(*) = 1,
        'UPDATE storage_slot SET usable_height = height WHERE usable_height IS NULL AND height IS NOT NULL',
        'SELECT ''SKIP: storage_slot.height column not present'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'storage_slot' AND COLUMN_NAME = 'height');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE storage_slot SET fork_height = 0 WHERE fork_height IS NULL;

-- 8-3. 구 필수 컬럼 완화 (현재 StorageSlotMapper.insert 가 넣지 않는 컬럼들)
SET @sql = (SELECT IF(COUNT(*) = 1, 'ALTER TABLE storage_slot MODIFY COLUMN rack_level_id BIGINT NULL COMMENT ''(구 컬럼) 현재 코드 미사용''',
        'SELECT ''SKIP: storage_slot.rack_level_id'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'storage_slot'
      AND COLUMN_NAME = 'rack_level_id' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 1, 'ALTER TABLE storage_slot MODIFY COLUMN width DOUBLE NULL',
        'SELECT ''SKIP: storage_slot.width'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'storage_slot'
      AND COLUMN_NAME = 'width' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 1, 'ALTER TABLE storage_slot MODIFY COLUMN length DOUBLE NULL',
        'SELECT ''SKIP: storage_slot.length'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'storage_slot'
      AND COLUMN_NAME = 'length' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 1, 'ALTER TABLE storage_slot MODIFY COLUMN height DOUBLE NULL',
        'SELECT ''SKIP: storage_slot.height'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'storage_slot'
      AND COLUMN_NAME = 'height' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 1, 'ALTER TABLE storage_slot MODIFY COLUMN created_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6)',
        'SELECT ''SKIP: storage_slot.created_at'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'storage_slot'
      AND COLUMN_NAME = 'created_at' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 1, 'ALTER TABLE storage_slot MODIFY COLUMN updated_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6)',
        'SELECT ''SKIP: storage_slot.updated_at'' AS message')
    FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'storage_slot'
      AND COLUMN_NAME = 'updated_at' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- =============================================================================
-- 9. vehicle_command  — 구 스키마에는 없는 테이블
-- =============================================================================
-- 구 DB에는 embedded_vehicle_command / transport_command 가 있었고 vehicle_command 는 없다.
-- 새로 만드는 테이블이므로 CHECK/FK 를 그대로 포함해도 기존 데이터와 충돌하지 않는다.
-- (schema.sql 을 먼저 실행했다면 이 문장은 아무 일도 하지 않는다)

CREATE TABLE IF NOT EXISTS vehicle_command (
    command_id     VARCHAR(100) NOT NULL PRIMARY KEY COMMENT '차량 명령 고유 식별자',
    task_id        BIGINT       NULL COMMENT '연결된 운반 작업 식별자',
    vehicle_id     VARCHAR(50)  NOT NULL COMMENT '명령 대상 차량 식별자',
    command        VARCHAR(30)  NOT NULL COMMENT '실행할 명령 이름',
    target_system  VARCHAR(20)  NOT NULL COMMENT '명령 대상 시스템(ROS2, EMBEDDED, ALL)',
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '명령 처리 상태',
    result_message VARCHAR(500) NULL COMMENT '명령 처리 결과 또는 실패 상세',
    completed_at   DATETIME(6)  NULL COMMENT '명령 완료 시각',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '명령 생성 시각',
    CONSTRAINT chk_vehicle_command_target CHECK (target_system IN ('ROS2', 'EMBEDDED', 'ALL')),
    CONSTRAINT chk_vehicle_command_status CHECK (status IN (
        'PENDING', 'PUBLISHED', 'PUBLISH_FAILED', 'ACCEPTED', 'IN_PROGRESS',
        'SUCCESS', 'FAILED', 'REJECTED', 'CANCELLED'
    )),
    INDEX idx_vehicle_command_task (task_id),
    INDEX idx_vehicle_command_vehicle (vehicle_id),
    INDEX idx_vehicle_command_status (status)
);
-- FK 는 참조 대상(transport_task.id, vehicle.vehicle_id)의 인덱스 상태에 따라 실패할 수 있어
-- 여기서 걸지 않는다. 필요하면 문서의 "검증 후 선택 적용" 절차를 따를 것.


-- =============================================================================
-- 10. 초기 데이터 — MQTT 메시지가 폐기되지 않으려면 반드시 필요
-- =============================================================================
-- 백엔드는 미등록 vehicleId 의 상태·위치·경로 메시지를 전부 폐기한다
--   ForkliftLocationService.handleLocation / IsaacForkliftPathService.handlePath
--   VehicleStatusService.updateCurrentStatus
-- SIM-F01 = Isaac twin_bridge.py 의 SIM_ID. 관제 대상은 이 한 대뿐이다.
-- REAL-F01(ROS2 브리지 mqtt_bridge.yaml 기본값)은 더 이상 등록하지 않는다 — 실물 지게차 연동을
-- 관제 목록에서 제외했다. 이미 REAL-F01 이 들어간 DB 는 db/migrate-real-f01-to-sim-f01.sql 로 정리한다.
--
-- 구 vehicle 테이블에 source NOT NULL 이 남아 있을 수 있으므로, 컬럼 존재 여부에 따라
-- INSERT 문을 나눠 실행한다.

SET @has_source = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vehicle' AND COLUMN_NAME = 'source');

-- 이름은 data-local.sql 과 반드시 같은 값을 쓴다(INSERT IGNORE 라 먼저 실행된 쪽이 이긴다).
SET @sql = IF(@has_source = 1,
    'INSERT IGNORE INTO vehicle (vehicle_id, name, source, active, created_at, updated_at) VALUES
        (''SIM-F01'',  ''시뮬레이션 지게차 1호'', ''SIM'',  TRUE, NOW(6), NOW(6))',
    'INSERT IGNORE INTO vehicle (vehicle_id, name, active, created_at, updated_at) VALUES
        (''SIM-F01'',  ''시뮬레이션 지게차 1호'', TRUE, NOW(6), NOW(6))');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 최신 상태 행. 없어도 위치 수신 시 자동 생성되지만, 대시보드 초기 표시를 위해 넣어 둔다.
INSERT IGNORE INTO vehicle_current_status (vehicle_id, status, received_at) VALUES
    ('SIM-F01',  'UNKNOWN', NOW(6));


-- =============================================================================
-- 11. 마이그레이션 결과 요약
-- =============================================================================

SELECT 'columns' AS check_type, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'station_state'          AND COLUMN_NAME = 'acquired_at')
    OR (TABLE_NAME = 'vehicle_current_status' AND COLUMN_NAME = 'position_frame')
    OR (TABLE_NAME = 'transport_task'         AND COLUMN_NAME IN
        ('measurement_session_id','measurement_id','destination_slot_code','measurement_requested_at'))
    OR (TABLE_NAME = 'storage_slot'           AND COLUMN_NAME IN ('usable_height','fork_height')))
ORDER BY TABLE_NAME, COLUMN_NAME;

-- INSERT 를 막을 컬럼이 남아 있는지 (기대: 0행)
SELECT 'blocking_not_null' AS check_type, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL AND EXTRA NOT LIKE '%auto_increment%'
  AND ((TABLE_NAME = 'vehicle'                AND COLUMN_NAME NOT IN ('vehicle_id','name','active','created_at','updated_at'))
    OR (TABLE_NAME = 'vehicle_current_status' AND COLUMN_NAME NOT IN ('vehicle_id','status','received_at'))
    OR (TABLE_NAME = 'cargo'                  AND COLUMN_NAME NOT IN ('cargo_id','created_at'))
    OR (TABLE_NAME = 'storage_slot'           AND COLUMN_NAME NOT IN ('slot_code','usable_height','fork_height','destination_x','destination_y','destination_heading','status'))
    OR (TABLE_NAME = 'transport_task'         AND COLUMN_NAME NOT IN ('id','task_code','cargo_id','status','created_at')))
ORDER BY TABLE_NAME, COLUMN_NAME;

-- ─────────────────────────────────────────────────────────────────────────────
-- 교통 관제 정지/재개 이벤트 (FR-502-1a)
--
-- CREATE TABLE IF NOT EXISTS 라 반복 실행해도 안전하다. 스키마 정의는 db/schema.sql 과 같아야
-- 하며, 한쪽만 고치면 신규 DB 와 기존 DB 가 갈라진다 — 둘 다 고칠 것.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS traffic_control_event (
    id                     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    vehicle_id             VARCHAR(50)  NOT NULL COMMENT '정지/재개 대상 차량',
    event_type             VARCHAR(20)  NOT NULL COMMENT 'HOLD(정지) 또는 RELEASE(재개)',
    reason_code            VARCHAR(40)  NULL COMMENT '정지 사유 코드. RELEASE 면 해제된 직전 사유',
    reason_detail          VARCHAR(500) NULL COMMENT '사람이 읽는 사유 상세',
    counterpart_vehicle_id VARCHAR(50)  NULL COMMENT '안전거리 위반 상대 또는 구역 점유 차량',
    slot_code              VARCHAR(50)  NULL COMMENT '작업 구역 사유일 때의 선반 코드',
    distance_m             DOUBLE       NULL COMMENT '판단 당시 유효 거리(m)',
    command_id             VARCHAR(100) NULL COMMENT '이 판단으로 발행된 명령. 발행 실패면 NULL',
    occurred_at            DATETIME(6)  NOT NULL COMMENT '판단 시각',
    CONSTRAINT chk_traffic_event_type CHECK (event_type IN ('HOLD', 'RELEASE')),
    CONSTRAINT chk_traffic_event_reason CHECK (
        reason_code IS NULL OR reason_code IN ('SAFETY_DISTANCE', 'WORK_ZONE_OCCUPIED')
    ),
    CONSTRAINT fk_traffic_event_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id),
    INDEX idx_traffic_event_vehicle (vehicle_id, occurred_at),
    INDEX idx_traffic_event_occurred (occurred_at)
);

SELECT 'vehicles' AS check_type, vehicle_id, name, active FROM vehicle ORDER BY vehicle_id;
SELECT 'station_state' AS check_type, singleton_id, active_session_id, acquired_at FROM station_state;

SELECT 'migration finished' AS message, NOW(6) AS finished_at;
