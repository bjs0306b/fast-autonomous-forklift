# fast-backend

F.A.S.T. — AIoT 기반 무인 지게차 물류 자동화 시스템의 Spring Boot 백엔드입니다.

현재 저장소에는 Spring Boot 공통 기반뿐 아니라 MQTT 수신·명령 발행, MySQL/MyBatis 차량 상태·명령 저장,
WebSocket 이벤트 중계가 구현되어 있습니다. 실제 Mosquitto/ROS2/임베디드와의 end-to-end 연동 완료 여부는
코드 구현·자동 테스트와 구분해 각 문서의 `외부 연동 확인 필요` 항목으로 관리합니다.

## 시스템 구성 (최종 목표)

```
ROS2 / Jetson / Isaac Sim → MQTT Broker → Spring Boot → MySQL → REST API / WebSocket → React 관제 화면
```

## 개발 환경

| 항목 | 내용 |
|---|---|
| Java | 21 |
| Build Tool | Maven |
| Spring Boot | 3.3.x (Java 21 호환 안정 버전) |
| Packaging | Jar |
| Group | com.fast |
| Artifact | backend |
| Base Package | com.fast.backend |
| Lombok | 사용하지 않음 |
| Test | JUnit 5, Spring Boot Test (MockMvc) |

## 패키지 구조

```
com.fast.backend
├─ FastBackendApplication.java   # 애플리케이션 진입점
├─ common
│  ├─ api                        # 공통 API 응답 구조 (ApiResponse, ErrorResponse)
│  └─ exception                  # 공통 예외 처리 (ErrorCode, BusinessException, GlobalExceptionHandler)
├─ health
│  └─ controller                 # 서버 상태 확인 API (GET /api/health)
├─ config
│  └─ mqtt                       # MQTT 설정 (MqttProperties, MqttConfig, MqttTopics)
├─ mqtt                          # MQTT 연동 (S15P11A304-89)
│  ├─ gateway                    #   - MqttGateway (@MessagingGateway, 발행 진입점)
│  ├─ inbound                    #   - MqttMessageReceiver / MqttMessageRouter / MqttErrorChannelHandler
│  ├─ outbound                   #   - MqttPublisher, MqttPublishException
│  ├─ service                    #   - MqttConnectionEventListener (연결/구독/발행 이벤트 로그)
│  └─ controller                 #   - MqttTestController (검증용 임시 발행 API)
├─ forklift                      # 지게차(차량) 도메인
│  ├─ dto                        #   - ForkliftStatusMessage / ForkliftLocationMessage / ForkliftCommandMessage
│  ├─ service                    #   - ForkliftStatusService / ForkliftLocationService (상태·위치 메시지 처리, 아직 DB 저장 없음)
│  ├─ controller                 #   - (준비 단계, 아직 없음)
│  └─ repository                 #   - (준비 단계, 아직 없음)
├─ task                          # (준비 단계) 작업 배정 도메인
│  ├─ controller / dto / service / repository
└─ alert                         # (준비 단계) 경보/알림 도메인
   ├─ controller / dto / service / repository
```

`task/*`, `alert/*`, `forklift/controller`, `forklift/repository` 하위 패키지는 아직 실제 클래스 없이
디렉터리만 준비되어 있습니다. 빈 패키지를 유지하기 위한 가짜(placeholder) 클래스는 만들지 않았습니다.
`config/mqtt`, `mqtt/*`, `forklift/dto`, `forklift/service`는 S15P11A304-89(MQTT 브로커 구축·구독 연결)
단계에서 실제 코드가 채워졌습니다.

## MQTT 연동 (S15P11A304-89)

Spring Integration MQTT + Eclipse Paho MQTT v3로 MQTT Broker(Eclipse Mosquitto) 연결, 구독, 발행을 구현했다.

- 구독 토픽(8종, 백엔드가 실제 사용하는 MQTT QoS는 전부 **1**): `forklift/+/status`, `forklift/+/location`,
  `forklift/+/path`, `forklift/+/command-result`, `forklift/+/fork-status`, `forklift/+/error`,
  `cargo/detected`, `fast/station/+/measurement`. QoS는 `mqtt.default-qos`(로컬 기본 1) 하나를 8개 토픽에
  균등 적용한다(`MqttConfig.mqttInboundAdapter`, `MqttConfigTest`로 회귀 검증).
- 발행 토픽(1종): `forklift/{vehicleId}/command` — 이동(ROS2)·포크/적재(임베디드)·비상정지를 모두
  이 토픽 하나로 발행한다. payload의 `targetSystem`/`commandCategory`로 수신 측이 분기한다.
  **MQTT QoS 1, retained false**(확정, `VehicleCommandPublisher`의 코드 상수 — 설정값을 참조하지 않음).
  구 `forklift/{id}/emergency` 전용 토픽은 제거됐다.
- **백엔드가 보장하는 범위**: ① 발행 시 QoS 1/retained false(코드 상수) ② 구독 시 QoS 1(`mqtt.default-qos`).
  **외부 연동 확인 필요**: ROS2/Isaac/임베디드/AI/스테이션이 실제로 QoS 1로 발행하는지는 백엔드 코드만으로
  보장할 수 없다 — 저장소만으로는 확인 불가.
- 수신 흐름: `MqttPahoMessageDrivenChannelAdapter` → `mqttInputChannel` → `MqttMessageReceiver` → `MqttMessageRouter` → (`ForkliftStatusMessage`/`ForkliftLocationMessage`로 역직렬화 후 처리, 알 수 없는 토픽은 경고 로그, 잘못된 JSON은 오류 로그만 남기고 계속 동작)
- 발행 흐름: 도메인 Service → `MqttPublisher` → `MqttGateway` → `mqttOutboundChannel` → `MqttPahoMessageHandler` → MQTT Broker
- 접속 설정은 `application-local.yml`의 `mqtt.*`를 `MqttProperties`(`@ConfigurationProperties`)로 바인딩해서 사용하며, 코드에 하드코딩하지 않는다. 주요 접속 값(`enabled`, `test-api.enabled`, `broker-url`, `username`, `password`, `inbound-client-id`, `outbound-client-id`, `default-qos`)은 `${ENV_VAR:기본값}` 형태로 환경변수 오버라이드가 가능하다(아래 "EC2 Mosquitto Broker 연동" 참고).
- Inbound(`fast-backend-inbound`)와 Outbound(`fast-backend-outbound`) Client ID는 분리되어 있고, `MqttPahoClientFactory` 하나를 공유한다.
- 검증용 임시 API: `POST /api/mqtt/test` (`{"topic":"...","payload":"...","qos":1,"retained":false}`) — 실제 지게차 제어 API가 아니며, 이번 Jira 이슈 검증 목적으로만 존재한다. `mqtt.test-api.enabled` 프로퍼티로 켜고 끄며, **기본값은 `false`(비활성화)**다(prompt9.md 기준 변경 — 값을 명시하지 않은 환경에서는 노출되지 않는 것이 더 안전하다는 판단). 로컬 개발 환경(`application-local.yml`)에서는 이 값을 `true`로 명시적으로 켜뒀다(`@Profile`이 아니라 `@ConditionalOnProperty`를 쓰는 이유는 `answer7.md` 8장 참고).
- MQTT의 broker 연결 Bean(Client Factory/Inbound Adapter/Outbound Handler)은 `mqtt.enabled`(기본값
  `true`)로 켜고 끌 수 있다. `src/test/resources/application-test.yml`에서는 `false`로 두므로 실제
  Broker에 접속하지 않는다. `@ServiceActivator`가 참조하는 내부 channel은 Spring Integration이 자동
  생성할 수 있지만 Paho 연결 Bean이 없어 외부 접속은 발생하지 않는다.
- 수신 경계는 전체 payload 대신 byte 길이만 로그에 남긴다. null/blank/non-object/잘못된 JSON은
  Router가 해당 메시지만 폐기하고, 예상하지 못한 Service 예외도 Router와 Receiver의 이중 경계에서
  consumer thread 밖으로 전파되지 않는다.
- gateway 발행 실패는 `MqttPublishException`으로 변환된다. `VehicleCommandService`는 이를 받아 DB
  상태를 `PUBLISH_FAILED`로 저장하며, gateway 호출 성공은 broker/차량 수신 성공과 구분한다.
- 통신 규격(JSON 필드)은 2026-07-24 팀 확정 규격으로 갱신됐다(아래 "확정 통신 규격 요약" 참고).
- 실제 로컬 Mosquitto(winget으로 설치, Windows 서비스로 상시 구동)를 대상으로 구독 성공, 상태/위치/cargo 메시지 수신, 잘못된 JSON 무시, `POST /api/mqtt/test` → `mosquitto_sub` 수신, Broker 재기동 후 자동 재연결까지 실제로 검증했다(`prompt/answer/answer5.md` 참고).

### 확정 통신 규격 요약 (2026-07-24)

팀 확정 규격을 코드에 반영했다. 상세는 `docs/backend-message/communication-protocol.md` 참고.

- **시각**: 모든 통신 시각은 Asia/Seoul `+09:00` ISO-8601 `OffsetDateTime`
  (예: `2026-07-23T11:20:27+09:00`). DB에는 Asia/Seoul 벽시계로 저장하고 읽을 때 오프셋을 복원한다.
- **차량 상태 10종**: `UNKNOWN, IDLE, ACTIVE, MOVING, LIFTING, LOADING, UNLOADING, ESTOP, ERROR, OFFLINE`.
  `MOVING`을 `ACTIVE`로 변환하던 기존 정규화는 폐지됐다 — 어떤 값도 다른 값으로 흡수되지 않는다.
- **좌표·방향**: 좌표 단위 m, `frameId`는 `map`/`odom`만 허용(기본 `map`), 방향 필드는 `heading`
  (단위 degree, [0,360) 정규화). Isaac의 구 `direction` 필드는 과도기 읽기 alias로만 허용한다.
- **Isaac 확장 필드 DB 저장**: `forkHeight`/`hasCargo`/`cargoId`/`footprint`가 `vehicle_current_status`와
  `vehicle_status_history`에 저장된다. ROS2 상태 메시지는 이 값들을 null로 덮어쓰지 않고 보존한다.
- **명령 공통 envelope**: `{commandId, vehicleId, targetSystem, commandCategory, command, payload, reason, timestamp}`.
  `commandId`(UUID)와 `timestamp`는 백엔드가 생성하며, 잘못된 조합은 발행하지 않고 400으로 거부한다.
- **WebSocket 공통 envelope**: 차량·AI·스테이션 이벤트가 모두
  `{eventType, vehicleId, occurredAt, data}` 형태를 공유한다(`vehicleId`는 nullable).

운영 DB에 적용할 마이그레이션 SQL은
`src/main/resources/db/migration/2026-07-24-unified-command-and-isaac-status.sql`에 있다
(schema.sql과 마찬가지로 **수동 실행 전제**).

### 알려진 제한사항

- **애플리케이션/테스트 종료 시 약 30초 지연**: `MqttPahoMessageDrivenChannelAdapter.doStop()`이 호출하는 `MqttAsyncClient.disconnectForcibly(long)`이 Eclipse Paho 라이브러리 내부에 **quiesceTimeout을 30000ms로 하드코딩**하고 있어서 발생한다(바이트코드 역어셈블로 확인, `answer7.md` 3장 참고). Spring Integration MQTT 6.3.4가 이 값을 조정할 수 있는 공개 API를 제공하지 않아, 안전하게 확정된 해결책이 없는 상태로 **알려진 제한**으로 남겨뒀다. 단, `mqtt.enabled=false`인 테스트(`application-test.yml`)에서는 애초에 Adapter/Client가 생성되지 않아 이 지연이 발생하지 않는다.

### EC2 Mosquitto Broker 연동 (S15P11A304-89 확장)

로컬 개발용 Mosquitto뿐 아니라 팀이 공유하는 AWS EC2 Mosquitto Broker에도 코드 변경 없이 연결할 수 있도록,
`application-local.yml`의 MQTT 접속 값을 환경변수로 오버라이드할 수 있게 구성했다.

```env
# 로컬 Spring Boot → EC2 공용 Broker
MQTT_BROKER_URL=tcp://<EC2_PUBLIC_IP>:1883
MQTT_USERNAME=<username>
MQTT_PASSWORD=<password>

# EC2에서 직접 실행하는 Spring Boot → 같은 EC2의 Broker
MQTT_BROKER_URL=tcp://localhost:1883
MQTT_USERNAME=<username>
MQTT_PASSWORD=<password>
```

환경변수를 아무것도 설정하지 않으면 기존과 동일하게 `tcp://localhost:1883`(인증 없음)으로 접속한다.
비밀번호는 코드/Git에 직접 작성하지 않고 실행 시점의 환경변수(`.env`, 쉘 환경변수, 배포 시크릿 등)로만 주입한다.

EC2 Mosquitto 설치·설정 가이드, 보안 그룹 정책, 통합 테스트 절차, 재연결 테스트, 오류 점검표는
`prompt/answer/answer11.md`에 상세히 정리되어 있다.

## 공통 API 응답 형식

성공:

```json
{
  "success": true,
  "data": {},
  "error": null
}
```

실패:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "잘못된 요청입니다."
  }
}
```

`ApiResponse<T>`는 Generic으로 작성되어 있으며, `ApiResponse.success(data)` / `ApiResponse.fail(errorResponse)`
정적 팩토리 메서드로 생성합니다. 오류 코드는 `ErrorCode` Enum(`INVALID_REQUEST`, `VALIDATION_FAILED`,
`METHOD_NOT_ALLOWED`, `JSON_PARSE_ERROR`, `INTERNAL_SERVER_ERROR`)으로 관리하며, 각 코드는 코드 문자열/기본 메시지/HTTP 상태를 함께 가집니다.
`GlobalExceptionHandler`(`@RestControllerAdvice`)가 Validation 실패, `BusinessException`, 잘못된 HTTP Method,
JSON 파싱 실패, `IllegalArgumentException`, 그 외 예외를 모두 위 형식으로 변환합니다.

## Health API

```
GET /api/health
```

응답 예시 (200 OK):

```json
{
  "success": true,
  "data": {
    "status": "UP",
    "service": "fast-backend"
  },
  "error": null
}
```

## 실행 방법

### IntelliJ에서 실행

1. IntelliJ에서 이 디렉터리(`fast-backend`)를 Maven 프로젝트로 열기
2. Maven 창에서 **Reload All Maven Projects** 실행 (의존성 새로고침)
3. `FastBackendApplication`을 실행

### 커맨드라인에서 실행

```bash
mvn clean package
mvn spring-boot:run
```

또는 빌드된 jar 실행:

```bash
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

기본 포트는 `8080`이며, 기본 활성 프로필은 `local`입니다(`application-local.yml`).

### 테스트 실행

```bash
mvn test
```

- `FastBackendApplicationTests`: Spring 컨텍스트 정상 로딩 확인
- `HealthControllerTest`: `MockMvc`로 `GET /api/health` 호출 후 응답 형식 검증

## 설정 파일

- `application.yml`: 애플리케이션 이름, 기본 프로필(`local`), 서버 포트(`8080`), Jackson 공통 설정(널 필드 생략 방지), 기본 로그 레벨
- `application-local.yml`: 로컬 전용 설정. `mqtt.*`(브로커 접속 정보, 토픽 패턴)를 포함하며, DB 등 향후 설정이 추가될 위치를 주석으로 표시
- `logback-spring.xml`: 콘솔 Appender와 로그 패턴(날짜, 레벨, 스레드, Logger, 메시지)을 정의. 비밀번호/토큰 등 민감한 값은 어떤 로그 레벨에서도 출력하지 않습니다.

## 로깅

- `System.out.println()`을 사용하지 않고 SLF4J `Logger`를 직접 사용합니다(Lombok `@Slf4j` 미사용).
- 민감 정보(비밀번호, 토큰, 인증키 등)는 로그에 출력하지 않습니다.

## 이번 단계에서 구현하지 않은 것

- ROS2 Node 실제 구현, Isaac Sim 연동
- MySQL 연결, MyBatis Mapper, Entity/DB 테이블 (지게차 상태/위치는 현재 DB 저장 없이 로그만 남긴다)
- WebSocket/STOMP 연결, React 화면 연동
- 차량 등록, 작업 배정, 이동·정지·비상정지 실제 제어, 충돌 방지, 다중 차량 관제, AI 추론/화물 적재 위치 추천

이 기능들은 `forklift`, `task`, `alert` 패키지 위치에 이후 단계에서 추가될 예정입니다. MQTT Broker 연결/구독/발행은
S15P11A304-89 단계에서 구현이 완료되었다(위 "MQTT 연동" 절 참고).
