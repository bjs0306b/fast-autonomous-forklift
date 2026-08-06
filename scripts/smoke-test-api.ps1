<#
.SYNOPSIS
    REST Smoke Test (Windows / PowerShell) — 백엔드/프론트 생존 확인

.DESCRIPTION
    상태를 바꾸지 않는다.
      - 차량 생성 없음
      - DB 데이터 변경 없음
      - MQTT 발행 없음
      - EMERGENCY STOP / STOP / MOVE 요청 없음

    테스트 API 비활성 확인은 기본적으로 실행하지 않는다.
    그 API 가 켜져 있는 서버라면 실제로 상태를 바꿔 버리기 때문이다.
    확인이 필요하면:  $env:ALLOW_TEST_ENDPOINT_CHECK='true'

.EXAMPLE
    .\scripts\smoke-test-api.ps1

.EXAMPLE
    $env:BACKEND_BASE_URL='http://10.0.0.5:8080'
    $env:FRONTEND_BASE_URL='http://10.0.0.5:3000'
    .\scripts\smoke-test-api.ps1
#>

$ErrorActionPreference = 'Continue'

$BackendBaseUrl  = if ($env:BACKEND_BASE_URL)  { $env:BACKEND_BASE_URL }  else { 'http://localhost:8080' }
$FrontendBaseUrl = if ($env:FRONTEND_BASE_URL) { $env:FRONTEND_BASE_URL } else { 'http://localhost:3000' }
$AllowTestCheck  = ($env:ALLOW_TEST_ENDPOINT_CHECK -eq 'true')

$script:Pass = 0; $script:Fail = 0; $script:Skip = 0

function Write-Header([string]$Text) {
    Write-Host ""
    Write-Host "-- $Text --------------------------------------" -ForegroundColor Cyan
}
function Write-Pass([string]$Text) { Write-Host "  [PASS] $Text" -ForegroundColor Green;  $script:Pass++ }
function Write-Fail([string]$Text) { Write-Host "  [FAIL] $Text" -ForegroundColor Red;    $script:Fail++ }
function Write-Skip([string]$Text) { Write-Host "  [SKIP] $Text" -ForegroundColor Yellow; $script:Skip++ }

# HTTP 상태코드만 얻는다. 4xx/5xx 도 예외 없이 코드로 돌려받기 위해 try/catch 를 쓴다.
function Get-StatusCode([string]$Url, [string]$Method = 'GET', $Body = $null) {
    try {
        $params = @{
            Uri                = $Url
            Method             = $Method
            TimeoutSec         = 10
            UseBasicParsing    = $true
            ErrorAction        = 'Stop'
        }
        if ($null -ne $Body) {
            $params['Body']        = $Body
            $params['ContentType'] = 'application/json'
        }
        $resp = Invoke-WebRequest @params
        return [int]$resp.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return -1   # 연결 자체 실패
    }
}

function Test-GetEndpoint([string]$Path, [int]$Expected, [string]$Desc) {
    $url = "$BackendBaseUrl$Path"
    try {
        $resp = Invoke-WebRequest -Uri $url -Method GET -TimeoutSec 10 -UseBasicParsing -ErrorAction Stop
        $status = [int]$resp.StatusCode
    } catch {
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
        } else {
            Write-Fail "$Desc - 연결 실패 ($url)"
            return
        }
    }

    if ($status -eq $Expected) { Write-Pass "$Desc - HTTP $status" }
    else { Write-Fail "$Desc - HTTP $status (기대 $Expected) $url"; return }

    if ($status -eq 200) {
        try {
            $json = $resp.Content | ConvertFrom-Json
            $hasEnvelope = ($null -ne $json.PSObject.Properties['success']) -and
                           ($null -ne $json.PSObject.Properties['data'])    -and
                           ($null -ne $json.PSObject.Properties['error'])
            if ($hasEnvelope) { Write-Host "         success=$($json.success)" }
            else { Write-Host "         (ApiResponse 봉투 아님)" }
        } catch {
            $snippet = $resp.Content
            if ($snippet.Length -gt 200) { $snippet = $snippet.Substring(0, 200) }
            Write-Host "         $snippet"
        }
    }
}

Write-Host "===== REST Smoke Test =====" -ForegroundColor White
Write-Host "backend : $BackendBaseUrl"
Write-Host "frontend: $FrontendBaseUrl"

Write-Header "백엔드 조회 API (읽기 전용)"
Test-GetEndpoint '/api/health'                      200 'GET /api/health'
Test-GetEndpoint '/api/monitoring/dashboard'        200 'GET /api/monitoring/dashboard'
Test-GetEndpoint '/api/vehicles'                    200 'GET /api/vehicles'
Test-GetEndpoint '/api/vehicles/load-safety/latest' 200 'GET /api/vehicles/load-safety/latest'

Write-Header "테스트 API 비활성 확인"
if (-not $AllowTestCheck) {
    Write-Skip 'POST /api/mqtt/test - $env:ALLOW_TEST_ENDPOINT_CHECK=''true'' 일 때만 실행'
    Write-Skip 'PUT  /api/vehicles/{id}/status - 동일'
    Write-Host "         (켜져 있는 서버라면 실제로 MQTT 발행·상태 변경이 일어나므로 기본 비활성)"
} else {
    $s1 = Get-StatusCode "$BackendBaseUrl/api/mqtt/test" 'POST' '{}'
    if ($s1 -eq 404) { Write-Pass 'POST /api/mqtt/test - HTTP 404 (비활성, 정상)' }
    else { Write-Fail "POST /api/mqtt/test - HTTP $s1 (404 여야 함. 테스트 API 가 켜져 있다)" }

    $s2 = Get-StatusCode "$BackendBaseUrl/api/vehicles/SIM-F01/status" 'PUT' '{"status":"IDLE"}'
    if ($s2 -eq 404) { Write-Pass 'PUT /api/vehicles/SIM-F01/status - HTTP 404 (비활성, 정상)' }
    else { Write-Fail "PUT /api/vehicles/SIM-F01/status - HTTP $s2 (404 여야 함. 테스트 API 가 켜져 있다)" }
}

Write-Header "프론트"
$fs = Get-StatusCode "$FrontendBaseUrl/" 'GET'
if ($fs -eq 200) { Write-Pass 'GET / - HTTP 200' }
else { Write-Fail "GET / - HTTP $fs (기대 200) $FrontendBaseUrl" }

Write-Header "요약"
Write-Host ("  PASS: {0}   FAIL: {1}   SKIP: {2}" -f $script:Pass, $script:Fail, $script:Skip)
Write-Host ""
Write-Host "  참고"
Write-Host "    - 차량이 0대이고 dashboard 의 vehicles 가 빈 배열인 것이 정상이다."
Write-Host "      더미 차량 자동 삽입을 차단해 두었기 때문이다(DEPLOY_GPU_SERVER.md 10장)."
Write-Host "    - 실제 차량은 POST /api/vehicles 로 등록한다."
Write-Host ""

if ($script:Fail -eq 0) { exit 0 } else { exit 1 }
