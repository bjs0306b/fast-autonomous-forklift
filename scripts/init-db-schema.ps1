<#
.SYNOPSIS
    MySQL 스키마 최초 적용 스크립트 (Windows / PowerShell)

.DESCRIPTION
    이 프로젝트는 스키마를 자동 생성하지 않는다(Flyway/Liquibase/ddl-auto 미사용).
    application-local.yml 이 spring.sql.init.schema-locations 를 일부러 비워 두었기 때문에,
    관리자가 최초 1회 이 스크립트로 db/schema.sql 을 적용해야 한다.

    안전 원칙
      - 기존 DB 를 DROP 하지 않는다
      - 기존 데이터를 삭제하지 않는다
      - data-local.sql(더미 차량) 은 실행하지 않는다
      - SQL_INIT_MODE 를 건드리지 않는다
      - 비밀번호를 매개변수로 받지 않는다 (mysql 프롬프트 사용)

.EXAMPLE
    $env:DB_HOST='localhost'
    $env:DB_PORT='3306'
    $env:DB_NAME='fast_backend'
    $env:DB_USERNAME='fastuser'
    .\scripts\init-db-schema.ps1

.NOTES
    비대화식으로 돌려야 한다면 $env:MYSQL_PWD 를 쓸 수 있으나 권장하지 않는다.
    같은 호스트의 다른 사용자가 프로세스 환경을 통해 볼 수 있다.
#>

$ErrorActionPreference = 'Stop'

$DbHost     = if ($env:DB_HOST)     { $env:DB_HOST }     else { 'localhost' }
$DbPort     = if ($env:DB_PORT)     { $env:DB_PORT }     else { '3306' }
$DbName     = if ($env:DB_NAME)     { $env:DB_NAME }     else { 'fast_backend' }
$DbUsername = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { '' }

# 스크립트 위치 기준으로 schema.sql 경로 계산
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent $ScriptDir
$SchemaSql = Join-Path $RepoRoot 'src\main\resources\db\schema.sql'

# ─── 사전 확인 ────────────────────────────────────────────────────────────────
$mysqlCmd = Get-Command mysql -ErrorAction SilentlyContinue
if ($null -eq $mysqlCmd) {
    Write-Error "mysql 클라이언트를 찾을 수 없습니다. MySQL client 를 설치하거나 PATH 에 추가하세요."
    exit 1
}

if ([string]::IsNullOrWhiteSpace($DbUsername)) {
    Write-Error "DB_USERNAME 이 비어 있습니다.  예:  `$env:DB_USERNAME='fastuser'"
    exit 1
}

if (-not (Test-Path $SchemaSql)) {
    Write-Error "schema.sql 을 찾을 수 없습니다: $SchemaSql"
    exit 1
}

# ─── 대상 출력 후 명시적 동의 ─────────────────────────────────────────────────
Write-Host ""
Write-Host "==============================================================="
Write-Host " MySQL 스키마 적용"
Write-Host "==============================================================="
Write-Host "  대상 호스트 : ${DbHost}:${DbPort}"
Write-Host "  대상 DB     : $DbName"
Write-Host "  접속 계정   : $DbUsername"
Write-Host "  스키마 파일 : $SchemaSql"
Write-Host ""
Write-Host "  수행 내용"
Write-Host "    1. DB 가 없으면 생성 (CREATE DATABASE IF NOT EXISTS)"
Write-Host "    2. schema.sql 적용 (CREATE TABLE IF NOT EXISTS)"
Write-Host "    3. SHOW TABLES 출력"
Write-Host ""
Write-Host "  수행하지 않는 것"
Write-Host "    - DROP DATABASE / DROP TABLE"
Write-Host "    - DELETE / TRUNCATE"
Write-Host "    - data-local.sql(더미 차량 3대) 삽입"
Write-Host "==============================================================="
Write-Host ""

$confirm = Read-Host "위 대상에 적용합니다. 계속하시겠습니까? [yes/NO]"
if ($confirm -ne 'yes') {
    Write-Host "취소했습니다. 아무것도 변경하지 않았습니다."
    exit 0
}

# MYSQL_PWD 가 있으면 프롬프트를 띄우지 않는다.
$mysqlArgs = @('-h', $DbHost, '-P', $DbPort, '-u', $DbUsername)
if ([string]::IsNullOrWhiteSpace($env:MYSQL_PWD)) {
    $mysqlArgs += '-p'
}

# ─── 1. DB 생성 (없을 때만) ───────────────────────────────────────────────────
Write-Host "[1/3] 데이터베이스 확인·생성: $DbName"
$createDbSql = "CREATE DATABASE IF NOT EXISTS ``$DbName`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
& mysql @mysqlArgs -e $createDbSql
if ($LASTEXITCODE -ne 0) { Write-Error "데이터베이스 생성/확인에 실패했습니다."; exit 1 }

# ─── 2. 스키마 적용 ───────────────────────────────────────────────────────────
Write-Host "[2/3] schema.sql 적용"
Get-Content $SchemaSql -Raw -Encoding UTF8 | & mysql @mysqlArgs $DbName
if ($LASTEXITCODE -ne 0) { Write-Error "schema.sql 적용에 실패했습니다."; exit 1 }

# ─── 3. 결과 확인 ─────────────────────────────────────────────────────────────
Write-Host "[3/3] 적용 결과"
& mysql @mysqlArgs $DbName -e "SHOW TABLES;"
if ($LASTEXITCODE -ne 0) { Write-Error "테이블 조회에 실패했습니다."; exit 1 }

Write-Host ""
Write-Host "완료했습니다."
Write-Host ""
Write-Host "다음 단계"
Write-Host "  - 차량은 자동으로 등록되지 않는다(더미 데이터 삽입을 차단해 두었다)."
Write-Host "    실제 차량은 REST 로 등록한다:  POST /api/vehicles"
Write-Host "  - 비어 있는 것이 정상이다. 아래가 0 이어야 한다:"
Write-Host "      SELECT COUNT(*) FROM vehicle;"
Write-Host ""
