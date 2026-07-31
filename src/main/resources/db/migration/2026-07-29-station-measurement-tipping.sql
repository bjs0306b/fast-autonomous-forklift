-- =============================================================================
-- 운영/공용 MySQL 적용용 마이그레이션 (2026-07-29)
-- 측정 결과 규격 v1.0 → v1.1: 전복 위험(tipping) 필드 저장
-- =============================================================================
--
-- 적용 대상: 이미 이전 버전 schema.sql로 테이블이 생성되어 있는 팀 공용 MySQL.
-- 새로 구축하는 DB라면 이 파일 대신 src/main/resources/db/schema.sql 전체를 실행하면 된다
-- (schema.sql은 이미 아래 변경이 반영된 최신 전체 정의다).
--
-- 이 저장소는 애플리케이션 기동만으로 공용 DB 스키마가 바뀌는 것을 막기 위해 schema.sql을 자동
-- 실행하지 않는다. 이 마이그레이션도 동일하게 **수동 실행 전제**다.
--
-- -----------------------------------------------------------------------------
-- 왜 필요한가
-- -----------------------------------------------------------------------------
-- 스테이션은 2026-07-28부터(MR !75) 측정 payload에 tipping 블록을 실어 보내고 있다.
-- 그런데 station_measurement 테이블은 v1.0 규격으로 만들어져 해당 컬럼이 없고, 백엔드
-- DTO에도 필드가 없다. Spring Boot는 모르는 JSON 필드를 기본으로 무시하므로
-- (FAIL_ON_UNKNOWN_PROPERTIES=false) **측정 메시지는 정상 저장되지만 tipping만 조용히
-- 버려진다.** 에러도 로그도 남지 않아 발견이 늦었다.
--
-- 근본 원인은 규격 버전이다. 필드를 추가하면서 schema_version을 "1.0"에 둔 탓에 소비자
-- (백엔드)가 규격 변경을 감지할 수단이 없었다. 핸드오프 문서를 v1.1로 올리고 변경 이력을
-- 남기는 것을 함께 진행한다(docs/ai/station-measurement-handoff.md).
--
-- -----------------------------------------------------------------------------
-- 컬럼 설계
-- -----------------------------------------------------------------------------
-- tipping_ 접두사를 붙인 이유: payload의 tipping.direction / tipping.message가 기존
-- load_direction / load_message(편하중)와 이름이 겹친다. 접두사가 없으면 두 판정이 섞인다.
--
-- 전 컬럼 nullable: status가 dimensions_only(파렛트 미검출)면 전복 판정 자체가 불가능해
-- assessable=false로 오거나 블록이 비어 온다. 기존 행에도 값이 없다.
--
-- 편하중(load_balance)과 다른 질문에 답한다는 점을 유의할 것 —
--   편하중: 무게중심이 치우쳤나 (파렛트 중심 기준 비율)
--   전복  : 무게중심이 지지면(파렛트)을 벗어났나 (support_offset 1.0 = 물리적 한계)
-- =============================================================================

ALTER TABLE station_measurement
    ADD COLUMN tipping_assessable     BOOLEAN      NULL COMMENT '전복 판정 가능 여부',
    ADD COLUMN tipping_level          VARCHAR(20)  NULL COMMENT 'safe | warning | danger',
    ADD COLUMN tipping_static_stable  BOOLEAN      NULL COMMENT '정지 상태 안정 여부(등급과 구분)',
    ADD COLUMN tipping_support_offset DOUBLE       NULL COMMENT '1.0 = 무게중심이 파렛트 끝',
    ADD COLUMN tipping_margin         DOUBLE       NULL COMMENT '한계까지 남은 여유',
    ADD COLUMN tipping_direction      VARCHAR(20)  NULL COMMENT 'left | right | null',
    ADD COLUMN tipping_aspect_ratio   DOUBLE       NULL COMMENT '화물 종횡비(1.5 이상이면 격상)',
    ADD COLUMN tipping_overhang       DOUBLE       NULL COMMENT '파렛트 밖 돌출 비율',
    ADD COLUMN tipping_message        VARCHAR(500) NULL COMMENT '판정 사유 문구';

-- 확인
-- SHOW COLUMNS FROM station_measurement LIKE 'tipping%';   -- 9행이 나와야 한다

-- -----------------------------------------------------------------------------
-- 롤백
-- -----------------------------------------------------------------------------
-- ALTER TABLE station_measurement
--     DROP COLUMN tipping_assessable,
--     DROP COLUMN tipping_level,
--     DROP COLUMN tipping_static_stable,
--     DROP COLUMN tipping_support_offset,
--     DROP COLUMN tipping_margin,
--     DROP COLUMN tipping_direction,
--     DROP COLUMN tipping_aspect_ratio,
--     DROP COLUMN tipping_overhang,
--     DROP COLUMN tipping_message;
