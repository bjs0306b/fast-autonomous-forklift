# EC2 자동배포

> 대상: `i15a304.p.ssafy.io` — 백엔드 `:8080`, 프론트 `:3000`
> 관련 이슈: S15P11A304-179

`develop` 에 커밋이 들어오면 EC2 가 스스로 빌드·배포한다. 그 전에는 사람이 손으로
파일을 복사하고 컨테이너를 올렸고, 그래서 **2026-07-31 이후 배포가 멈춰 있었다.**

---

## 1. 구조

```
develop 푸시
   │
   ▼
GitLab 파이프라인 (.gitlab-ci.yml)
   │  러너가 EC2 위에 있다 — SSH 키·배포 자격증명이 CI 에 없다
   ▼
scripts/deploy-ec2.sh   (EC2 에서 실행)
   ├─ rsync   작업본 → /home/ubuntu/fast-backend  (.env·backend.env 는 제외)
   ├─ build   docker compose build
   ├─ up      docker compose up -d
   └─ check   /api/health · :3000  →  실패 시 직전 이미지로 롤백
```

컨테이너 4개가 모두 `restart=unless-stopped` 이므로 **재부팅 시 자동 복구된다.**
(systemd 유닛을 따로 만들 필요가 없다 — Docker 데몬이 그 역할을 한다.)

| 컨테이너 | 이미지 | 포트 |
|---|---|---|
| `fast-backend` | 빌드 (`Dockerfile.backend`) | 8080 |
| `fast-frontend` | 빌드 (`frontend/.../Dockerfile`) | 3000 |
| `fast-mysql` | `mysql:8.0` | 내부 3306 |
| `fast-mosquitto` | `eclipse-mosquitto:2` | 8883 (TLS) |

---

## 2. 러너 등록 (최초 1회)

⚠️ **등록 토큰은 자격증명이다.** 아래 명령은 토큰을 다루므로 **본인이 직접 실행한다.**
채팅·이슈·커밋 어디에도 붙여넣지 않는다.

**1)** GitLab 프로젝트 → **Settings → CI/CD → Runners** → *New project runner*

| 항목 | 값 | 이유 |
|---|---|---|
| Tags | **`ec2`** | `.gitlab-ci.yml` 의 `tags:` 와 글자까지 같아야 한다. 다르면 잡이 `no runner for tags` 로 영원히 대기한다 |
| Run untagged jobs | ☐ 해제 | 켜면 프로젝트의 태그 없는 잡을 전부 집어가 배포 전용 러너가 남의 빌드까지 EC2 에서 돌린다 |
| Runner description | `ec2-deploy` | — |
| Paused | ☐ 해제 | — |
| **Protected** | ☑ **체크** | 아래 참조 |
| Maximum job timeout | 비움 | 잡 타임아웃은 `.gitlab-ci.yml` 에 `30m` 으로 있다 |

> ⚠️ **Protected 를 반드시 체크한다.** shell executor 라 CI 스크립트가 EC2 에서
> `ubuntu` 계정으로 그대로 실행되고, 그 계정엔 `NOPASSWD` sudo 가 있다. 체크하지
> 않으면 **누구든 브랜치를 하나 파서 `.gitlab-ci.yml` 에 `tags: [ec2]` 잡을 넣는
> 것만으로 배포 서버에서 임의 명령을 돌릴 수 있다.** 체크하면 protected 브랜치
> (`develop`·`master`)에서만 동작한다. 파이프라인이 어차피 `develop` 전용이라
> 잃는 것은 없다(feature 브랜치에서 파이프라인을 시험할 수 없다는 점만 다르다).

생성 후 나오는 토큰(`glrt-...`)을 복사한다. **채팅·이슈·커밋 어디에도 남기지 않는다.**

**2)** EC2 에서 등록:

```bash
ssh i15a304
sudo gitlab-runner register \
  --non-interactive \
  --url https://lab.ssafy.com/ \
  --token <복사한_토큰> \
  --executor shell \
  --description "ec2-deploy"
```

**3)** 러너 서비스를 `ubuntu` 계정으로 돌린다. 배포 디렉터리 소유자가 `ubuntu` 이고,
`ubuntu` 에만 `NOPASSWD` sudo 가 있기 때문이다. 기본값(`gitlab-runner` 계정)이면
`docker` 도 `rsync` 도 권한에서 막힌다.

```bash
sudo gitlab-runner uninstall
sudo gitlab-runner install --user ubuntu --working-directory /home/ubuntu/gitlab-runner
sudo systemctl restart gitlab-runner
sudo gitlab-runner verify
```

**4)** 확인: GitLab Runners 화면에서 초록불이면 된다.

---

## 3. 비밀값

`.env` 와 `backend.env` 는 **서버에만 있고 커밋되지 않는다.** `deploy-ec2.sh` 의
rsync 가 둘 다 `--exclude` 하므로 배포해도 덮어써지지 않는다.

| 파일 | 읽는 주체 | 템플릿 |
|---|---|---|
| `/home/ubuntu/fast-backend/.env` | `docker compose` 자신 | `.env.example` |
| `/home/ubuntu/fast-backend/backend.env` | 백엔드 컨테이너 (`env_file`) | `backend.env.example` |

⚠️ **이 두 파일은 백업이 없다.** 지우면 값을 아는 사람에게 다시 받아야 한다.

---

## 4. 알아야 할 함정

### 배포 디렉터리를 옮기면 DB 가 빈다

compose 프로젝트명은 기본적으로 **디렉터리명**에서 나오고 볼륨은 `<프로젝트>_mysql-data`
다. 실데이터는 `fast-backend_mysql-data` 에 있다. 다른 경로에서 `docker compose up`
하면 새 프로젝트로 잡혀 **빈 볼륨이 새로 붙는데, 컨테이너는 정상 기동한다** — 에러가
안 나고 데이터만 사라진 것처럼 보인다.

→ `deploy-ec2.sh` 가 `COMPOSE_PROJECT_NAME=fast-backend` 를 못 박아 경로에 의존하지
않게 했다. 그래도 배포 경로를 바꿀 일이 있으면 볼륨부터 확인할 것.

### `NEXT_PUBLIC_*` 는 재시작으로 안 바뀐다

Next.js 빌드 인자라 **번들에 박힌다.** 값을 고쳤으면 컨테이너 재시작이 아니라
이미지 재빌드가 필요하다. `deploy-ec2.sh` 는 매번 `build` 를 하므로 배포를 한 번
돌리면 반영된다.

### 자동배포는 깨진 코드도 배포한다

`develop` 푸시마다 무조건 나간다(팀 결정). 방어는 두 겹뿐이다.

1. 이미지 빌드 중 `./mvnw clean package` 가 **테스트를 돈다** — 깨지면 빌드가
   실패하고 돌던 컨테이너는 손대지 않는다.
2. 기동 후 헬스체크 실패 시 **직전 이미지로 롤백**한다.

"테스트는 통과하는데 실제로 망가진" 변경은 못 막는다. 시연 직전에는 develop 에
머지하지 말 것.

---

## 5. 수동 배포 · 롤백

CI 없이 똑같은 절차를 손으로 돌릴 수 있다.

```bash
ssh i15a304
cd /home/ubuntu/fast-backend
./scripts/deploy-ec2.sh
```

배포 후 문제가 발견돼 되돌려야 하면 — 스크립트의 자동 롤백은 *헬스체크 실패*에만
동작한다. 기동은 됐는데 동작이 이상한 경우는 직접 되돌린다.

```bash
# 이전 이미지 목록 (CREATED 순)
sudo docker images fast-backend-backend

# 되돌리기
sudo docker tag <이전_IMAGE_ID> fast-backend-backend:latest
cd /home/ubuntu/fast-backend && sudo docker compose up -d --no-build
```

⚠️ `docker image prune -a` 는 **롤백 대상 이미지까지 지운다.** 쓰지 말 것.
`deploy-ec2.sh` 는 dangling 만 지운다(`prune -f`).

---

## 6. 장애 대응

| 증상 | 확인 | 원인·조치 |
|---|---|---|
| 파이프라인이 안 뜬다 | Runners 화면 | 러너 오프라인 → `sudo systemctl restart gitlab-runner` |
| `no runner for tags` | `.gitlab-ci.yml` `tags:` | 러너 태그가 `ec2` 가 아님 |
| `permission denied` (docker/rsync) | `sudo gitlab-runner verify` | 러너가 `gitlab-runner` 계정으로 돎 → §2-3 |
| 빌드 단계 실패 | 잡 로그 | 테스트 실패. **서버는 멀쩡하다** — 코드를 고칠 것 |
| 헬스체크 실패 후 롤백됨 | `docker compose logs backend` | 기동 실패. DB 스키마·env 확인 |
| 차량 3대가 화면에 보임 | `SELECT COUNT(*) FROM vehicle` | `SQL_INIT_MODE=always` → 즉시 `never` |
| DB 가 비어 보임 | `docker volume ls` | 볼륨 이름이 `fast-backend_mysql-data` 인지 확인 (§4) |

배포 이력은 GitLab **Deployments → Environments → production** 에서 본다.
