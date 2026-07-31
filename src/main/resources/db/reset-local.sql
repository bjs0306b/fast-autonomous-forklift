-- =====================================================================================
-- 로컬 개발 MySQL 초기화 — 전체 테이블 DROP (prompt97)
--
-- ⚠️ 이 파일은 **데이터를 전부 지운다.** 로컬 개발 DB 전용이며, 팀 공유/운영 DB에서는 실행하지 않는다.
--
-- 용도: schema.sql 구조가 바뀌었을 때 로컬 DB를 깨끗한 상태로 되돌린다. 이 저장소는 기존 DB를
--       마이그레이션하지 않고 "전부 지우고 다시 만든다"를 정책으로 삼는다
--       (docs/backend-db/database-schema.md "DB 적용 정책").
--
-- 실행 순서:
--   1) reset-local.sql   (이 파일 — DROP)
--   2) schema.sql        (CREATE)
--   3) data-local.sql    (선택 — 로컬 더미 데이터)
--   상세 절차는 docs/backend-db/local-database-reset.md 참고.
--
-- 이 파일은 애플리케이션이 자동 실행하지 않는다. 어떤 yml의 spring.sql.init에도 등록하지 않았다 —
-- 기동만으로 DB가 날아가는 사고를 막기 위한 의도적 결정이다. 반드시 수동으로 실행한다.
--
-- =====================================================================================
-- DROP 순서에 대하여
--
-- FK를 참조하는 쪽(자식)을 먼저 지운다. 아래 순서는 schema.sql의 FK 14개를 역순으로 편 것이라
-- FOREIGN_KEY_CHECKS를 끄지 않아도 그대로 실행된다. 순서를 지키는 편이 낫다 — 체크를 끄고 지우면
-- 순서가 틀렸다는 사실 자체가 드러나지 않아, schema.sql에 FK가 추가돼도 이 파일이 낡은 채로 남는다.
--
-- schema.sql의 FK 관계(참조하는 쪽 -> 참조되는 쪽):
--   transport_command.task_id                  -> transport_task
--   transport_task.cargo_id                    -> cargo
--   transport_task.pallet_id                   -> pallet
--   transport_task.destination_slot_id         -> storage_slot
--   storage_slot.rack_level_id                 -> rack_level
--   rack_level.rack_id                         -> rack
--   pallet.cargo_id                            -> cargo
--   station_measurement_box.station_measurement_id -> station_measurement
--   station_measurement.session_id             -> station_session   (prompt96 신규)
--   station_state.active_session_id            -> station_session   (prompt96 신규)
--   station_session.cargo_id                   -> cargo             (prompt96 신규)
--   ai_cargo_detection_box.analysis_id         -> ai_cargo_analysis
--   vehicle_current_status.vehicle_id          -> vehicle
--   vehicle_status_history.vehicle_id          -> vehicle
--
-- FK가 없는 테이블 4개(embedded_vehicle_command, vehicle_fork_current_status,
-- vehicle_load_safety, embedded_error_history)는 순서와 무관해 마지막에 모아 두었다.
--
-- storage_slot.reserved_task_id / stored_cargo_id는 의미상 transport_task / cargo를 가리키지만
-- schema.sql에 FK로 선언돼 있지 않다(인덱스만 있음). DROP 순서에 영향을 주지 않는다.
-- =====================================================================================

-- 대상 데이터베이스를 명시적으로 고른다. 다른 스키마를 실수로 비우지 않기 위함이다.
-- 접속 시 이미 선택했다면(mysql -D fast_backend) 이 줄은 없어도 된다.
USE fast_backend;


-- ── 1. 운반 (transport) ──────────────────────────────────────────────────────
-- transport_command -> transport_task -> {cargo, pallet, storage_slot}
DROP TABLE IF EXISTS transport_command;
DROP TABLE IF EXISTS transport_task;

-- ── 2. 적재 위치 (storage) ───────────────────────────────────────────────────
-- storage_slot -> rack_level -> rack
DROP TABLE IF EXISTS storage_slot;
DROP TABLE IF EXISTS rack_level;
DROP TABLE IF EXISTS rack;

-- pallet -> cargo. transport_task를 먼저 지운 뒤라야 지울 수 있다.
DROP TABLE IF EXISTS pallet;

-- ── 3. 측정 스테이션 (station) ───────────────────────────────────────────────
-- station_measurement_box -> station_measurement -> station_session
-- station_state -> station_session
--
-- station_state를 station_session보다 먼저 지운다. 활성 세션을 가리키는 FK가 있어
-- 순서를 바꾸면 "Cannot delete or update a parent row" 로 실패한다.
DROP TABLE IF EXISTS station_measurement_box;
DROP TABLE IF EXISTS station_state;
DROP TABLE IF EXISTS station_measurement;
DROP TABLE IF EXISTS station_session;

-- ── 4. 화물 (cargo) ──────────────────────────────────────────────────────────
-- station_session / pallet / transport_task가 모두 참조하므로 그 셋을 지운 뒤에 온다.
DROP TABLE IF EXISTS cargo;

-- ── 5. AI 화물 분석 ──────────────────────────────────────────────────────────
DROP TABLE IF EXISTS ai_cargo_detection_box;
DROP TABLE IF EXISTS ai_cargo_analysis;

-- ── 6. 차량 (vehicle) ────────────────────────────────────────────────────────
DROP TABLE IF EXISTS vehicle_current_status;
DROP TABLE IF EXISTS vehicle_status_history;
DROP TABLE IF EXISTS vehicle;

-- ── 7. FK 없는 테이블 ────────────────────────────────────────────────────────
-- 참조 관계가 없어 순서와 무관하다. vehicle_id를 컬럼으로 갖지만 FK 선언은 없다.
DROP TABLE IF EXISTS embedded_vehicle_command;
DROP TABLE IF EXISTS vehicle_fork_current_status;
DROP TABLE IF EXISTS vehicle_load_safety;
DROP TABLE IF EXISTS embedded_error_history;


-- =====================================================================================
-- 실행 후 확인
--
--   SHOW TABLES;            -- 아무것도 남지 않아야 한다
--
-- 남은 테이블이 있다면 schema.sql에 이 파일이 모르는 테이블이 추가된 것이다.
-- 그때는 이 파일에 DROP을 추가하고 위 FK 목록도 함께 갱신한다.
-- =====================================================================================
