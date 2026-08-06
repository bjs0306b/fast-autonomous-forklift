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
-- SIM-F01은 관제 대상 대표 차량이다 (Isaac twin_bridge.py의 SIM_ID).
-- 백엔드는 미등록 vehicleId의 상태·위치·경로 메시지를 전부 폐기하므로, 이 행이 없으면
-- Isaac이 보낸 메시지가 한 건도 화면에 도달하지 않는다.
-- REAL-F01은 더 이상 등록하지 않는다. 실물 지게차 연동을 관제 목록에서 제외했다.
--   · ROS2 브리지(ros2_ws mqtt_bridge.yaml)는 여전히 forklift/REAL-F01/* 로 발행하지만,
--     미등록 차량이므로 백엔드가 그 메시지를 폐기한다 — 의도된 동작이다.
--   · Isaac twin_bridge.py 는 브로커에서 직접 forklift/REAL-F01/location 을 구독해 씬의
--     미러 prim 을 움직이므로, DB 에서 빼도 트윈 표시는 그대로 동작한다.
--   · 실물 차량을 다시 관제에 올릴 때는 별칭 매핑이 아니라 이 시드에 행을 다시 추가할 것.
-- FORKLIFT-01/02는 넣지 않는다. 실제 장비가 없는 더미인데 MonitoringService 가 active=true 인
-- 차량을 전부 내려주는 탓에, 관제 화면에 "연동된 차량"처럼 섞여 보였다. 테스트는 DB 시드가 아니라
-- 코드 안의 리터럴을 쓰므로 이 두 행을 빼도 영향이 없다.
--
-- 차량 이름은 화면 표시용이다(vehicle_id 가 시스템 식별자). 여기와 migration-local-fast-backend.sql,
-- DEPLOY_GPU_SERVER.md 가 서로 다른 이름을 넣으면 먼저 실행된 쪽이 이겨 화면 문구가 달라진다 —
-- 세 곳을 같은 값으로 유지할 것.
--
-- 이미 REAL-F01 이 들어간 기존 DB 는 이 시드만으로 정리되지 않는다(INSERT IGNORE 는 기존 행을
-- 지우지 않는다). db/migrate-real-f01-to-sim-f01.sql 을 수동으로 실행할 것.
USE fast_backend;

INSERT IGNORE INTO vehicle (vehicle_id, name, active) VALUES
    ('SIM-F01', '시뮬레이션 지게차 1호', TRUE);

INSERT IGNORE INTO vehicle_current_status
    (vehicle_id, status, received_at) VALUES
    ('SIM-F01', 'UNKNOWN', NOW(6));

INSERT IGNORE INTO storage_slot
    (slot_code, usable_height, fork_height, destination_x, destination_y,
     destination_heading, status) VALUES
    ('SLOT-01', 1.00, 0.15, 2.00, 1.00, 180.0, 'EMPTY'),
    ('SLOT-02', 1.50, 0.65, 2.00, 2.00, 180.0, 'EMPTY');
