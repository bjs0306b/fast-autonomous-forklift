# 로컬 개발 DB 초기화 절차

> 대상: **로컬 개발용 MySQL** 전용. 팀 공유 DB·운영 DB에서는 실행하지 않는다.
> 기준: `src/main/resources/db/schema.sql` (prompt96 반영 상태, 테이블 20개)

## 1. 왜 "지우고 다시 만드는가"

이 저장소는 **기존 DB 업그레이드를 지원하지 않는다**(`docs/backend-db/database-schema.md` "DB 적용 정책").
migration 폴더도 두지 않는다. 스키마가 바뀌면 로컬 DB를 비우고 `schema.sql`로 전체를 재생성한다.

또한 **애플리케이션은 스키마를 자동 생성하지 않는다.** `application-local.yml`의
`spring.sql.init.schema-locations`를 일부러 비워 두었다 — 기동 한 번으로 공유 DB의 테이블이
바뀌는 것을 막기 위한 의도적 결정이다. 따라서 아래 절차는 **전부 수동 실행**이다.

## 2. 파일 세 개의 역할

| 파일 | 역할 | 자동 실행 여부 |
|---|---|---|
| `src/main/resources/db/reset-local.sql` | 전체 테이블 `DROP` (FK 역순) | ❌ 수동 전용 |
| `src/main/resources/db/schema.sql` | 전체 테이블 `CREATE` | ❌ local 프로필에서는 수동. 단 **test/mqttcheck 프로필은 H2에 자동 적용** |
| `src/main/resources/db/data-local.sql` | 로컬 더미 데이터(차량 3대 + 상태) | ⚠️ `SQL_INIT_MODE=always`일 때만 |

`reset-local.sql`은 **어떤 yml의 `spring.sql.init`에도 등록하지 않았다.** 기동만으로 DB가 날아가는
사고를 막기 위해서다.

## 3. 실행 순서

```
reset-local.sql   →   schema.sql   →   data-local.sql(선택)
   (DROP)              (CREATE)          (더미 데이터)
```

### 3.1 명령줄에서

```bash
# 0) (최초 1회) 데이터베이스가 없으면 만든다
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS fast_backend \
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 1) 전체 테이블 DROP
mysql -u fastbackend -p -D fast_backend < src/main/resources/db/reset-local.sql

# 2) 전체 테이블 CREATE
mysql -u fastbackend -p -D fast_backend < src/main/resources/db/schema.sql

# 3) (선택) 로컬 더미 데이터
mysql -u fastbackend -p -D fast_backend < src/main/resources/db/data-local.sql
```

`-D fast_backend`로 데이터베이스를 지정하는 점이 중요하다 — 아래 4장 참고.

### 3.2 MySQL Workbench / DBeaver 등 GUI에서

1. `fast_backend` 스키마를 선택(더블클릭)한 상태에서
2. `reset-local.sql` 열어 전체 실행
3. `schema.sql` 열어 전체 실행
4. (선택) `data-local.sql` 열어 전체 실행

## 4. `schema.sql`을 수동 실행할 때 주의 — `USE` 가 주석 처리돼 있다

`schema.sql` 안의 `USE fast_backend;` 두 줄은 **주석 처리돼 있다.** H2(MySQL 호환 모드)가 `USE`를
지원하지 않아, 이 문장이 살아 있으면 `mvnw test`에서 스키마 초기화가 통째로 실패하기 때문이다
(이 파일은 test 프로필에서 H2에도 그대로 적용된다).

따라서 실 MySQL에 수동 실행할 때는 **접속 시 데이터베이스를 직접 골라야 한다**:

- CLI: `mysql -D fast_backend ...` (위 명령 예시대로)
- GUI: 실행 전에 `fast_backend` 스키마를 선택
- 또는 `schema.sql`의 `-- USE fast_backend;` 주석을 일시적으로 해제

같은 이유로 `schema.sql` 말미의 **MySQL 운영자용 블록**(`SHOW TABLES`, `mysql.user` 조회,
`CREATE USER`, `GRANT`, `FLUSH PRIVILEGES`)도 전부 주석 처리돼 있다. 계정을 새로 만들어야 할 때만
그 블록의 주석을 풀어 수동 실행한다. (비밀번호가 평문이므로 실제 운영 계정에는 그대로 쓰지 않는다.)

`reset-local.sql`에는 `USE fast_backend;`가 **살아 있다** — 이 파일은 H2에서 실행되지 않고
로컬 MySQL 전용이며, 대상을 명시해 다른 스키마를 실수로 비우지 않게 하는 편이 안전하기 때문이다.

## 5. 테이블 20개와 DROP 순서

develop 기준 18개 + prompt96에서 추가한 `station_session`·`station_state` = **20개**.
`reset-local.sql`은 FK를 참조하는 쪽(자식)부터 지우므로 `FOREIGN_KEY_CHECKS`를 끄지 않아도 된다.

| # | DROP 순서 | 참조하는 대상 |
|---:|---|---|
| 1 | `transport_command` | transport_task |
| 2 | `transport_task` | cargo, pallet, storage_slot |
| 3 | `storage_slot` | rack_level |
| 4 | `rack_level` | rack |
| 5 | `rack` | — |
| 6 | `pallet` | cargo |
| 7 | `station_measurement_box` | station_measurement |
| 8 | `station_state` | station_session |
| 9 | `station_measurement` | station_session |
| 10 | `station_session` | cargo |
| 11 | `cargo` | — |
| 12 | `ai_cargo_detection_box` | ai_cargo_analysis |
| 13 | `ai_cargo_analysis` | — |
| 14 | `vehicle_current_status` | vehicle |
| 15 | `vehicle_status_history` | vehicle |
| 16 | `vehicle` | — |
| 17~20 | `embedded_vehicle_command`, `vehicle_fork_current_status`, `vehicle_load_safety`, `embedded_error_history` | FK 없음 |

> **`station_state`를 `station_session`보다 먼저 지운다.** `station_state.active_session_id`가
> 세션을 FK로 참조하므로 순서를 바꾸면 부모 행 삭제 실패로 막힌다.

`storage_slot.reserved_task_id` / `stored_cargo_id`는 의미상 `transport_task` / `cargo`를 가리키지만
schema.sql에 **FK로 선언돼 있지 않다**(인덱스만 존재). DROP 순서에 영향을 주지 않는다.

## 6. 실행 후 확인

```sql
USE fast_backend;

-- 1) reset 직후: 아무것도 남지 않아야 한다
SHOW TABLES;

-- 2) schema.sql 실행 후: 20개
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'fast_backend';

-- 3) prompt96 신규 구조 확인
SHOW TABLES LIKE 'station\_%';           -- station_measurement, station_measurement_box,
                                          -- station_session, station_state
DESCRIBE station_session;                 -- session_id, cargo_id, created_at
DESCRIBE station_state;                   -- singleton_id, active_session_id
SELECT * FROM station_state;              -- singleton_id=1, active_session_id=NULL 한 행

-- 4) station_measurement 신규 컬럼(prompt96)
SHOW COLUMNS FROM station_measurement LIKE 'session_id';
SHOW COLUMNS FROM station_measurement LIKE 'cargo_height';      -- meter, 화물만
SHOW COLUMNS FROM station_measurement LIKE 'overhang_ratio';    -- 무차원 비율

-- 5) 신규 제약
SELECT constraint_name, constraint_type
  FROM information_schema.table_constraints
 WHERE table_schema = 'fast_backend' AND table_name = 'station_measurement';
-- uk_station_measurement_measurement_id (UNIQUE)
-- uk_station_measurement_session        (UNIQUE, 세션당 최종 측정 1건)
-- fk_station_measurement_session        (FOREIGN KEY)
-- chk_station_measurement_cargo_height  (CHECK, NULL 또는 > 0)
-- chk_station_measurement_overhang_ratio(CHECK, NULL 또는 >= 0)
```

`station_state`에 `(1, NULL)` 한 행이 있어야 세션 API가 동작한다. `schema.sql`이 `INSERT IGNORE`로
넣으므로 재실행해도 중복되지 않는다.

## 7. 애플리케이션 기동

```bash
# 더미 데이터 없이
./mvnw.cmd spring-boot:run

# data-local.sql 더미 차량 3대까지 넣고 싶을 때
SQL_INIT_MODE=always ./mvnw.cmd spring-boot:run
```

`SQL_INIT_MODE`의 기본값은 `never`다. 이 프로필이 서버에서 그대로 돌 때 존재하지 않는 차량 3대가
관제 화면에 나타나는 것을 막기 위한 기본값이며, 로컬에서만 `always`로 켠다.
`data-local.sql`은 `INSERT IGNORE`라 반복 실행해도 안전하다.

## 8. 하지 않는 것

- **실제 DB에 자동 실행**: 어떤 프로필도 `reset-local.sql`을 실행하지 않는다
- **`DROP DATABASE`**: 데이터베이스 자체는 지우지 않는다(권한·문자셋 설정이 날아간다). 테이블만 지운다
- **migration 파일**: 만들지 않는다. 스키마 변경은 재생성으로 처리한다
- **`schema.sql` 테이블 축소**: develop 18개 + prompt96 신규 2개를 모두 유지한다
