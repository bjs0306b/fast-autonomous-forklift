# FAST MQTT Broker (Eclipse Mosquitto)

Jira `MQTT 브로커 구축·구독 연결`의 **브로커 실행 구성**입니다. 백엔드(Spring)와 ROS2 브리지가
붙는 로컬 개발용 브로커를 재현 가능하게 띄우기 위한 파일이 들어 있습니다.

```
infra/mqtt/
├── docker-compose.yml        # Mosquitto 2.0 컨테이너 정의
├── config/mosquitto.conf     # 브로커 설정 (로컬 개발 전용)
├── data/                     # 영속 메시지 저장 (.gitkeep 만 커밋)
├── log/                      # 브로커 로그 (.gitkeep 만 커밋)
└── README.md
```

---

## 1. 실행 방법

### 1-A. Docker 로 실행 (권장)

```bash
cd infra/mqtt
docker compose up -d
docker compose ps
docker compose logs --tail=100 mosquitto
```

종료:

```bash
docker compose down        # 데이터 유지
docker compose down -v     # 데이터까지 삭제
```

### 1-B. Docker 없이 실행

이 프로젝트를 개발 중인 일부 PC에는 Docker 가 설치돼 있지 않습니다. Docker 설치를 강제하지 않고
직접 설치한 Mosquitto 를 그대로 써도 됩니다.

**Windows** (현재 개발 PC에서 실제로 이 방식으로 검증했습니다)

```powershell
winget install EclipseFoundation.Mosquitto
# 설치 시 Windows 서비스로 등록되며 부팅 시 자동 실행됩니다.
Get-Service mosquitto
Get-NetTCPConnection -LocalPort 1883 -State Listen
```

실행 파일은 PATH 에 등록되지 않으므로 전체 경로로 호출합니다.

```powershell
& 'C:\Program Files\mosquitto\mosquitto_sub.exe' --help
```

**Ubuntu / EC2**

```bash
sudo apt-get update
sudo apt-get install -y mosquitto mosquitto-clients
sudo systemctl enable --now mosquitto
sudo systemctl status mosquitto
sudo ss -lntp | grep 1883
```

`infra/mqtt/config/mosquitto.conf` 를 쓰려면:

```bash
sudo cp infra/mqtt/config/mosquitto.conf /etc/mosquitto/conf.d/fast.conf
sudo systemctl restart mosquitto
```

> **주의**: 직접 설치한 Mosquitto 는 설치본 기본 설정으로 동작합니다. Mosquitto 2.x 는 설정에
> `listener` 가 하나도 없으면 **localhost(127.0.0.1, ::1)에만 바인딩하고 익명 접속을 허용**합니다.
> 로컬 개발에는 안전하지만, 이 상태는 저장소가 관리하는 설정이 아니라 설치본 기본값이라는 점을
> 인지하고 있어야 합니다.

---

## 2. 연결 확인

### 2-A. 브로커 자체 확인 (Pub/Sub 왕복)

터미널 두 개를 씁니다. Windows 는 실행 파일 전체 경로를 쓰세요.

**Subscriber**

```bash
mosquitto_sub -h localhost -p 1883 -t 'fast/test/connection' -q 1 -v
```

**Publisher**

```bash
mosquitto_pub -h localhost -p 1883 -t 'fast/test/connection' -q 1 -m 'mqtt-connected'
```

Subscriber 쪽에 `fast/test/connection mqtt-connected` 가 출력되면 성공입니다.

### 2-B. 백엔드 연결·구독 확인

DB 자격증명 없이 MQTT 경로만 검증하려면 **`mqttcheck` 프로필**을 씁니다
(임베디드 H2 + 실제 브로커 연결. 자세한 내용은 `src/main/resources/application-mqttcheck.yml` 주석 참고).

```bash
mvn spring-boot:run "-Dspring-boot.run.profiles=mqttcheck" "-Dspring-boot.run.useTestClasspath=true"
```

> `useTestClasspath=true` 가 필요한 이유: H2 는 `pom.xml` 에서 `<scope>test</scope>` 입니다.
> 운영 의존성을 늘리지 않으려고 그대로 두었기 때문에, 이 프로필로 띄울 때만 테스트 classpath 를
> 빌려 씁니다. 이 옵션 없이 실행하면 `Cannot load driver class: org.h2.Driver` 로 기동이 실패합니다.

기동 로그에서 다음 두 줄을 확인합니다.

```
MQTT inbound adapter starting: clientId=..., topics=[forklift/+/status, ... ], qos=[1, 1, 1, 1, 1, 1, 1, 1]
MQTT subscribed: bean=mqttInboundAdapter, message=Connected and subscribed to [...]
```

### 2-C. 상태 메시지 수신 확인

DB 에 등록된 차량 ID 만 씁니다(`src/main/resources/db/data-local.sql`: `SIM-F01` 한 대).
등록되지 않은 ID 로 보내면 백엔드가 `VEHICLE_NOT_FOUND` 로 폐기합니다.

```bash
mosquitto_pub -h localhost -p 1883 -t 'forklift/SIM-F01/status' -q 1 \
  -m '{"forkliftId":"SIM-F01","status":"MOVING","battery":87,"timestamp":"2026-07-24T15:40:24+09:00"}'
```

> **따옴표 주의**: PowerShell 에서 `-m` 에 JSON 을 직접 넘기면 큰따옴표가 제거돼 브로커에는
> 깨진 JSON 이 전달됩니다. PowerShell 을 쓴다면 파일로 발행하세요.
> ```powershell
> '{"forkliftId":"SIM-F01","status":"MOVING","battery":87,"timestamp":"2026-07-24T15:40:24+09:00"}' |
>   Set-Content -Encoding utf8 status.json
> & 'C:\Program Files\mosquitto\mosquitto_pub.exe' -h localhost -p 1883 `
>   -t 'forklift/SIM-F01/status' -q 1 -f status.json
> ```

백엔드 로그에서 순서대로 확인합니다.

```
MqttMessageReceiver  - MQTT message received: topic=forklift/SIM-F01/status, qos=1, retained=false, payloadBytes=...
ForkliftStatusService - Forklift status updated: forkliftId=SIM-F01, status=MOVING, battery=87
VehicleStatusService  - Vehicle current status updated: vehicleId=SIM-F01, status=MOVING
VehicleWebSocketBroadcaster - Vehicle WebSocket event broadcast sent: ... eventType=VEHICLE_STATUS_UPDATED
```

DB 반영은 REST 로 확인합니다.

```bash
curl -s http://localhost:8080/api/vehicles/SIM-F01
curl -s "http://localhost:8080/api/vehicles/SIM-F01/status-history?limit=5"
```

### 2-D. 명령 발행 확인

> **안전 규칙**: 실제 차량·임베디드가 붙어 있는 브로커에서는 명령 테스트를 하지 마세요.
> 아래는 아무 장비도 연결되지 않은 **로컬 브로커 전용** 절차입니다.

command 토픽을 먼저 구독한 뒤,

```bash
mosquitto_sub -h localhost -p 1883 -t 'forklift/+/command' -q 1 -v
```

REST 로 명령을 발행합니다.

```bash
curl -X POST http://localhost:8080/api/vehicles/SIM-F01/commands \
  -H "Content-Type: application/json" \
  -d '{"command":"FORK_UP","reason":"broker verification"}'
```

Subscriber 에 통합 envelope 가 그대로 도착해야 합니다.

```
forklift/SIM-F01/command {"commandId":"...","vehicleId":"SIM-F01","targetSystem":"EMBEDDED",
"commandCategory":"FORK","command":"FORK_UP","payload":{},"reason":"...","timestamp":"...+09:00"}
```

### 2-E. retained false 확인

명령을 발행한 뒤 Subscriber 를 **완전히 종료**하고, 같은 토픽으로 **새로** 붙습니다.

```bash
mosquitto_sub -h localhost -p 1883 -t 'forklift/+/command' -q 1 -v -W 6
```

과거 명령이 즉시 도착하지 않으면 `retained=false` 가 실제로 적용된 것입니다.
(retained 였다면 브로커가 마지막 메시지를 새 구독자에게 즉시 전달합니다.)

### 2-F. 재연결 확인

Docker 로 띄운 브로커에서만 수행하세요. 운영 서비스는 중지하지 않습니다.

```bash
# 1) 백엔드가 붙은 상태에서
docker compose stop mosquitto
#    백엔드 로그: MQTT connection failed / disconnected
docker compose start mosquitto
#    백엔드 로그: MQTT subscribed ... (토픽 재구독)
```

> 재연결은 지수 백오프이며 `mqtt.recovery-interval`(기본 5000ms)은 **고정 주기가 아니라 백오프
> 상한**입니다. 과거 실측에서 끊김 감지부터 재구독까지 약 88초가 걸린 사례가 있습니다
> (`MqttConfig` 주석 참고). 몇 초 안에 안 붙는다고 실패로 판단하지 마세요.

---

## 3. 환경변수

브로커 주소·계정은 설정 파일이 아니라 환경변수로 주입합니다. 템플릿은 `.env.example` 이며,
실제 값을 넣은 `.env` 는 `.gitignore` 로 Git 추적에서 제외돼 있습니다.

| 대상 | 변수 | 기본값 |
|---|---|---|
| Backend | `MQTT_BROKER_URL` | `tcp://70.12.130.106:1883` |
| Backend | `MQTT_USERNAME` / `MQTT_PASSWORD` | 빈 값(익명) |
| Backend | `MQTT_INBOUND_CLIENT_ID` | `fast-backend-inbound` |
| Backend | `MQTT_OUTBOUND_CLIENT_ID` | `fast-backend-outbound` |
| Backend | `MQTT_ENABLED` | `true` (test 프로필만 `false`) |
| Backend | `MQTT_DEFAULT_QOS` | `1` |
| ROS2 Bridge | `MQTT_BROKER_HOST` / `MQTT_BROKER_PORT` | `localhost` / `1883` |
| ROS2 Bridge | `MQTT_CLIENT_ID` | `fast-mqtt-bridge` |
| ROS2 Bridge | `VEHICLE_ID` | `REAL-F01` (실물 지게차 전용 — 아래 주의 참고) |

> **ROS2 브리지의 `VEHICLE_ID` 를 `SIM-F01` 로 바꾸지 마세요.** Isaac Sim `twin_bridge.py` 가
> 이미 `SIM-F01` 로 상태·위치를 발행하고 있어, 두 송신자가 같은 ID 를 쓰면 서로의 상태를
> 덮어씁니다. `REAL-F01` 은 현재 관제 DB 에 등록돼 있지 않으므로 백엔드가 그 메시지를 폐기하며,
> 이는 의도된 동작입니다(관제 대상은 `SIM-F01` 한 대).

> **clientId 중복 금지**: 같은 clientId 로 두 클라이언트가 접속하면 먼저 붙어 있던 쪽이 브로커에서
> 끊깁니다. 백엔드 inbound/outbound/브리지가 서로 다른 값을 쓰도록 되어 있으니 새 클라이언트를
> 추가할 때도 반드시 새 ID 를 부여하세요.

---

## 4. 로컬과 운영(EC2)의 차이

| 항목 | 로컬 개발 | 운영(EC2) |
|---|---|---|
| 익명 접속 | 허용 (`allow_anonymous true`) | **금지** — `allow_anonymous false` + `password_file` |
| 포트 노출 | `127.0.0.1:1883` 로만 바인딩 | Security Group 에서 백엔드/차량 IP 또는 VPC 내부로만 제한 |
| 1883 전체 공개 | 해당 없음 | **절대 금지** (0.0.0.0/0 으로 열지 말 것) |
| 비밀번호 | 사용 안 함 | `.env` 로 주입, 저장소에 커밋 금지 |

운영에서 인증을 켜는 절차:

```bash
# 1) 비밀번호 파일 생성 (infra/mqtt/config/passwd 는 .gitignore 대상)
docker run --rm -it -v "$PWD/config:/mosquitto/config" eclipse-mosquitto:2.0 \
  mosquitto_passwd -c /mosquitto/config/passwd fast-backend

# 2) mosquitto.conf 수정
#    allow_anonymous false
#    password_file /mosquitto/config/passwd

# 3) docker-compose.yml 의 passwd 마운트 주석 해제 후 재기동
docker compose up -d --force-recreate
```

---

## 5. 실제 검증 완료 범위 (2026-07-24, 개발 PC)

이 PC 에는 Docker 가 없어 **Windows 서비스로 설치된 Mosquitto**(localhost:1883)를 대상으로 검증했습니다.

| 항목 | 결과 |
|---|---|
| 브로커 기동 (1883 LISTEN) | 확인 — `127.0.0.1:1883`, `::1:1883` |
| `mosquitto_pub`/`sub` 왕복 | **성공** — `fast/test/connection mqtt-connected` |
| 백엔드 → 브로커 연결 | **성공** — `mqttcheck` 프로필 |
| 8개 토픽 구독 (QoS 전부 1) | **성공** — 어댑터 로그로 확인 |
| 상태 메시지 수신 → Router → Service → DB → WebSocket | **성공** — `REAL-F01`, `MOVING`, battery 87 |
| 명령 발행 (QoS 1) | **성공** — `forklift/REAL-F01/command` 수신 확인 |
| retained false 실동작 | **성공** — 재구독 시 과거 명령 미전달 |
| 잘못된 JSON 폐기 후 계속 동작 | **성공** — 폐기 로그 후 다음 메시지 정상 처리 |

**미검증 (이 문서 작성 시점)**

- Docker Compose 구성 자체의 기동 (이 PC 에 Docker 미설치 — 구성 파일만 제공)
- 브로커 중지/재시작을 통한 **재연결 실동작** (프로젝트 규칙상 Docker 로컬 브로커에서만 수행하며,
  Windows 서비스는 임의로 중지하지 않았습니다)
- EC2 브로커 연동 및 인증(`allow_anonymous false`) 적용
- ROS2 `fast_mqtt_bridge` ↔ 브로커 실제 연결 (ROS2 런타임 없음)
- 실제 차량·임베디드의 명령 수신 및 `command-result` 회신
