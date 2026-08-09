-- =============================================================================
-- fork_height 10 배 오류 정정 (13.25 → 1.325) — MySQL 수동 실행 전용
-- =============================================================================
-- 왜
--   seed-rack-slots.sql 이 F팀 8/5 판의 "선반 높이 13.25" 를 그대로 넣었는데, 8/9 개정판
--   backend-mqtt-guide 높이 환산표·nav2/config/rack_slots.csv(place_z)·backend-control-impl
--   의 shelfHeight 예시가 모두 **1.325** 다. 13.25 는 문서상 포크 최대치 1.5 를 8 배 넘는다.
--
--   fork_height 는 task.dropoff.forkHeight 로 **변환 없이 그대로** 나가므로(§4.2),
--   틀린 값이 차량에 직접 전달된다.
--
-- 어디에
--   로컬 DB 와 EC2 운영 DB 양쪽. 이미 24 칸이 13.25 로 들어가 있다.
--
-- 안전성
--   fork_height 한 컬럼만 바꾼다 — status·reserved_task_id·stored_cargo_id 는 건드리지
--   않으므로 진행 중인 예약/적재가 깨지지 않는다. 여러 번 돌려도 결과가 같다.
--
--   대상을 slot_code 3 자리 패턴으로 한정한다. 로컬 테스트 슬롯 SLOT-01/02 는
--   data-local.sql 이 정한 값이 따로 있으므로 제외한다.
--
-- 실행
--   로컬 : mysql -u fastbackend -p fast_backend < src/main/resources/db/fix-fork-height.sql
--   EC2  : sudo docker exec -i fast-mysql sh -c 'mysql -u fastbackend -p"$MYSQL_PASSWORD" fast_backend' < fix-fork-height.sql
-- =============================================================================
USE fast_backend;

-- 바꾸기 전 상태
SELECT fork_height, COUNT(*) AS slots FROM storage_slot GROUP BY fork_height;

UPDATE storage_slot
   SET fork_height = 1.325
 WHERE slot_code REGEXP '^[AB][0-9]{3}$';

-- 기대: 1.325 가 24 건. 13.25 가 남아 있으면 안 된다.
SELECT fork_height, COUNT(*) AS slots FROM storage_slot GROUP BY fork_height;

SELECT slot_code, fork_height, destination_x, destination_y, status
  FROM storage_slot
 WHERE slot_code IN ('A001', 'A012', 'B001', 'B012')
 ORDER BY slot_code;
