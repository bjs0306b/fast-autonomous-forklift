# 로컬 개발 DB 초기화

이 절차는 데이터 보존이 필요 없는 로컬·MVP 개발 DB 전용이다. 공유 또는 운영 DB에는 실행하지 않는다.

## 기준 파일

| 파일 | 역할 |
|---|---|
| `src/main/resources/db/reset-local.sql` | 기존 MVP 테이블 삭제 |
| `src/main/resources/db/schema.sql` | 정본 스키마 생성 |
| `src/main/resources/db/data-local.sql` | 선택적 차량·적재 위치 샘플 |

애플리케이션 기본 설정은 스키마를 자동 생성하지 않는다. MySQL Workbench나 CLI에서 직접 실행한다.

## 실행 순서

```text
reset-local.sql → schema.sql → data-local.sql(선택)
```

MySQL Workbench에서는 `fast_backend` 스키마를 기본 스키마로 선택한 다음 각 파일을 순서대로 전체 실행한다.

CLI 예시:

```bash
mysql -u <user> -p -D fast_backend < src/main/resources/db/reset-local.sql
mysql -u <user> -p -D fast_backend < src/main/resources/db/schema.sql
mysql -u <user> -p -D fast_backend < src/main/resources/db/data-local.sql
```

## 생성되는 테이블

정본 스키마는 다음 9개 테이블을 만든다.

```text
cargo
station_session
station_state
station_measurement
vehicle
vehicle_current_status
storage_slot
transport_task
vehicle_command
```

확인 쿼리:

```sql
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = 'fast_backend';

SELECT * FROM station_state;
```

`station_state`에는 `(singleton_id=1, active_session_id=NULL, acquired_at=NULL)` 한 행이 있어야 한다.

## 주의

- `reset-local.sql`은 테이블을 삭제하므로 실행 전 대상 스키마를 반드시 확인한다.
- DB 자체와 계정은 삭제하지 않는다.
- 현재 MVP는 마이그레이션을 운영하지 않고 정본 스키마로 재생성한다.
- EC2 등 공유 환경에서 실행하려면 팀원이 데이터 삭제에 동의한 경우에만 진행한다.
