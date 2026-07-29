#!/usr/bin/env bash
# =============================================================================
# GPU 서버 사전 점검 (읽기 전용)
# =============================================================================
#
# 이 스크립트는 시스템을 **변경하지 않는다**.
#   - 서비스 시작/종료 없음
#   - Docker 컨테이너 변경 없음
#   - 방화벽 변경 없음
#   - 파일 생성/수정 없음
#   - MQTT 발행 없음
#
# 비밀번호·인증정보는 출력하지 않는다(DB_PASSWORD / MQTT_PASSWORD 등은
# "설정됨/미설정"만 표시한다).
#
# 사용법
#   ./scripts/check-gpu-server.sh
#
# 도구가 없어도 중단하지 않고 MISSING 으로 표시한 뒤 계속 진행한다.
# =============================================================================

# set -e 를 쓰지 않는다 — 도구가 없어도 끝까지 점검해야 하기 때문이다.
set -uo pipefail

OK=0; MISSING=0; NOT_LISTENING=0

hdr()  { printf '\n\033[1m── %s ─────────────────────────────────────\033[0m\n' "$1"; }
ok()   { printf '  [OK]            %s\n' "$1"; OK=$((OK+1)); }
miss() { printf '  [MISSING]       %s\n' "$1"; MISSING=$((MISSING+1)); }
nolis(){ printf '  [NOT LISTENING] %s\n' "$1"; NOT_LISTENING=$((NOT_LISTENING+1)); }
info() { printf '  %s\n' "$1"; }

# 도구 버전 확인 — 없으면 MISSING 으로만 기록하고 계속
check_tool() {
  local name="$1"; shift
  if command -v "$name" >/dev/null 2>&1; then
    local ver
    ver="$("$@" 2>&1 | head -1)"
    ok "$name — ${ver}"
  else
    miss "$name (설치되지 않음 또는 PATH 에 없음)"
  fi
}

# 포트 LISTEN 확인 — ss 우선, 없으면 netstat, 둘 다 없으면 확인 불가
check_port() {
  local port="$1" label="$2" out=""
  if command -v ss >/dev/null 2>&1; then
    out="$(ss -lnt 2>/dev/null | awk -v p=":${port}$" '$4 ~ p {print}')"
  elif command -v netstat >/dev/null 2>&1; then
    out="$(netstat -lnt 2>/dev/null | awk -v p=":${port}$" '$4 ~ p {print}')"
  else
    info "[SKIP]          ${port} (${label}) — ss/netstat 없음"
    return
  fi
  if [[ -n "${out}" ]]; then
    ok "${port} LISTEN (${label})"
  else
    nolis "${port} (${label})"
  fi
}

printf '\033[1m===== GPU 서버 사전 점검 (읽기 전용) =====\033[0m\n'
printf '실행 시각: %s\n' "$(date '+%Y-%m-%d %H:%M:%S')"

hdr "OS / 호스트"
info "uname   : $(uname -a 2>/dev/null || echo '확인 불가')"
if [[ -r /etc/os-release ]]; then
  info "os      : $(. /etc/os-release && echo "${PRETTY_NAME:-unknown}")"
else
  info "os      : /etc/os-release 없음"
fi
info "hostname: $(hostname 2>/dev/null || echo '확인 불가')"
# hostname -I 는 Linux 전용. 실패해도 넘어간다.
info "IP      : $(hostname -I 2>/dev/null || echo '확인 불가 (hostname -I 미지원)')"

hdr "현재 사용자 / 경로"
info "whoami  : $(whoami 2>/dev/null || echo '확인 불가')"
info "pwd     : $(pwd)"

hdr "런타임 도구"
check_tool java    java -version
check_tool mvn     mvn -v
check_tool node    node -v
check_tool npm     npm -v
check_tool docker  docker --version
if command -v docker >/dev/null 2>&1; then
  if docker compose version >/dev/null 2>&1; then
    ok "docker compose — $(docker compose version 2>&1 | head -1)"
  else
    miss "docker compose (plugin 미설치 또는 권한 없음)"
  fi
fi
check_tool mysql   mysql --version
check_tool mosquitto_sub mosquitto_sub --help
check_tool conda   conda --version

hdr "Conda 환경 목록"
if command -v conda >/dev/null 2>&1; then
  conda env list 2>/dev/null | sed 's/^/  /' || info "환경 목록 조회 실패"
else
  info "conda 없음 — 환경 목록을 확인할 수 없음"
fi

hdr "포트 LISTEN 확인"
check_port 1883 "Mosquitto"
check_port 3306 "MySQL"
check_port 8080 "Spring Boot"
check_port 3000 "Next.js"

hdr "관련 프로세스"
for p in mosquitto mysqld java node isaac; do
  n="$(pgrep -fa "$p" 2>/dev/null | grep -v 'check-gpu-server' | wc -l | tr -d ' ')"
  if [[ "${n}" != "0" ]]; then
    info "${p}: ${n}개 실행 중"
  else
    info "${p}: 실행 중 아님"
  fi
done

hdr "환경변수 설정 여부 (값은 출력하지 않음)"
for v in DB_URL DB_USERNAME DB_PASSWORD \
         MQTT_ENABLED MQTT_BROKER_URL MQTT_USERNAME MQTT_PASSWORD \
         CORS_ALLOWED_ORIGINS WEBSOCKET_ALLOWED_ORIGIN_PATTERNS \
         SQL_INIT_MODE MQTT_TEST_API_ENABLED VEHICLE_STATUS_TEST_API_ENABLED; do
  if [[ -n "${!v:-}" ]]; then
    case "$v" in
      # 비밀 계열은 값을 절대 출력하지 않는다
      *PASSWORD*) info "${v}: 설정됨 (값 미출력)" ;;
      # 안전 플래그는 값이 중요하므로 그대로 보여준다(비밀 아님)
      SQL_INIT_MODE|MQTT_TEST_API_ENABLED|VEHICLE_STATUS_TEST_API_ENABLED)
        info "${v}: ${!v}" ;;
      *) info "${v}: 설정됨" ;;
    esac
  else
    info "${v}: 미설정 (코드 기본값 사용)"
  fi
done

hdr "요약"
printf '  OK: %d   MISSING: %d   NOT LISTENING: %d\n' "${OK}" "${MISSING}" "${NOT_LISTENING}"
cat <<'EOF'

  참고
    - NOT LISTENING 은 아직 그 서비스를 띄우지 않았다는 뜻일 수 있다(오류가 아님).
    - 기동 순서:  MySQL → Mosquitto → Spring Boot → Next.js
    - 자세한 절차는 DEPLOY_GPU_SERVER.md 참고.
EOF
