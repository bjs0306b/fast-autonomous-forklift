#!/usr/bin/env bash
#
# EC2(i15a304.p.ssafy.io) 배포. GitLab CI 가 develop 푸시마다 호출하고,
# 사람이 손으로 실행해도 똑같이 동작한다.
#
#   ./scripts/deploy-ec2.sh
#
# 하는 일: 작업본 → 배포 디렉터리 동기화 → 이미지 빌드 → 기동 → 헬스체크.
# 헬스체크가 실패하면 **직전 이미지로 되돌리고** 0 이 아닌 코드로 끝난다.
#
# ── 반드시 알아야 할 두 가지 ────────────────────────────────────────────────
#
# 1. 배포 디렉터리를 바꾸면 DB 가 빈다.
#    compose 프로젝트명은 기본적으로 **디렉터리명**에서 나오고, 볼륨 이름이
#    `<프로젝트>_mysql-data` 다. 현재 실데이터는 `fast-backend_mysql-data` 에 있다.
#    다른 경로에서 compose 를 올리면 새 프로젝트 → 새 빈 볼륨이 붙고, 컨테이너는
#    **정상 기동한다**(에러가 안 난다). 그래서 아래에서 경로와 COMPOSE_PROJECT_NAME
#    을 둘 다 못 박는다.
#
# 2. 비밀값은 서버에만 있다.
#    `.env`(compose 용)와 `backend.env`(백엔드 컨테이너 용)는 커밋되지 않으므로
#    동기화 대상에서 제외한다. 지우면 복구는 사람 손으로만 된다.
#
set -Eeuo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/home/ubuntu/fast-backend}"
SRC_DIR="${SRC_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
HEALTH_URL="${HEALTH_URL:-http://localhost:8080/api/health}"
FRONT_URL="${FRONT_URL:-http://localhost:3000}"
HEALTH_RETRIES="${HEALTH_RETRIES:-40}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-5}"

# compose 프로젝트명을 경로에 의존시키지 않는다 (위 주석 1번).
export COMPOSE_PROJECT_NAME=fast-backend

# docker 그룹 대신 sudo 를 쓴다 — 그룹 추가는 재로그인해야 반영되지만
# sudo 는 NOPASSWD 라 러너 서비스에서 바로 동작한다.
DOCKER="sudo docker"

log() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31m[배포 실패]\033[0m %s\n' "$*" >&2; exit 1; }

# ── 사전 점검 ──────────────────────────────────────────────────────────────
log "사전 점검"
[ -d "$DEPLOY_DIR" ] || die "배포 디렉터리가 없다: $DEPLOY_DIR"
[ -f "$DEPLOY_DIR/.env" ] || die ".env 가 없다. 비밀값 파일은 서버에만 있고 복구는 수동이다."
[ -f "$DEPLOY_DIR/backend.env" ] || die "backend.env 가 없다. 위와 같다."
[ -f "$SRC_DIR/docker-compose.yml" ] || die "docker-compose.yml 이 작업본에 없다: $SRC_DIR"
echo "  작업본     : $SRC_DIR"
echo "  배포 대상  : $DEPLOY_DIR"
echo "  프로젝트명 : $COMPOSE_PROJECT_NAME"

# ── 롤백 지점 기록 ────────────────────────────────────────────────────────
# 실행 중인 컨테이너가 **실제로 쓰는 이미지 ID**를 잡아둔다. 태그(:latest)는
# 빌드가 덮어쓰지만 이미지 ID 는 남아 있어 되돌릴 수 있다.
prev_image() { $DOCKER inspect -f '{{.Image}}' "$1" 2>/dev/null || true; }
PREV_BACKEND="$(prev_image fast-backend)"
PREV_FRONTEND="$(prev_image fast-frontend)"
log "롤백 지점"
echo "  backend  : ${PREV_BACKEND:-(없음 — 최초 배포)}"
echo "  frontend : ${PREV_FRONTEND:-(없음 — 최초 배포)}"

rollback() {
    [ -n "$PREV_BACKEND" ] || { echo "  되돌릴 이전 이미지가 없다 — 그대로 둔다." >&2; return; }
    printf '\n\033[1;33m==> 롤백: 직전 이미지로 되돌린다\033[0m\n' >&2
    $DOCKER tag "$PREV_BACKEND"  "${COMPOSE_PROJECT_NAME}-backend:latest"  || true
    [ -n "$PREV_FRONTEND" ] && \
        $DOCKER tag "$PREV_FRONTEND" "${COMPOSE_PROJECT_NAME}-frontend:latest" || true
    ( cd "$DEPLOY_DIR" && $DOCKER compose up -d --no-build ) || true
}

# ⚠️ 롤백은 반드시 **명시적으로** 부른다. `trap rollback ERR` 로 두면 안 된다 —
#    실패 경로가 die() 를 거치는데 die 는 `exit` 이고, `exit` 은 ERR 트랩을
#    발동시키지 않는다. 그러면 "롤백이 있다"고 적혀 있는데 실제로는 한 번도
#    돌지 않는, 조용히 틀리는 안전망이 된다.
fail_and_rollback() {
    rollback
    die "$1"
}

# ── 동기화 ────────────────────────────────────────────────────────────────
# 레포 전체가 아니라 **배포에 필요한 경로만** 옮긴다.
#
# 이 레포에는 ai/·isaac_sim/·ros2_ws/·3d_model/·recovery/ 처럼 배포와 무관한
# 대용량 디렉터리가 있다. 통째로 rsync 하면 배포 디렉터리가 부풀고 Docker 빌드
# 컨텍스트(= 데몬으로 전송되는 양)가 같이 커진다. `.dockerignore` 가 일부를
# 걸러주지만 목록이 어긋나면 조용히 새 나가므로, 애초에 **보낼 것만 나열**한다.
#
# 목록에 없는 항목은 배포 디렉터리에서 지우지 않는다 — `.env`·`backend.env`
# (서버에만 있고 백업이 없는 비밀값)를 실수로 날리지 않기 위해서다.
DEPLOY_PATHS=(
    pom.xml mvnw .mvn src              # 백엔드 빌드
    frontend/monitoring-control-system # 프론트 빌드
    infra                              # mqtt-ca.crt
    Dockerfile.backend
    docker-compose.yml
    .dockerignore
    scripts/deploy-ec2.sh              # 서버에서 손으로 재실행할 수 있도록
)

log "작업본 → 배포 디렉터리 동기화"
for p in "${DEPLOY_PATHS[@]}"; do
    [ -e "$SRC_DIR/$p" ] || die "동기화 대상이 작업본에 없다: $p"
    if [ -d "$SRC_DIR/$p" ]; then
        mkdir -p "$DEPLOY_DIR/$p"
        rsync -a --delete \
            --exclude='node_modules/' \
            --exclude='.next/' \
            --exclude='target/' \
            "$SRC_DIR/$p"/ "$DEPLOY_DIR/$p"/
    else
        mkdir -p "$(dirname "$DEPLOY_DIR/$p")"
        rsync -a "$SRC_DIR/$p" "$DEPLOY_DIR/$p"
    fi
    echo "  $p"
done

# CI 러너는 잡을 root 로 실행한다(shell executor 를 `--user ubuntu` 로 두면
# `su -s /bin/bash ubuntu -c 'bash -l'` 단계에서 prepare environment 가 실패한다).
# 그대로 두면 동기화된 파일이 root 소유가 되어, 다음에 사람이 ubuntu 로 손수
# 배포할 때 rsync 가 권한 오류로 죽는다. 끝나고 돌려준다.
if [ "$(id -u)" -eq 0 ]; then
    chown -R ubuntu:ubuntu "$DEPLOY_DIR"
    echo "  (root 실행 — 소유권을 ubuntu 로 되돌림)"
fi

cd "$DEPLOY_DIR"

# ── 빌드 ──────────────────────────────────────────────────────────────────
# 빌드 실패는 여기서 끝난다. 아직 up 하지 않았으므로 돌던 서비스는 그대로다.
log "이미지 빌드"
$DOCKER compose build || die "이미지 빌드 실패 — 기존 컨테이너는 건드리지 않았다."

# ── 기동 ──────────────────────────────────────────────────────────────────
log "컨테이너 기동"
$DOCKER compose up -d || fail_and_rollback "컨테이너 기동 실패"

# ── 헬스체크 ──────────────────────────────────────────────────────────────
# 200 을 받을 때까지 기다린다. Spring Boot 기동에 30~60초가 걸린다.
wait_for() {
    local name="$1" url="$2" i
    for ((i = 1; i <= HEALTH_RETRIES; i++)); do
        if curl -fsS -o /dev/null --max-time 5 "$url"; then
            echo "  $name OK (${i}회째)"
            return 0
        fi
        sleep "$HEALTH_INTERVAL"
    done
    return 1
}

log "헬스체크 (최대 $((HEALTH_RETRIES * HEALTH_INTERVAL))초)"
wait_for "backend " "$HEALTH_URL" || {
    $DOCKER compose logs --tail=50 backend >&2 || true
    fail_and_rollback "백엔드 헬스체크 실패: $HEALTH_URL"
}
wait_for "frontend" "$FRONT_URL" || {
    $DOCKER compose logs --tail=50 frontend >&2 || true
    fail_and_rollback "프론트 헬스체크 실패: $FRONT_URL"
}

# ── 정리 ──────────────────────────────────────────────────────────────────
# dangling 이미지만 지운다. `image prune -a` 는 롤백 대상까지 지우므로 쓰지 않는다.
log "미사용 이미지 정리"
$DOCKER image prune -f >/dev/null || true

log "배포 완료"
$DOCKER compose ps
