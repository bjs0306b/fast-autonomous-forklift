-- =============================================================================
-- 한글 깨짐 정정 — DB·테이블 문자셋을 utf8mb4 로 (MySQL 수동 실행 전용)
-- =============================================================================
-- 증상
--   관제 화면 차량 이름이 `????? ??? 1?` 로 나온다. 물음표 개수가 원래 글자 수
--   (시뮬레이션 지게차 1호 = 10자)와 정확히 일치한다.
--
-- 원인
--   db/schema.sql 에 CHARACTER SET 지정이 하나도 없어서, DB 기본 문자셋(대개 latin1)으로
--   테이블이 만들어졌다. JDBC URL 에 characterEncoding=UTF-8 이 있어도 **컬럼이 한글을
--   담지 못하면** MySQL 이 표현 불가 문자를 '?' 로 바꿔 저장한다.
--
-- ⚠️ 데이터는 이미 파괴됐다
--   '?' 로 바뀐 뒤에는 원래 글자를 복원할 수 없다(정보가 사라졌다). 그래서 이 파일은
--   1) 문자셋을 고치고  2) 한글 값을 다시 넣는다. 두 단계가 모두 필요하다.
--
-- 안전성
--   CONVERT TO CHARACTER SET 은 컬럼 정의만 바꾸고 행을 지우지 않는다. 다만 VARCHAR 는
--   utf8mb4 에서 바이트가 늘어나므로, 인덱스 길이 제한(767B/3072B)에 걸리면 실패한다.
--   이 스키마의 문자열 PK 는 VARCHAR(100) 이하라 여유가 있다.
--
-- 실행
--   mysql -u fastbackend -p fast_backend < src/main/resources/db/fix-charset-utf8mb4.sql
-- =============================================================================
USE fast_backend;

-- 바꾸기 전 상태
SELECT DEFAULT_CHARACTER_SET_NAME AS db_charset, DEFAULT_COLLATION_NAME AS db_collation
  FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE();

SELECT TABLE_NAME, TABLE_COLLATION
  FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME;

-- 1. 데이터베이스 기본값 — 앞으로 만들어지는 테이블에 적용된다
ALTER DATABASE fast_backend CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 2. 기존 테이블 — 이미 만들어진 것은 위 ALTER DATABASE 로 바뀌지 않는다
ALTER TABLE cargo                 CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE station_session       CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE station_state         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE station_measurement   CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE vehicle               CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE vehicle_current_status CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE storage_slot          CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE transport_task        CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE vehicle_command       CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE traffic_control_event CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 3. 깨진 한글을 다시 넣는다.
--    '?' 로 저장된 값은 복원할 수 없으므로 원래 문구를 그대로 UPDATE 한다.
--    차량 표시 이름은 data-local.sql · cleanup-dummy-vehicles.sql 과 같은 값이어야 한다
--    (세 곳이 다르면 먼저 실행된 쪽이 이겨 화면 문구가 달라진다).
UPDATE vehicle SET name = '시뮬레이션 지게차 1호' WHERE vehicle_id = 'SIM-F01';
UPDATE vehicle SET name = '시뮬레이션 지게차 2호' WHERE vehicle_id = 'SIM-F02';
UPDATE vehicle SET name = '시뮬레이션 지게차 3호' WHERE vehicle_id = 'SIM-F03';
UPDATE vehicle SET name = '실물 지게차 1호'       WHERE vehicle_id = 'REAL-F01';

-- 자동 등록된 차량은 이름이 ID 그대로다(의도된 동작 — 가짜 이름을 짓지 않는다).
-- 그 행은 한글이 없으므로 건드리지 않는다.

-- 4. 확인 — db_charset 이 utf8mb4 이고 이름에 '?' 가 없어야 한다
SELECT DEFAULT_CHARACTER_SET_NAME AS db_charset FROM information_schema.SCHEMATA
 WHERE SCHEMA_NAME = DATABASE();

SELECT vehicle_id, name, active FROM vehicle ORDER BY vehicle_id;
