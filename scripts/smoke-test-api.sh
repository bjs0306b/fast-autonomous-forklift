#!/usr/bin/env bash
# =============================================================================
# REST Smoke Test — 백엔드/프론트가 살아 있는지 읽기 전용으로 확인
# =============================================================================
#
# 이 스크립트는 **상태를 바꾸지 않는다**.
#   - 차량 생성 없음
#   - DB 데이터 변경 없음
#   - MQTT 발행 없음
#   - EMERGENCY STOP / STOP / MOVE 요청 없음
#
# 테스트 API 비활성 확인(POST /api/mqtt/test, PUT /api/vehicles/{id}/status)은
# 기본적으로 실행하지 않는다. 만약 그 API 가 켜져 있는 서버라면 실제로 상태를
# 바꿔 버리기 때문이다. 확인이 필요하면 명시적으로 켠다:
#
#   ALLOW_TEST_ENDPOINT_CHECK=true ./scripts/smoke-test-api.sh
#
# 사용법
#   ./scripts/smoke-test-api.sh
#   BACKEND_BASE_URL=http://10.0.0.5:8080 FRONTEND_BASE_URL=http://10.0.0.5:3000 \
#     ./scripts/smoke-test-api.sh
# =============================================================================

set -uo pipefail

BACKEND_BASE_URL="${BACKEND_BASE_URL:-http://localhost:8080}"
FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://localhost:3000}"
ALLOW_TEST_ENDPOINT_CHECK="${ALLOW_TEST_ENDPOINT_CHECK:-false}"

PASS=0; FAIL=0; SKIP=0

if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl 이 필요합니다." >&2
  exit 1
fi

HAS_JQ=false
command -v jq >/dev/null 2>&1 && HAS_JQ=true

hdr()  { printf '\n\033[1m── %s ─────────────────────────────────────\033[0m\n' "$1"; }
pass() { printf '  \033[32m[PASS]\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '  \033[31m[FAIL]\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
skip() { printf '  [SKIP] %s\n' "$1"; SKIP=$((SKIP+1)); }

# GET 요청 후 기대 상태코드와 비교. 본문은 ApiResponse 봉투 키 존재만 확인한다.
check_get() {
  local path="$1" expected="$2" desc="$3"
  local url="${BACKEND_BASE_URL}${path}"
  local body status
  body="$(curl -s -m 10 -w $'\n%{http_code}' "${url}" 2>/dev/null)" || {
    fail "${desc} — 연결 실패 (${url})"
    return
  }
  status="$(printf '%s' "${body}" | tail -1)"
  body="$(printf '%s' "${body}" | sed '$d')"

  if [[ "${status}" == "${expected}" ]]; then
    pass "${desc} — HTTP ${status}"
  else
    fail "${desc} — HTTP ${status} (기대 ${expected}) ${url}"
    return
  fi

  # ApiResponse 봉투 확인 (success/data/error)
  if [[ "${status}" == "200" ]]; then
    if [[ "${HAS_JQ}" == true ]]; then
      local has
      has="$(printf '%s' "${body}" | jq -r 'has("success") and has("data") and has("error")' 2>/dev/null)"
      if [[ "${has}" == "true" ]]; then
        printf '         success=%s\n' "$(printf '%s' "${body}" | jq -c '.success' 2>/dev/null)"
      else
        printf '         (ApiResponse 봉투 아님 또는 JSON 파싱 불가)\n'
      fi
    else
      printf '         %s\n' "$(printf '%s' "${body}" | head -c 200)"
    fi
  fi
}

printf '\033[1m===== REST Smoke Test =====\033[0m\n'
printf 'backend : %s\n' "${BACKEND_BASE_URL}"
printf 'frontend: %s\n' "${FRONTEND_BASE_URL}"
printf 'jq      : %s\n' "$([[ "${HAS_JQ}" == true ]] && echo '있음' || echo '없음 — 원문 출력으로 대체')"

hdr "백엔드 조회 API (읽기 전용)"
check_get "/api/health"                          200 "GET /api/health"
check_get "/api/monitoring/dashboard"            200 "GET /api/monitoring/dashboard"
check_get "/api/vehicles"                        200 "GET /api/vehicles"
check_get "/api/vehicles/load-safety/latest"     200 "GET /api/vehicles/load-safety/latest"

hdr "테스트 API 비활성 확인"
if [[ "${ALLOW_TEST_ENDPOINT_CHECK}" != "true" ]]; then
  skip "POST /api/mqtt/test — ALLOW_TEST_ENDPOINT_CHECK=true 일 때만 실행"
  skip "PUT  /api/vehicles/{id}/status — 동일"
  printf '         (켜져 있는 서버라면 실제로 MQTT 발행·상태 변경이 일어나므로 기본 비활성)\n'
else
  # 기대: 404 (빈이 등록되지 않음). 200 이면 테스트 API 가 켜진 것이다.
  s1="$(curl -s -o /dev/null -m 10 -w '%{http_code}' -X POST \
        -H 'Content-Type: application/json' -d '{}' \
        "${BACKEND_BASE_URL}/api/mqtt/test" 2>/dev/null)"
  if [[ "${s1}" == "404" ]]; then
    pass "POST /api/mqtt/test — HTTP 404 (비활성, 정상)"
  else
    fail "POST /api/mqtt/test — HTTP ${s1} (404 여야 함. 테스트 API 가 켜져 있다)"
  fi

  s2="$(curl -s -o /dev/null -m 10 -w '%{http_code}' -X PUT \
        -H 'Content-Type: application/json' -d '{"status":"IDLE"}' \
        "${BACKEND_BASE_URL}/api/vehicles/SIM-F01/status" 2>/dev/null)"
  if [[ "${s2}" == "404" ]]; then
    pass "PUT /api/vehicles/SIM-F01/status — HTTP 404 (비활성, 정상)"
  else
    fail "PUT /api/vehicles/SIM-F01/status — HTTP ${s2} (404 여야 함. 테스트 API 가 켜져 있다)"
  fi
fi

hdr "프론트"
fs="$(curl -s -o /dev/null -m 10 -w '%{http_code}' "${FRONTEND_BASE_URL}/" 2>/dev/null)"
if [[ "${fs}" == "200" ]]; then
  pass "GET / — HTTP 200"
else
  fail "GET / — HTTP ${fs} (기대 200) ${FRONTEND_BASE_URL}"
fi

hdr "요약"
printf '  PASS: %d   FAIL: %d   SKIP: %d\n' "${PASS}" "${FAIL}" "${SKIP}"
cat <<'EOF'

  참고
    - 차량이 0대이고 dashboard 의 vehicles 가 빈 배열인 것이 **정상**이다.
      더미 차량 자동 삽입을 차단해 두었기 때문이다(DEPLOY_GPU_SERVER.md §10).
    - 실제 차량은 POST /api/vehicles 로 등록한다.
EOF

[[ "${FAIL}" -eq 0 ]] && exit 0 || exit 1
