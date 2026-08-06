# fast_mqtt_bridge

FAST 백엔드 통신 규격에 맞춘 ROS2 ↔ MQTT 브리지입니다. 기존 `forklift_teleop`의
`/cmd_vel` → UART 경로는 수정하지 않습니다.

## 데이터 흐름

```text
ROS2 state / Odometry / Path
  → fast_mqtt_bridge → MQTT broker → Spring Backend → DB/WebSocket

Spring Backend
  → forklift/{vehicleId}/command → fast_mqtt_bridge
  → ROS2 command adapter
  → forklift/{vehicleId}/command-result → Spring Backend
```

> ✅ **MOVE 는 2026-08-03에 연결됐다**(S15P11A304-192·193). `nav2_adapter.py` ·
> `nav2_goal_sender.py` 가 `NavigateToPose` 액션으로 실행하고, 회전 없이 도는 행동트리
> (`forklift_teleop/behavior_trees/navigate_to_pose_no_spin.xml`)를 물렸습니다.
> **배선까지만 검증됐고 실주행은 미검증**입니다.
>
> ⚠️ **`/cmd_vel` 인계 규칙이 없습니다**(S15P11A304-**199**). Nav2 와 포크 정렬 루프
> (`ai/scripts/onboard_fork_align_node.py`)가 같은 토픽을 동시에 잡을 수 있어, 지금은
> 포크 정렬 전에 사람이 Nav2 를 내립니다.

MOVE 를 뺀 나머지는 아직 연결되지 않았습니다 — 정지·비상정지 service, 상태 원본
message type 가 저장소에 없습니다. 따라서 EMERGENCY_STOP 을 임의 토픽으로 보내지 않으며
기본 adapter 는 `REJECTED` 결과를 회신합니다. mock adapter 통합 테스트로 명령 전달·성공·
실패 결과 흐름을 검증했고, 실제 제어 adapter 는 팀이 ROS2 interface 를 확정한 뒤
연결해야 합니다.

## 조사된 ROS2 연결

| 데이터/명령 | ROS2 interface | type | 현재 연결 |
|---|---|---|---|
| 텔레옵 | `/cmd_vel` | `geometry_msgs/msg/Twist` | 기존 `forklift_teleop` 구독(변경 없음) |
| 위치 | parameter `location_topic` | `nav_msgs/msg/Odometry` | 토픽을 명시한 경우만 연결 |
| 경로 | parameter `path_topic` | `nav_msgs/msg/Path` | 토픽을 명시한 경우만 연결 |
| 상태 | 팀 확인 필요 | 팀 확인 필요 | adapter API만 제공 |
| MOVE | `navigate_to_pose` (action) | `nav2_msgs/action/NavigateToPose` | ✅ **연결됨**(`nav2_adapter.py`, 2026-08-03). 배선까지만 검증 |
| STOP | 백엔드상 `EMBEDDED+SAFETY` | 팀 확인 필요 | ROS2에서 실행 안 함 |
| EMERGENCY_STOP | `ALL+SAFETY` | 팀 확인 필요 | 실제 모터 제어 안 함, REJECTED |
| 포크/적재 | 임베디드 대상 | 임베디드 규격 | ROS2에서 실행 안 함 |

## MQTT 토픽

| 토픽 | 방향 | QoS | retained | 현재 백엔드 payload |
|---|---|---:|---|---|
| `forklift/{id}/status` | ROS2 → Backend | 1 | false | `forkliftId,status,battery(int),timestamp` |
| `forklift/{id}/location` | ROS2 → Backend | 1 | false | `vehicleId,position,heading,messageAt` |
| `forklift/{id}/path` | ROS2 → Backend | 1 | false | `forkliftId,waypoints,goal,timestamp` |
| `forklift/{id}/command` | Backend → ROS2 | 1 | 해당 없음 | 통합 command envelope |
| `forklift/{id}/command-result` | ROS2 → Backend | 1 | false | `result,completedAt` 기반 결과 envelope |

프롬프트 예시와 현재 Spring DTO가 다른 경우 현재 백엔드를 우선했습니다.

- 상태 배터리: 백엔드 `int`이므로 소수 배터리는 거부합니다.
- 위치: 평면 `x/y`가 아니라 `position` 중첩 구조이며 시간 필드는 `messageAt`입니다.
- 경로: `path[]`가 아니라 현재 `IsaacForkliftPathMessage`의 `waypoints + goal` 구조입니다.
- 결과: `status/SUCCEEDED/timestamp`가 아니라
  `result/SUCCESS/completedAt`입니다.

## 설정

우선순위는 환경변수 > ROS parameter/YAML > 기본값입니다.

| 환경변수 | 기본값 |
|---|---|
| `MQTT_BROKER_HOST` | `localhost` |
| `MQTT_BROKER_PORT` | `1883` |
| `MQTT_USERNAME` | 빈 값 |
| `MQTT_PASSWORD` | 빈 값(YAML에 평문 저장 금지) |
| `MQTT_CLIENT_ID` | `fast-mqtt-bridge` |
| `MQTT_KEEPALIVE` | `60` |
| `MQTT_TLS_ENABLED` | `false` |
| `MQTT_CA_CERT` | 빈 값 |
| `VEHICLE_ID` | `REAL-F01` |
| `ROS_NAMESPACE` | 빈 값 |

```bash
export MQTT_PASSWORD='secret'
ros2 launch fast_mqtt_bridge mqtt_bridge.launch.py \
  vehicle_id:=REAL-F01 mqtt_host:=localhost mqtt_port:=1883 \
  location_topic:=/confirmed/odom path_topic:=/confirmed/path
```

EC2 TLS 브로커 예시:

```bash
export MQTT_BROKER_HOST='<broker-host-or-ip>'
export MQTT_BROKER_PORT='8883'
export MQTT_USERNAME='<username>'
export MQTT_PASSWORD='<password>'
export MQTT_TLS_ENABLED='true'
export MQTT_CA_CERT="$HOME/mqtt-certs/ca.crt"

ros2 launch fast_mqtt_bridge mqtt_bridge.launch.py \
  vehicle_id:=REAL-F01 location_topic:=/confirmed/odom
```

TLS를 활성화하면 CA 인증서가 반드시 존재해야 하며 서버 인증서의 hostname/IP 검증을
비활성화하지 않는다. CA 인증서와 실제 자격증명은 저장소에 커밋하지 않는다.

`status_topic`, `location_topic`, `path_topic` 기본값은 빈 문자열입니다. 확인되지 않은 토픽을
자동 구독하지 않습니다. `location_publish_interval_ms` 최솟값은 broker 과부하 방지를 위해
100ms이고, 상태 heartbeat 기본값은 1000ms입니다.

## 장애/중복 정책

- Paho network loop를 사용하며 재연결 지연은 1초부터 60초까지 exponential backoff입니다.
- MQTT callback은 명령을 thread-safe priority queue에 넣고 ROS2 timer가 소비합니다.
- EMERGENCY_STOP은 일반 명령보다 queue 우선순위가 높지만, 제어 interface가 확정되지 않아
  실제 모터를 작동하거나 정지시키지 않습니다.
- publish 실패 payload는 queue에 넣고 연결 복구 후 재시도합니다.
- commandId cache는 기본 TTL 1시간, 최대 1000개입니다. 종료 명령 재수신 시 재실행하지 않고
  마지막 결과를 다시 발행합니다. 재시작 후 cache는 유지되지 않습니다.
- 결과 전이는 `ACCEPTED → IN_PROGRESS → SUCCESS|FAILED|REJECTED|CANCELLED` 방향만 허용하며,
  종료 결과 이후의 역행/중복 종료를 차단합니다.
- MQTT publish는 QoS 1, retained false로 고정합니다.

## 테스트

외부 Mosquitto/ROS2 없이:

```bash
cd ros2_ws/src/fast_mqtt_bridge
PYTHONPATH=. python3 -m unittest discover -s test -p 'test_*.py' -v
```

ROS2 설치 환경:

```bash
cd ros2_ws
colcon test --packages-select fast_mqtt_bridge
colcon test-result --verbose
```

Mosquitto 수동 확인(기본 발행은 retained=false이므로 `mosquitto_pub`에 `-r`을 쓰지 않음):

```bash
mosquitto_sub -h localhost -p 1883 -t 'forklift/REAL-F01/#' -q 1 -v

mosquitto_pub -h localhost -p 1883 \
  -t 'forklift/REAL-F01/command' -q 1 \
  -m '{"commandId":"CMD-TEST-001","vehicleId":"REAL-F01","targetSystem":"ROS2","commandCategory":"MOVE","command":"MOVE","payload":{"destination":{"x":2.5,"y":4.1,"heading":90.0,"frameId":"map"}},"timestamp":"2026-07-24T10:00:00+09:00"}'
```

## 브로커 실행

브리지가 붙을 브로커 실행 구성은 저장소의 [`infra/mqtt/`](../../../infra/mqtt/README.md)에 있습니다.
Docker 가 있으면 `cd infra/mqtt && docker compose up -d`, 없으면 같은 문서의 직접 설치 절차를 따릅니다.

> **clientId 주의**: 브리지 기본값은 `fast-mqtt-bridge`, 백엔드는 `fast-backend-inbound`/
> `fast-backend-outbound` 입니다. 같은 clientId 로 접속하면 먼저 붙은 쪽이 브로커에서 끊기므로
> 차량을 여러 대 붙일 때는 `MQTT_CLIENT_ID` 를 차량별로 다르게 주세요.

## 미검증

- 실제 Mosquitto 접속, 끊김/재접속 및 QoS delivery
  (백엔드 ↔ 같은 브로커의 연결·구독·발행·retained 는 2026-07-24 검증 완료 —
  `infra/mqtt/README.md` 5절. **브리지 자체의 접속은 ROS2 런타임이 없어 여전히 미검증**)
- ROS2 Humble `colcon build`와 launch 기동
- 실제 차량 위치/경로 토픽
- Nav2 또는 다른 MOVE 제어 interface
- 실제 모터, 비상정지 하드웨어, reset/fail-safe
- 임베디드 포크·적재/하역 제어

