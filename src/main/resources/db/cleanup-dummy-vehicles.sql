-- =============================================================================
-- 더미 차량(FORKLIFT-*) 정리 + 차량 표시 이름 통일
-- =============================================================================
--
-- 왜 필요한가
--   data-local.sql 에서 FORKLIFT-01/02 시드를 제거해도 **이미 DB 에 들어간 행은 남는다**.
--   MonitoringService 는 active=true 인 차량을 전부 내려주므로(VehicleMapper.findAllActive),
--   그 행이 남아 있는 한 관제 화면에는 실제 장비가 없는 더미가 계속 보인다.
--
-- 실행 방법
--   mysql -u fastbackend -p fast_backend < src/main/resources/db/cleanup-dummy-vehicles.sql
--
-- 중요
--   이 파일은 **자동 실행되지 않는다**(SQL_INIT_MODE 대상이 아니다). 직접 돌려야 한다.
--   1장을 먼저 실행해 결과를 보고, 그 결과에 따라 2-A 또는 2-B **하나만** 실행할 것.
--
-- 차량 ID 규칙 (변환하지 않는다 — 별칭 매핑을 만들면 정본이 무엇인지 영구히 모호해진다)
--   SIM-F01 = Isaac Sim 시뮬레이션 차량 (twin_bridge.py SIM_ID). **관제 대상은 이 한 대뿐이다.**
--   실물/시뮬 구분은 별도 컬럼이 아니라 **ID 접두어**로 한다.
--
--   REAL-F01 은 이 파일의 대상이 아니다. 실물 지게차 연동을 관제 목록에서 제외하면서
--   전용 마이그레이션으로 옮겼다 — db/migrate-real-f01-to-sim-f01.sql 을 쓸 것.
--   (FK 참조와 vehicle_current_status 병합을 다뤄야 해서 단순 DELETE 로는 안 된다.)
-- =============================================================================

USE fast_backend;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 현재 상태 확인 (먼저 실행하고 결과를 볼 것)
-- ─────────────────────────────────────────────────────────────────────────────

SELECT vehicle_id, name, active
FROM vehicle
ORDER BY vehicle_id;

-- 이 값이 0 이면 2-A(삭제), 0 이 아니면 2-B(비활성화)를 쓴다.
-- transport_task.vehicle_id 에는 FK 가 걸려 있어(fk_transport_task_vehicle),
-- 참조가 남은 채로 DELETE 하면 실패한다.
SELECT COUNT(*) AS transport_task_count
FROM transport_task
WHERE vehicle_id LIKE 'FORKLIFT-%';

-- 명령 이력도 같은 이유로 확인한다.
SELECT COUNT(*) AS vehicle_command_count
FROM vehicle_command
WHERE vehicle_id LIKE 'FORKLIFT-%';


-- ─────────────────────────────────────────────────────────────────────────────
-- 2-A. 참조가 없을 때 — 삭제
--      vehicle_current_status 가 vehicle 을 FK 로 참조하므로 상태 행을 먼저 지운다.
-- ─────────────────────────────────────────────────────────────────────────────

-- DELETE FROM vehicle_current_status WHERE vehicle_id LIKE 'FORKLIFT-%';
-- DELETE FROM vehicle               WHERE vehicle_id LIKE 'FORKLIFT-%';


-- ─────────────────────────────────────────────────────────────────────────────
-- 2-B. 참조가 있을 때 — 이력을 보존하고 목록에서만 감춘다
--      findAllActive 가 active=true 만 조회하므로 이것만으로 관제 화면에서 사라진다.
-- ─────────────────────────────────────────────────────────────────────────────

-- UPDATE vehicle SET active = FALSE WHERE vehicle_id LIKE 'FORKLIFT-%';


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. 표시 이름 통일 (이미 등록된 행 대상)
--    INSERT IGNORE 시드는 기존 행의 name 을 고치지 못한다. 영어/한글 이름이 섞이는 것을
--    막으려면 이 UPDATE 가 필요하다.
-- ─────────────────────────────────────────────────────────────────────────────

UPDATE vehicle SET name = '시뮬레이션 지게차 1호' WHERE vehicle_id = 'SIM-F01';


-- ─────────────────────────────────────────────────────────────────────────────
-- 4. 결과 확인 (기대: SIM-F01 만 active=1)
-- ─────────────────────────────────────────────────────────────────────────────

SELECT vehicle_id, name, active
FROM vehicle
ORDER BY active DESC, vehicle_id;
