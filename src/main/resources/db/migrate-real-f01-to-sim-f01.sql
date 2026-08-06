-- =============================================================================
-- REAL-F01 폐기 → SIM-F01 단일 차량으로 통합
-- =============================================================================
--
-- 무엇을 하는가
--   관제 대상 차량을 `SIM-F01` 한 대로 통합한다. `REAL-F01` 을 참조하던 이력(운반 작업,
--   차량 명령)을 SIM-F01 로 옮기고, 최신 상태 한 행을 병합한 뒤 REAL-F01 차량 행을 지운다.
--
-- 왜 단순 UPDATE 가 아닌가
--   vehicle.vehicle_id 는 PK 이고, DB 에는 REAL-F01 과 SIM-F01 이 **동시에** 존재한다
--   (구 data-local.sql 이 두 행을 모두 시드했다). 그래서 아래 문장은 PK 충돌로 실패한다.
--
--       UPDATE vehicle SET vehicle_id = 'SIM-F01' WHERE vehicle_id = 'REAL-F01';   -- 실패
--
--   vehicle_current_status 도 vehicle_id 가 PK 라 같은 이유로 무조건 UPDATE 할 수 없다.
--   차량당 한 행만 허용되므로 두 행을 **병합**해야 한다.
--
-- vehicle.vehicle_id 를 참조하는 테이블 (schema.sql 기준)
--   ┌─────────────────────────┬───────────────────────────┬──────────────────────────────┐
--   │ 테이블                  │ vehicle_id 제약           │ 충돌 가능성                  │
--   ├─────────────────────────┼───────────────────────────┼──────────────────────────────┤
--   │ vehicle_current_status  │ PK + FK                   │ **있음** — 차량당 1행. 병합  │
--   │ transport_task          │ FK + 일반 인덱스(NULL 허용)│ 없음 — 그대로 UPDATE 가능   │
--   │ vehicle_command         │ FK + 일반 인덱스           │ 없음 — PK 는 command_id      │
--   └─────────────────────────┴───────────────────────────┴──────────────────────────────┘
--   transport_task 의 UNIQUE 제약(task_code, measurement_session_id, measurement_id)에는
--   vehicle_id 가 들어가지 않으므로, 두 차량의 작업이 섞여도 중복 키가 생기지 않는다.
--
-- 실행 방법
--   0) 반드시 백업부터 (아래 "백업" 절)
--   1) 1장 사전 조회를 실행하고 결과를 눈으로 확인한다
--   2) 2장 트랜잭션 블록을 실행한다
--   3) 3장 검증 쿼리로 결과를 확인한다
--
--   mysql -u fastbackend -p fast_backend < src/main/resources/db/migrate-real-f01-to-sim-f01.sql
--
-- 중요
--   이 파일은 **자동 실행되지 않는다**(spring.sql.init 대상이 아니다). 직접 돌려야 한다.
--   1장만 먼저 돌려 보고 싶으면 2장 이후를 주석 처리하거나 1장만 복사해 실행할 것.
--
-- 별칭 매핑을 만들지 않는다
--   애플리케이션에는 REAL-F01 → SIM-F01 런타임 변환을 넣지 않았다. 그런 변환은 정본이
--   무엇인지 영구히 모호하게 만든다. ID 는 송신자·DB·REST·WebSocket 전 구간에서 처음부터
--   SIM-F01 이다.
--
-- 통신에 미치는 영향 (실행 전에 반드시 읽을 것)
--   · ROS2 브리지(ros2_ws/src/fast_mqtt_bridge)는 여전히 forklift/REAL-F01/* 로 발행한다.
--     REAL-F01 이 DB 에서 사라지면 백엔드가 그 메시지를 **폐기**한다 — 의도된 동작이다.
--     실물 지게차를 다시 관제에 올리려면 이 마이그레이션이 아니라 vehicle 시드에 행을
--     다시 추가할 것.
--   · Isaac Sim twin_bridge.py 는 브로커에서 직접 forklift/REAL-F01/location 을 구독해
--     씬의 미러 prim 을 움직인다. 백엔드를 거치지 않으므로 이 마이그레이션의 영향을 받지 않는다.
--   · SIM-F01 발행자는 Isaac twin_bridge.py **하나뿐**이다. ROS2 브리지의 vehicle_id 는
--     REAL-F01 그대로 두었다 — 두 송신자가 같은 ID 로 발행하면 상태가 서로 덮어써진다.
--
-- 백업 (실행 전 필수)
--   mysqldump -u fastbackend -p --single-transaction \
--     --databases fast_backend \
--     > backup-before-real-f01-merge-$(date +%Y%m%d%H%M%S).sql
--
--   되돌리려면:
--   mysql -u fastbackend -p < backup-before-real-f01-merge-<타임스탬프>.sql
--
--   (2장은 트랜잭션이라 COMMIT 전이면 ROLLBACK 으로 되돌릴 수 있지만, COMMIT 이후에는
--    덤프가 유일한 복구 수단이다.)
-- =============================================================================

USE fast_backend;


-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 사전 조회 — 먼저 실행하고 결과를 볼 것
-- ─────────────────────────────────────────────────────────────────────────────

-- 두 차량이 모두 있는가? (2행이면 병합 필요, 1행이면 해당 행만 처리하면 된다)
SELECT vehicle_id, name, active, created_at, updated_at
FROM vehicle
WHERE vehicle_id IN ('REAL-F01', 'SIM-F01')
ORDER BY vehicle_id;

-- 최신 상태 행. received_at 을 비교해 어느 쪽을 살릴지 판단한다.
SELECT vehicle_id, status, position_x, position_y, position_frame,
       heading, speed, has_cargo, cargo_id, message_at, received_at
FROM vehicle_current_status
WHERE vehicle_id IN ('REAL-F01', 'SIM-F01')
ORDER BY vehicle_id;

-- 옮겨야 할 참조 건수.
SELECT COUNT(*) AS transport_task_count
FROM transport_task
WHERE vehicle_id = 'REAL-F01';

SELECT COUNT(*) AS vehicle_command_count
FROM vehicle_command
WHERE vehicle_id = 'REAL-F01';

-- 혹시 위 3개 말고 vehicle 을 참조하는 FK 가 더 있는지 확인한다.
-- (스키마가 바뀌었는데 이 파일이 갱신되지 않았다면 여기서 드러난다)
SELECT TABLE_NAME, COLUMN_NAME, CONSTRAINT_NAME
FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = DATABASE()
  AND REFERENCED_TABLE_NAME = 'vehicle'
ORDER BY TABLE_NAME;


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. 이전 + 병합 (트랜잭션)
--
--    순서가 중요하다. 자식 테이블의 참조를 모두 옮긴 뒤에야 부모(vehicle) 행을 지울 수 있다.
--    중간에 이상하면 COMMIT 대신 ROLLBACK 을 실행할 것.
-- ─────────────────────────────────────────────────────────────────────────────

START TRANSACTION;

-- 2-0. 정본 차량 보장. SIM-F01 이 없는 DB(REAL-F01 만 있는 경우)에서도 아래 UPDATE 들이
--      FK 위반 없이 돌도록 먼저 만들어 둔다.
INSERT IGNORE INTO vehicle (vehicle_id, name, active)
VALUES ('SIM-F01', '시뮬레이션 지게차 1호', TRUE);

-- 2-1. 운반 작업 이력 이전. vehicle_id 에 UNIQUE 가 없어 그대로 옮겨도 충돌하지 않는다.
UPDATE transport_task
SET vehicle_id = 'SIM-F01'
WHERE vehicle_id = 'REAL-F01';

-- 2-2. 명령 이력 이전. PK 는 command_id 라 중복이 생기지 않는다.
--      중복 명령 이력은 **삭제하지 않고 모두 남긴다** — 어떤 명령이 언제 나갔는지는
--      사후 분석에 필요한 원본 기록이고, vehicle_id 만 바뀌어도 의미가 보존된다.
UPDATE vehicle_command
SET vehicle_id = 'SIM-F01'
WHERE vehicle_id = 'REAL-F01';

-- 2-3. 최신 상태 병합.
--      차량당 한 행만 허용되므로 UPDATE 로 옮길 수 없다. REAL 쪽이 더 최신일 때만
--      값을 SIM 행에 복사한다. SIM 쪽이 최신이면 아무것도 하지 않는다.
UPDATE vehicle_current_status AS sim
JOIN vehicle_current_status AS real_row
  ON real_row.vehicle_id = 'REAL-F01'
SET sim.status         = real_row.status,
    sim.position_x     = real_row.position_x,
    sim.position_y     = real_row.position_y,
    sim.position_frame = real_row.position_frame,
    sim.heading        = real_row.heading,
    sim.speed          = real_row.speed,
    sim.has_cargo      = real_row.has_cargo,
    sim.cargo_id       = real_row.cargo_id,
    sim.message_at     = real_row.message_at,
    sim.received_at    = real_row.received_at
WHERE sim.vehicle_id = 'SIM-F01'
  AND real_row.received_at > sim.received_at;

-- SIM 행 자체가 없으면(REAL 만 있던 DB) REAL 행의 값을 그대로 옮겨 심는다.
INSERT INTO vehicle_current_status
    (vehicle_id, status, position_x, position_y, position_frame,
     heading, speed, has_cargo, cargo_id, message_at, received_at)
SELECT 'SIM-F01', status, position_x, position_y, position_frame,
       heading, speed, has_cargo, cargo_id, message_at, received_at
FROM vehicle_current_status
WHERE vehicle_id = 'REAL-F01'
  AND NOT EXISTS (
      SELECT 1 FROM (SELECT vehicle_id FROM vehicle_current_status) AS existing
      WHERE existing.vehicle_id = 'SIM-F01');

-- 병합이 끝났으니 REAL 상태 행을 지운다. vehicle 을 지우기 전에 반드시 먼저 실행해야 한다
-- (fk_vehicle_current_status_vehicle 때문에 순서를 바꾸면 실패한다).
DELETE FROM vehicle_current_status
WHERE vehicle_id = 'REAL-F01';

-- 2-4. 모든 참조가 옮겨진 뒤에만 차량 행을 지운다.
--      남은 참조가 있으면 FK 위반으로 여기서 실패한다 — 그때는 ROLLBACK 하고
--      1장의 FK 조회 결과에 빠진 테이블이 없는지 다시 볼 것.
DELETE FROM vehicle
WHERE vehicle_id = 'REAL-F01';

-- 2-5. 정본 차량의 표시 이름·활성 여부 통일.
--      INSERT IGNORE 는 기존 행의 name 을 고치지 못하므로 UPDATE 가 따로 필요하다.
UPDATE vehicle
SET name = '시뮬레이션 지게차 1호',
    active = TRUE
WHERE vehicle_id = 'SIM-F01';

COMMIT;
-- 문제가 있으면 COMMIT 대신: ROLLBACK;


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. 검증 — 실행 후 확인
-- ─────────────────────────────────────────────────────────────────────────────

-- 기대: SIM-F01 1행만. name = '시뮬레이션 지게차 1호', active = 1
SELECT vehicle_id, name, active
FROM vehicle
ORDER BY active DESC, vehicle_id;

-- 기대: 0
SELECT COUNT(*) AS remaining_real_vehicle
FROM vehicle
WHERE vehicle_id = 'REAL-F01';

-- 기대: 모두 0
SELECT
    (SELECT COUNT(*) FROM vehicle_current_status WHERE vehicle_id = 'REAL-F01')
        AS remaining_status,
    (SELECT COUNT(*) FROM transport_task        WHERE vehicle_id = 'REAL-F01')
        AS remaining_task,
    (SELECT COUNT(*) FROM vehicle_command       WHERE vehicle_id = 'REAL-F01')
        AS remaining_command;

-- 기대: SIM-F01 1행. received_at 이 병합 전 두 행 중 더 최신 값이어야 한다.
SELECT vehicle_id, status, position_x, position_y, heading, received_at
FROM vehicle_current_status
ORDER BY vehicle_id;

-- 기대: 대시보드 API(/api/monitoring/dashboard)가 SIM-F01 한 대만 반환한다.
SELECT vehicle_id, name
FROM vehicle
WHERE active = TRUE
ORDER BY vehicle_id;
