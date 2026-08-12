# GPU 서버 배포 가이드 — ⚠️ 폐기됨 (실행되지 않은 계획)

> # 🛑 이 문서의 배포는 **한 번도 실행되지 않았다.**
>
> 2026-07-28에 "EC2 아웃바운드가 막혔으니 GPU 서버로 일원화하자"고 정하면서 쓴
> 문서다. 그런데 **그 이전(前)이 실행되지 않았고**, 배포는 결국 **EC2로 갔다.**
> (아웃바운드 차단이라는 전제도 2026-07-31 실측에서 사실이 아니었다.)
>
> ## 현재 배포는 여기다
>
> | | |
> |---|---|
> | 위치 | **EC2 `i15a304.p.ssafy.io`** — 백엔드 `:8080`, 프론트 `:3000` |
> | 방식 | **Docker Compose** (`docker-compose.yml` · `Dockerfile.backend`) |
> | 자동배포 | **`develop` 푸시 시 자동** (`.gitlab-ci.yml` → `scripts/deploy-ec2.sh`) |
> | 문서 | **[docs/deploy/ec2-auto-deploy.md](docs/deploy/ec2-auto-deploy.md)** ← 여기를 보라 |
>
> 아래 절차(`mvnw spring-boot:run`·`npm run start`로 직접 띄우기)는 **현재 배포 방식과
> 다르다.** 그대로 따라 하면 EC2의 컨테이너 구성과 어긋난다.
>
> ## 그래도 남겨두는 이유
>
> §3 환경변수 · §4 스키마 적용 · §8 CORS/STOMP origin · §10 빈 데이터가 정상인 이유 ·
> §12 담당자 전달 계약 · §14 장애 대응표는 **배포 위치와 무관하게 유효하다.** 특히
> §8·§10·§14는 실제로 겪은 증상 기반이라 참고 가치가 있다. 지우면 그 지식이 사라진다.
>
> *(폐기 표시: 2026-08-03, Jira S15P11A304-185)*

---

> 대상: FAST 관제 시스템 (Spring Boot + Next.js + Mosquitto + MySQL)
> **이 문서에는 실제 IP·비밀번호를 적지 않는다.** `<GPU_SERVER_IP>` 같은 자리표시자만 쓴다.

---

## 1. 현재 구조

```
   Orin (별도 장비)                Isaac Sim (GPU 서버)
        │ MQTT                          │ MQTT
        ▼                               ▼
   ┌────────── Mosquitto :1883 ──────────┐
   │  구독(백엔드←) forklift/+/status     │
   │                forklift/+/location   │
   │                forklift/+/load-safety│  외 6종
   │  발행(백엔드→) forklift/{id}/command │
   └──────────────────┬──────────────────┘
                      │
              Spring Boot :8080
               ├── MySQL :3306
               ├── REST  /api/**
               └── STOMP /ws (SockJS) → /topic/vehicles/**
                      │
              Next.js :3000 → 브라우저
```

포트는 코드 기준값이다(`application.yml`의 `server.port: 8080` 등). 서버 상황에 따라 달라질 수 있다.

---

## 2. 필수 프로그램

| 프로그램 | 버전 | 확인 |
|---|---|---|
| JDK | **21** (`pom.xml`의 `java.version`) | `java -version` |
| Maven | 불필요 — **Wrapper 포함** | `./mvnw -v` |
| Node.js | Next.js 16 지원 버전 | `node -v` |
| npm | — | `npm -v` |
| MySQL | 8.x 권장 | `mysql --version` |
| Docker + Compose | Mosquitto 실행용 | `docker compose version` |

한 번에 점검:

```bash
./scripts/check-gpu-server.sh          # Linux (읽기 전용)
```
```powershell
.\scripts\check-local-env.ps1          # Windows (읽기 전용)
```

---

## 3. 환경변수

예시 파일을 복사해 채운다. **실제 값이 든 파일은 커밋하지 않는다.**

```bash
cp .env.gpu.example .env.gpu
chmod 600 .env.gpu
# 편집 후
set -a; source ./.env.gpu; set +a
```

| 변수 | 필수 | 비고 |
|---|---|---|
| `DB_URL` | ✅ | `serverTimezone=Asia/Seoul` 유지 |
| `DB_USERNAME` / `DB_PASSWORD` | ✅ | |
| `CORS_ALLOWED_ORIGINS` | **다른 PC 브라우저면 필수** | §8 |
| `WEBSOCKET_ALLOWED_ORIGIN_PATTERNS` | 권장 | 기본값이 `*`라 좁히는 것이 좋다 |
| `MQTT_BROKER_URL` | 같은 서버면 불필요 | 기본 `tcp://localhost:1883` |
| `MQTT_ENABLED` | 불필요 | 기본 `true` |
| `SQL_INIT_MODE` | **설정하지 말 것** | 기본 `never` (안전) |
| `MQTT_TEST_API_ENABLED` | **설정하지 말 것** | 기본 `false` (안전) |
| `VEHICLE_STATUS_TEST_API_ENABLED` | **설정하지 말 것** | 기본 `false` (안전) |

> 마지막 세 개는 **기본값이 이미 안전하다.** 운영에서 명시적으로 설정하면 실수로 위험한 값을 넣을
> 위험만 늘어난다. `SQL_INIT_MODE=always`는 더미 차량 3대를 DB에 넣고, 두 테스트 API는 각각
> 임의 MQTT 발행과 차량 상태 임의 변경을 허용한다.

프론트는 별도다:

```bash
cd frontend/monitoring-control-system
cp .env.production.example .env.production   # NEXT_PUBLIC_API_BASE_URL 수정
```

---

## 4. MySQL 최초 스키마 적용

이 프로젝트는 **스키마를 자동 생성하지 않는다**(Flyway/Liquibase/`ddl-auto` 미사용).
최초 1회만 아래를 실행한다.

```bash
DB_HOST=localhost DB_PORT=3306 DB_NAME=fast_backend DB_USERNAME=<username> \
  ./scripts/init-db-schema.sh
```
```powershell
$env:DB_HOST='localhost'; $env:DB_PORT='3306'
$env:DB_NAME='fast_backend'; $env:DB_USERNAME='<username>'
.\scripts\init-db-schema.ps1
```

스크립트가 하는 일 / 하지 않는 일:

| 한다 | 하지 않는다 |
|---|---|
| `CREATE DATABASE IF NOT EXISTS` | `DROP DATABASE` / `DROP TABLE` |
| `schema.sql` 적용 (`CREATE TABLE IF NOT EXISTS`) | `DELETE` / `TRUNCATE` |
| `SHOW TABLES` 출력 | `data-local.sql`(더미 차량) 삽입 |
| 실행 전 대상 출력 + `yes` 확인 | `SQL_INIT_MODE` 변경 |

확인:

```sql
USE fast_backend;
SHOW TABLES;
SELECT COUNT(*) FROM vehicle;   -- 0 이 정상이다 (§10)
```

---

## 5. Mosquitto 실행

```bash
cd infra/mqtt
docker compose up -d
docker compose ps
docker compose logs --tail=100 mosquitto
```

로컬 pub/sub 확인 (**비제어 토픽만** 사용):

```bash
# 터미널 1
mosquitto_sub -h localhost -p 1883 -t 'integration/test' -v
# 터미널 2
mosquitto_pub -h localhost -p 1883 -t 'integration/test' -m '{"message":"broker-check"}'
```

> ⚠ `forklift/+/command` 에는 **절대 발행하지 않는다.** 실제 차량이 움직이거나 멈춘다.

### Orin(다른 장비)에서 붙이려면

현재 `docker-compose.yml`은 `127.0.0.1:1883:1883`이라 **다른 장비에서 접속할 수 없다.**
이는 `allow_anonymous true`와 결합한 노출을 막기 위한 의도적 설정이다.

→ **`infra/mqtt/ORIN_NETWORK_ACCESS.md`를 먼저 읽고 팀 승인 후 변경한다.**

---

## 6. Spring Boot 실행

Maven Wrapper가 포함돼 있으므로 서버에 Maven을 따로 설치하지 않아도 된다.

```bash
# 개발/검증
./mvnw spring-boot:run

# 운영 (권장)
./mvnw clean package -DskipTests
java -jar target/fast-backend-0.0.1-SNAPSHOT.jar
```

> jar 이름은 `pom.xml`의 `name`/`version` 기준이다. 빌드 후 `ls target/*.jar`로 확인할 것.

기동 로그에서 확인할 것:

```
Started FastBackendApplication in N seconds
HikariPool-1 - Start completed.                    ← MySQL 연결
Tomcat started on port 8080
CORS enabled for /api/**: allowedOrigins=[...]     ← 허용 Origin 확인
(MQTT 연결·구독 로그)
예외 반복 없음
```

---

## 7. Next.js 빌드 및 실행

```bash
cd frontend/monitoring-control-system
npm ci                    # package-lock.json 기준 (이 프로젝트는 npm 사용)
npm run build
npm run start             # :3000

# 외부에서 접속해야 하면
npm run start -- --hostname 0.0.0.0 --port 3000
```

> ★ `NEXT_PUBLIC_API_BASE_URL`은 **빌드 시점에 번들에 박힌다.**
> 값을 바꾸면 반드시 `npm run build`를 다시 해야 한다. 서버 재시작만으로는 반영되지 않는다.

> `npm run lint`는 현재 실패한다 — 이 프로젝트에 `eslint.config.*`도 `eslint` 의존성도 없다.

---

## 8. CORS 및 STOMP origin

브라우저가 GPU 서버가 아닌 다른 PC에서 열린다면 **반드시** 설정해야 한다.

```bash
export CORS_ALLOWED_ORIGINS='http://localhost:3000,http://<GPU_SERVER_IP>:3000'
export WEBSOCKET_ALLOWED_ORIGIN_PATTERNS='http://localhost:3000,http://<GPU_SERVER_IP>:3000'
```

| 대상 | 코드 기본값 | 문제 |
|---|---|---|
| REST `/api/**` | `http://localhost:3000` | **다른 Origin이면 전부 차단** |
| STOMP `/ws` | `*` | 당장 동작하지만 너무 넓다 |

### localhost 함정

```
브라우저 PC → http://localhost:8080        ✗  localhost = 브라우저 PC 자신
브라우저 PC → http://<GPU_SERVER_IP>:8080  ✓
```

### 증상별 원인

| 증상 | 원인 |
|---|---|
| `CORS policy blocked` | `CORS_ALLOWED_ORIGINS`에 브라우저 Origin 없음 |
| `SockJS 403` / `403 handshake` | `WEBSOCKET_ALLOWED_ORIGIN_PATTERNS` 불일치 |
| `WebSocket connection failed` | `/ws` 미도달 — 포트·방화벽·백엔드 미기동 |
| env 바꿔도 그대로 | `NEXT_PUBLIC_*` 재빌드 안 함 |

---

## 9. Smoke Test

```bash
./scripts/smoke-test-api.sh
BACKEND_BASE_URL=http://<GPU_SERVER_IP>:8080 \
FRONTEND_BASE_URL=http://<GPU_SERVER_IP>:3000 \
  ./scripts/smoke-test-api.sh
```
```powershell
.\scripts\smoke-test-api.ps1
```

확인 대상:

| 요청 | 기대 |
|---|---|
| `GET /api/health` | 200 |
| `GET /api/monitoring/dashboard` | 200, `vehicles: []` |
| `GET /api/vehicles` | 200 |
| `GET /api/vehicles/load-safety/latest` | 200 |
| `GET /` (프론트) | 200 |

테스트 API 비활성 확인은 **기본적으로 실행되지 않는다**(켜져 있는 서버라면 실제로 상태를 바꾸므로).
확인이 필요하면:

```bash
ALLOW_TEST_ENDPOINT_CHECK=true ./scripts/smoke-test-api.sh
# 기대: POST /api/mqtt/test → 404,  PUT /api/vehicles/{id}/status → 404
```

---

## 10. 빈 데이터가 정상인 이유

기동 직후 화면은 이렇게 보여야 한다.

```
차량 목록      : 등록된 활성 차량이 없습니다
미니맵         : 마커 0개
적재 안전      : 적재 안전 데이터 미수신
실시간 배지    : 실시간 연결됨
영상           : 디지털 트윈 스트림 미연결 (샘플 이미지 · 실시간 영상 아님)
```

**이것이 올바른 상태다.** 이전에는 `data-local.sql`이 차량과 가짜 상태(배터리 82, 좌표 1.2/3.4)를
자동으로 넣어, 실제 차량이 하나도 없어도 화면에 차량이 떴다.
운영에서 이는 **"연동됐다"는 착시**를 만들기 때문에 자동 삽입을 차단했다.

> **등록하지 않은 차량이 보이면 `SQL_INIT_MODE`가 `always`로 되살아난 것이다.** 즉시 확인할 것.
> 현재 시드는 `SIM-F01` **1대뿐**이다(`REAL-F01`, 더미 `FORKLIFT-01/02`는 제거됨). 기존 DB 에 남은
> `FORKLIFT-*` 행은 `src/main/resources/db/cleanup-dummy-vehicles.sql` 로,
> `REAL-F01` 행은 `src/main/resources/db/migrate-real-f01-to-sim-f01.sql` 로 정리한다.

---

## 11. 실제 차량 등록

시드가 차단됐으므로 REST로 등록한다.

```bash
curl -X POST http://localhost:8080/api/vehicles \
  -H 'Content-Type: application/json' \
  -d '{"vehicleId":"SIM-F01","name":"시뮬레이션 지게차 1호"}'

curl http://localhost:8080/api/vehicles
```

> **관제 대상은 `SIM-F01` 한 대다.** 실물 지게차(`REAL-F01`)는 등록하지 않는다 — ROS2 브리지는
> 여전히 `forklift/REAL-F01/*` 로 발행하지만 미등록 차량이라 백엔드가 폐기한다(의도된 동작).
>
> `VehicleCreateRequest` 는 `vehicleId` + `name` **두 필드뿐**이다. 예전 예시에 있던
> `"source":"SIM"` 은 DTO 에 없는 필드라 Jackson 이 조용히 버린다 — 넣어도 저장되지 않으니
> "source 를 등록했다"고 오해하지 말 것. 실물/시뮬 구분은 **ID 접두어**(`REAL-` / `SIM-`)로 한다.
>
> `name` 은 화면 표시용이며 `data-local.sql` · `migration-local-fast-backend.sql` 과 같은 값을
> 써야 한다. 다르면 어느 쪽을 먼저 실행했느냐에 따라 화면 문구가 달라진다.

**차량 ID 규칙**: 백엔드·DB·ROS2 브리지·테스트가 모두 **하이픈**(`SIM-F01`)을 쓴다.
등록 ID와 MQTT 토픽·payload의 `vehicleId`가 **정확히 일치해야** 메시지가 처리된다.
불일치하면 `Vehicle ID mismatch` 또는 `vehicle not registered` 경고와 함께 조용히 폐기된다.

---

## 12. Isaac Sim·Orin 담당자에게 전달할 계약

### 공통

| 항목 | 값 |
|---|---|
| 브로커 | `tcp://<GPU_SERVER_IP>:1883` (Isaac은 같은 서버면 `localhost`) |
| vehicleId | **하이픈** — `SIM-F01` (관제 대상은 이 한 대) |
| timestamp | ISO-8601 **`+09:00`** |
| clientId | 각자 **유일**해야 함 (중복 시 서로 끊김) |

### ⚠ 차량 ID 불일치 (미해결)

`isaac_sim/scripts/*.py`가 **언더스코어**(`SIM_F01`)를 쓴다. 이대로면 **모든 시뮬 메시지가
미등록 차량으로 폐기된다.** 권장 매핑:

```
MQTT/DB vehicleId : SIM-F01     ← 하이픈
ROS2 namespace    : SIM_F01     ← ROS2 이름 규칙상 하이픈 불가
Isaac prim name   : 기존 scene 이름 유지 가능
```

두 표기를 **명시적 변환 함수**로 분리하는 것을 권장한다. 단순 일괄 치환은 ROS2 네임스페이스를
깨뜨릴 수 있다. **이번 작업에서 Python 코드를 변경하지 않았다.**

### 적재 안전 발행 (미구현 — 담당자 필요)

토픽 `forklift/{vehicleId}/load-safety`

```json
{
  "vehicleId": "SIM-F01",
  "cargoId": "CARGO-001",
  "forkHeight": 0.86, "cargoHeight": 1.42,
  "roll": 7.4, "pitch": 3.1,
  "loadOffsetX": -0.18, "loadOffsetY": 0.04,
  "riskLevel": "WARNING",
  "riskCode": "LOAD_TILT_EXCEEDED",
  "message": "화물이 좌측으로 과도하게 기울었습니다.",
  "source": "VISION",
  "detectedAt": "2026-07-29T10:30:00+09:00"
}
```

| 항목 | 값 |
|---|---|
| 필수 | `vehicleId`, `riskLevel`, `detectedAt` |
| `riskLevel` | `NORMAL` / `CAUTION` / `WARNING` / `DANGER` / `UNKNOWN` |
| `source` | `VISION` / `SENSOR` / `ROS2` / `UNKNOWN` |
| 나머지 | 전부 선택(없으면 화면에 `—`로 표시) |
| 발행 주기 | **협의 필요** — 프론트 stale 임계값(현재 15초 잠정)이 여기에 의존 |

> **riskLevel은 발행 측이 최종 판정해 보낸다.** 백엔드는 저장·중계만 하고, 프론트는 표시만 한다.
> 어느 쪽도 roll/pitch로 위험도를 계산하지 않는다.

---

## 13. 종료 방법

```bash
# Next.js / Spring Boot — 포그라운드면 Ctrl+C
# Spring Boot 백그라운드
pkill -f 'fast-backend-.*\.jar'

# Mosquitto (데이터 유지)
cd infra/mqtt && docker compose down
```

> `docker compose down -v`는 **볼륨까지 삭제**한다. 브로커 영속 메시지가 사라진다.

---

## 14. 장애 대응

| 증상 | 확인 | 원인 | 해결 |
|---|---|---|---|
| `Table 'vehicle' doesn't exist` | `SHOW TABLES` | 스키마 미적용 | §4 |
| `Failed to configure DataSource` | 로그 | DB env 미주입 | `DB_URL` 등 확인 |
| `Access denied` | `mysql -u <user> -p` | 자격증명 오류 | §3 |
| Timezone 오류 | `DB_URL` | `serverTimezone` 누락 | URL 수정 |
| `Port 8080 already in use` | `ss -lntp \| grep 8080` | 이전 프로세스 | 종료 후 재기동 |
| MQTT 재연결 반복 | 로그 | 브로커 미기동 | §5 (브로커 먼저) |
| **Orin에서 `Connection refused`** | `nc -vz <GPU_IP> 1883` | **127.0.0.1 바인딩** | `ORIN_NETWORK_ACCESS.md` |
| `CORS policy blocked` | 브라우저 콘솔 | Origin 미허용 | §8 |
| `SockJS 403` | Network 탭 | STOMP origin | §8 |
| 다른 PC에서 접속 실패 | — | `localhost` 사용 | §8 + 재빌드 |
| **가짜 차량 3대 표시** | `SELECT COUNT(*) FROM vehicle` | `SQL_INIT_MODE=always` | 즉시 `never` |
| `vehicle not registered` 경고 | 로그 | 차량 미등록 | §11 |
| `Vehicle ID mismatch` 경고 | 로그 | 토픽 ID ≠ payload ID | §12 |
| 적재 안전 계속 미수신 | — | **발행 주체 없음** | §12 |
| 위험 링 안 보임 | — | 적재 데이터 자체가 없음 | 동일 |

---

## 실행 순서 요약

```
1.  서버 환경 점검          ./scripts/check-gpu-server.sh
2.  MySQL 기동
3.  schema.sql 최초 적용    ./scripts/init-db-schema.sh
4.  Mosquitto 기동          cd infra/mqtt && docker compose up -d
5.  Spring Boot 기동        ./mvnw spring-boot:run
6.  REST 확인               ./scripts/smoke-test-api.sh
7.  Next.js build/start     npm ci && npm run build && npm run start
8.  빈 데이터 화면 확인      (§10 — 차량 0대가 정상)
9.  실제 차량 등록          POST /api/vehicles
10. 상태·위치 데이터 연동    (비제어 토픽으로 검증)
11. Isaac Sim               (차량 ID 정합화 후)
12. Orin                    (브로커 바인딩 해결 후)
13. 실제 명령               ★ 별도 안전 검증 절차 후
```

각 단계가 성공해야 다음으로 넘어간다.
