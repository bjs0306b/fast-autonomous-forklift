-- 로컬 DB 초기화용 파일. 실행 전에 fast_backend 스키마를 선택한다.
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS vehicle_command;
DROP TABLE IF EXISTS transport_task;
DROP TABLE IF EXISTS storage_slot;
DROP TABLE IF EXISTS vehicle_current_status;
DROP TABLE IF EXISTS vehicle;
DROP TABLE IF EXISTS station_measurement;
DROP TABLE IF EXISTS station_state;
DROP TABLE IF EXISTS station_session;
DROP TABLE IF EXISTS cargo;
SET FOREIGN_KEY_CHECKS = 1;
