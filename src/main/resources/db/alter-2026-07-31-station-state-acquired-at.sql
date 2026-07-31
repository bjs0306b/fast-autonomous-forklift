-- =====================================================================================
-- station_state.acquired_at 추가 (prompt106 — 측정 세션 TTL 자동 해제)
--
-- 이 저장소에는 마이그레이션 체계가 없다(migration 폴더 없음, "DB 초기화 후 schema.sql 재생성"이
-- 기본 정책 — docs/backend-db/database-schema.md "DB 적용 정책"). 따라서 이 파일은
-- **이미 만들어져 있는 로컬 DB 를 지우지 않고** 컬럼만 더하려는 경우를 위한 수동 실행용이다.
--
-- 새로 만드는 DB 는 이 파일이 필요 없다 — schema.sql 에 이미 컬럼이 포함돼 있다.
--
-- ⚠️ 어떤 yml 에서도 자동 실행하지 않는다. 기동만으로 스키마가 바뀌는 것을 막기 위한 기존 정책을
--    그대로 따른다. 반드시 수동으로 실행한다.
--
-- 실행:
--   mysql -u <user> -p -D fast_backend < src/main/resources/db/alter-2026-07-31-station-state-acquired-at.sql
--
-- (schema.sql 의 USE 가 주석 처리돼 있는 것과 같은 이유로 여기서도 USE 를 쓰지 않는다 —
--  접속 시 -D fast_backend 로 데이터베이스를 고른다.)
-- =====================================================================================

-- MySQL 8.0 은 ADD COLUMN IF NOT EXISTS 를 지원하지 않는다. 이미 컬럼이 있으면
-- "Duplicate column name 'acquired_at'" 오류가 나는데, 그 경우 **이미 적용된 것이므로 무시하면 된다.**
ALTER TABLE station_state
    ADD COLUMN acquired_at DATETIME(6) NULL
    COMMENT '현재 세션을 점유한 시각. TTL 만료 판정 기준'
    AFTER active_session_id;

-- 기존 행 처리
--   이 컬럼이 없던 시절에 점유된 세션은 acquired_at 이 NULL 로 남는다.
--   백엔드는 acquired_at IS NULL 을 **만료로 간주**해 회수한다(answer106.md 3장 근거 참고).
--   즉 별도의 백필(UPDATE)이 필요 없다 — 다음 openSession 이 스스로 정리한다.
--
--   만약 배포 시점에 "지금 진행 중인 정상 세션"을 보호하고 싶다면, 아래를 실행해
--   현재 시각으로 채워 TTL 을 새로 시작시킬 수 있다(선택 사항).
--
-- UPDATE station_state
--    SET acquired_at = CURRENT_TIMESTAMP(6)
--  WHERE singleton_id = 1
--    AND active_session_id IS NOT NULL
--    AND acquired_at IS NULL;

-- 적용 확인
--   SHOW COLUMNS FROM station_state LIKE 'acquired_at';
--   SELECT singleton_id, active_session_id, acquired_at FROM station_state;
