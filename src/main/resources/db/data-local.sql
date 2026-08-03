-- 로컬 개발용 선택 데이터(시드 전용). db/schema.sql 실행 후 사용한다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 실행 방법 — "No database selected"(오류 1046)가 나면 이 절을 볼 것
--
-- 이 파일에는 의도적으로 `USE fast_backend;` 를 넣지 않았다. 이 파일은 손으로만 돌리는 게 아니라
-- Spring 의 spring.sql.init 도 자동 실행하는데(application-mqttcheck.yml 의 data-locations),
-- 그 프로필은 **H2(MODE=MySQL)** 를 쓴다. H2 는 `USE` 를 지원하지 않아
-- (실측: Schema "FAST_BACKEND" not found) 한 줄만 넣어도 애플리케이션 기동이 깨진다.
--
-- 따라서 대상 DB는 파일이 아니라 **실행하는 쪽**이 지정한다.
--
--   CLI  : mysql -u fastbackend -p fast_backend < src/main/resources/db/data-local.sql
--          (DB 이름을 인자로 주므로 USE 가 필요 없다 — 가장 확실한 방법)
--
--   GUI  : MySQL Workbench 는 좌측 SCHEMAS 에서 fast_backend 를 더블클릭(굵게 표시됨),
--          DataGrip 은 콘솔 상단의 스키마 선택 상자에서 fast_backend 선택.
--          또는 같은 탭에서 먼저 `USE fast_backend;` 를 실행한 뒤 이 파일을 실행한다.
--          확인: SELECT DATABASE();  →  fast_backend
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 실행 순서 — 과거 스키마로 만들어진 기존 DB라면 이 파일만으로는 실패한다
--
--   1) db/schema.sql                        없는 테이블 생성(CREATE TABLE IF NOT EXISTS)
--   2) db/migration-local-fast-backend.sql  기존 테이블의 컬럼 차이 보정
--   3) db/data-local.sql                    ← 이 파일
--
-- 2번을 건너뛰면 아래 INSERT 가 다음 이유로 실패한다.
--   · storage_slot 에 usable_height / fork_height 컬럼이 없음 (Unknown column)
--   · vehicle.source 같은 구형 NOT NULL 컬럼이 기본값 없이 남아 있음
--     (Field 'source' doesn't have a default value)
-- 스키마 보정은 migration 파일이 담당하고, 이 파일은 시드만 넣는다.
-- ─────────────────────────────────────────────────────────────────────────────
--
-- REAL-F01 / SIM-F01은 통신 규격에서 실제로 사용하는 차량 식별자다
-- (ROS2 브리지 mqtt_bridge.yaml의 vehicle_id 기본값, Isaac twin_bridge.py의 SIM_ID/REAL_ID).
-- 백엔드는 미등록 vehicleId의 상태·위치·경로 메시지를 전부 폐기하므로, 이 두 행이 없으면
-- ROS2와 Isaac이 보낸 메시지가 한 건도 화면에 도달하지 않는다.
-- FORKLIFT-01/02는 기존 문서 예시와 테스트가 쓰는 값이라 그대로 남긴다.
USE fast_backend;

INSERT IGNORE INTO vehicle (vehicle_id, name, active) VALUES
    ('REAL-F01', 'Real Forklift 01', TRUE),
    ('SIM-F01', 'Sim Forklift 01', TRUE),
    ('FORKLIFT-01', 'Forklift 01', TRUE),
    ('FORKLIFT-02', 'Forklift 02', TRUE);

INSERT IGNORE INTO vehicle_current_status
    (vehicle_id, status, received_at) VALUES
    ('REAL-F01', 'UNKNOWN', NOW(6)),
    ('SIM-F01', 'UNKNOWN', NOW(6)),
    ('FORKLIFT-01', 'IDLE', NOW(6)),
    ('FORKLIFT-02', 'UNKNOWN', NOW(6));

INSERT IGNORE INTO storage_slot
    (slot_code, usable_height, fork_height, destination_x, destination_y,
     destination_heading, status) VALUES
    ('SLOT-01', 1.00, 0.15, 2.00, 1.00, 180.0, 'EMPTY'),
    ('SLOT-02', 1.50, 0.65, 2.00, 2.00, 180.0, 'EMPTY');
