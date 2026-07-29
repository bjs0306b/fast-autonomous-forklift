#!/usr/bin/env bash
# =============================================================================
# MySQL 스키마 최초 적용 스크립트
# =============================================================================
#
# 이 프로젝트는 스키마를 자동 생성하지 않는다(Flyway/Liquibase/ddl-auto 미사용).
# application-local.yml 이 spring.sql.init.schema-locations 를 일부러 비워 두었기 때문에,
# 관리자가 최초 1회 이 스크립트로 db/schema.sql 을 적용해야 한다.
#
# 안전 원칙
#   - 기존 DB 를 DROP 하지 않는다
#   - 기존 데이터를 삭제하지 않는다 (schema.sql 은 CREATE TABLE IF NOT EXISTS 만 사용)
#   - data-local.sql(더미 차량) 은 실행하지 않는다
#   - SQL_INIT_MODE 를 건드리지 않는다
#   - 비밀번호를 명령행 인자로 받지 않는다
#
# 사용법
#   DB_HOST=localhost DB_PORT=3306 DB_NAME=fast_backend DB_USERNAME=fastuser \
#     ./scripts/init-db-schema.sh
#
# 비밀번호는 mysql 클라이언트가 프롬프트로 물어본다(-p).
# CI 등 비대화식 환경에서는 MYSQL_PWD 를 쓸 수 있으나 권장하지 않는다:
#   - MYSQL_PWD 는 같은 호스트의 다른 사용자가 ps/environ 으로 볼 수 있다
#   - 꼭 써야 한다면 아래처럼 history 에 남기지 않는다
#       set +o history; export MYSQL_PWD='...'; set -o history
# =============================================================================

set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-fast_backend}"
DB_USERNAME="${DB_USERNAME:-}"

# 스크립트 위치 기준으로 schema.sql 경로를 계산한다(어느 디렉터리에서 실행해도 동작).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SCHEMA_SQL="${REPO_ROOT}/src/main/resources/db/schema.sql"

# ─── 사전 확인 ────────────────────────────────────────────────────────────────
if ! command -v mysql >/dev/null 2>&1; then
  echo "ERROR: mysql 클라이언트를 찾을 수 없습니다. MySQL client 를 설치하세요." >&2
  exit 1
fi

if [[ -z "${DB_USERNAME}" ]]; then
  echo "ERROR: DB_USERNAME 이 비어 있습니다." >&2
  echo "  예: DB_USERNAME=fastuser ./scripts/init-db-schema.sh" >&2
  exit 1
fi

if [[ ! -f "${SCHEMA_SQL}" ]]; then
  echo "ERROR: schema.sql 을 찾을 수 없습니다: ${SCHEMA_SQL}" >&2
  exit 1
fi

# ─── 대상 출력 후 명시적 동의 ─────────────────────────────────────────────────
cat <<EOF

===============================================================
 MySQL 스키마 적용
===============================================================
  대상 호스트 : ${DB_HOST}:${DB_PORT}
  대상 DB     : ${DB_NAME}
  접속 계정   : ${DB_USERNAME}
  스키마 파일 : ${SCHEMA_SQL}

  수행 내용
    1. DB 가 없으면 생성 (CREATE DATABASE IF NOT EXISTS)
    2. schema.sql 적용 (CREATE TABLE IF NOT EXISTS)
    3. SHOW TABLES 출력

  수행하지 않는 것
    - DROP DATABASE / DROP TABLE
    - DELETE / TRUNCATE
    - data-local.sql(더미 차량 3대) 삽입
===============================================================

EOF

read -r -p "위 대상에 적용합니다. 계속하시겠습니까? [yes/NO]: " CONFIRM
if [[ "${CONFIRM}" != "yes" ]]; then
  echo "취소했습니다. 아무것도 변경하지 않았습니다."
  exit 0
fi

MYSQL_OPTS=(-h "${DB_HOST}" -P "${DB_PORT}" -u "${DB_USERNAME}")
# MYSQL_PWD 가 설정돼 있으면 mysql 이 알아서 사용하므로 -p 프롬프트를 띄우지 않는다.
if [[ -z "${MYSQL_PWD:-}" ]]; then
  MYSQL_OPTS+=(-p)
fi

# ─── 1. DB 생성 (없을 때만) ───────────────────────────────────────────────────
echo "[1/3] 데이터베이스 확인·생성: ${DB_NAME}"
mysql "${MYSQL_OPTS[@]}" -e \
  "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# ─── 2. 스키마 적용 ───────────────────────────────────────────────────────────
echo "[2/3] schema.sql 적용"
mysql "${MYSQL_OPTS[@]}" "${DB_NAME}" < "${SCHEMA_SQL}"

# ─── 3. 결과 확인 ─────────────────────────────────────────────────────────────
echo "[3/3] 적용 결과"
mysql "${MYSQL_OPTS[@]}" "${DB_NAME}" -e "SHOW TABLES;"

cat <<EOF

완료했습니다.

다음 단계
  - 차량은 자동으로 등록되지 않는다(더미 데이터 삽입을 차단해 두었다).
    실제 차량은 REST 로 등록한다:
      POST /api/vehicles

  - 비어 있는 것이 정상이다. 아래가 0 이어야 한다:
      SELECT COUNT(*) FROM vehicle;

EOF
