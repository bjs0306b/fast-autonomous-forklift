<#
.SYNOPSIS
    로컬 개발 환경 점검 (Windows / PowerShell, 읽기 전용)

.DESCRIPTION
    시스템을 변경하지 않는다.
      - 서비스 시작/종료 없음
      - Docker 컨테이너 변경 없음
      - 방화벽 변경 없음
      - 파일 생성/수정 없음
      - MQTT 발행 없음

    비밀번호·인증정보는 출력하지 않는다(설정 여부만 표시).

.EXAMPLE
    .\scripts\check-local-env.ps1
#>

# 도구가 없어도 끝까지 점검해야 하므로 Stop 으로 두지 않는다.
$ErrorActionPreference = 'Continue'

$script:OkCount      = 0
$script:MissingCount = 0
$script:NotListening = 0

function Write-Header([string]$Text) {
    Write-Host ""
    Write-Host "-- $Text --------------------------------------" -ForegroundColor Cyan
}
function Write-Ok([string]$Text)      { Write-Host "  [OK]            $Text";            $script:OkCount++ }
function Write-Missing([string]$Text) { Write-Host "  [MISSING]       $Text" -ForegroundColor Yellow; $script:MissingCount++ }
function Write-NoListen([string]$Text){ Write-Host "  [NOT LISTENING] $Text" -ForegroundColor Yellow; $script:NotListening++ }
function Write-Info([string]$Text)    { Write-Host "  $Text" }

function Test-Tool([string]$Name, [string[]]$VersionArgs) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $cmd) {
        Write-Missing "$Name (설치되지 않음 또는 PATH 에 없음)"
        return
    }
    try {
        $out = & $Name @VersionArgs 2>&1 | Select-Object -First 1
        Write-Ok "$Name — $out"
    } catch {
        Write-Ok "$Name (버전 확인 실패, 실행 파일은 존재)"
    }
}

function Test-Port([int]$Port, [string]$Label) {
    try {
        $conn = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop
        if ($conn) { Write-Ok "$Port LISTEN ($Label)" } else { Write-NoListen "$Port ($Label)" }
    } catch {
        Write-NoListen "$Port ($Label)"
    }
}

Write-Host "===== 로컬 개발 환경 점검 (읽기 전용) =====" -ForegroundColor White
Write-Host ("실행 시각: " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

Write-Header "OS / 호스트"
Write-Info ("OS       : " + (Get-CimInstance Win32_OperatingSystem).Caption)
Write-Info ("hostname : " + $env:COMPUTERNAME)
$ips = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        Select-Object -ExpandProperty IPAddress) -join ', '
if ([string]::IsNullOrWhiteSpace($ips)) { $ips = '확인 불가' }
Write-Info "IP       : $ips"

Write-Header "현재 사용자 / 경로"
Write-Info "whoami   : $env:USERNAME"
Write-Info "pwd      : $((Get-Location).Path)"

Write-Header "런타임 도구"
Test-Tool 'java'   @('-version')
Test-Tool 'mvn'    @('-v')
Test-Tool 'node'   @('-v')
Test-Tool 'npm'    @('-v')
Test-Tool 'docker' @('--version')
Test-Tool 'mysql'  @('--version')
Test-Tool 'conda'  @('--version')

# Maven Wrapper 존재 확인 (이 저장소 기준)
Write-Header "Maven Wrapper"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent $ScriptDir
foreach ($f in @('mvnw', 'mvnw.cmd', '.mvn\wrapper\maven-wrapper.properties')) {
    $p = Join-Path $RepoRoot $f
    if (Test-Path $p) { Write-Ok $f } else { Write-Missing $f }
}

Write-Header "포트 LISTEN 확인"
Test-Port 1883 'Mosquitto'
Test-Port 3306 'MySQL'
Test-Port 8080 'Spring Boot'
Test-Port 3000 'Next.js'

Write-Header "관련 프로세스"
foreach ($p in @('mosquitto', 'mysqld', 'java', 'node')) {
    $n = @(Get-Process -Name $p -ErrorAction SilentlyContinue).Count
    if ($n -gt 0) { Write-Info "${p}: $n개 실행 중" } else { Write-Info "${p}: 실행 중 아님" }
}

Write-Header "환경변수 설정 여부 (값은 출력하지 않음)"
$vars = @('DB_URL','DB_USERNAME','DB_PASSWORD',
          'MQTT_ENABLED','MQTT_BROKER_URL','MQTT_USERNAME','MQTT_PASSWORD',
          'CORS_ALLOWED_ORIGINS','WEBSOCKET_ALLOWED_ORIGIN_PATTERNS',
          'SQL_INIT_MODE','MQTT_TEST_API_ENABLED','VEHICLE_STATUS_TEST_API_ENABLED')
foreach ($v in $vars) {
    $val = [Environment]::GetEnvironmentVariable($v)
    if ([string]::IsNullOrWhiteSpace($val)) {
        Write-Info "${v}: 미설정 (코드 기본값 사용)"
    }
    elseif ($v -like '*PASSWORD*') {
        Write-Info "${v}: 설정됨 (값 미출력)"
    }
    elseif ($v -in @('SQL_INIT_MODE','MQTT_TEST_API_ENABLED','VEHICLE_STATUS_TEST_API_ENABLED')) {
        # 안전 플래그는 값이 중요하므로 표시한다(비밀 아님)
        Write-Info "${v}: $val"
    }
    else {
        Write-Info "${v}: 설정됨"
    }
}

Write-Header "요약"
Write-Host ("  OK: {0}   MISSING: {1}   NOT LISTENING: {2}" -f $script:OkCount, $script:MissingCount, $script:NotListening)
Write-Host ""
Write-Host "  참고"
Write-Host "    - NOT LISTENING 은 아직 서비스를 띄우지 않았다는 뜻일 수 있다(오류가 아님)."
Write-Host "    - 기동 순서:  MySQL -> Mosquitto -> Spring Boot -> Next.js"
Write-Host "    - 자세한 절차는 DEPLOY_GPU_SERVER.md 참고."
Write-Host ""
