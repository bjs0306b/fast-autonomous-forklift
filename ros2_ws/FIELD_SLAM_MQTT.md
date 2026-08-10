# FR-304-2 현장 SLAM · Nav2 · MQTT 실행 절차

이 문서는 **실물 차량에서 센서 확인 → SLAM/Nav2 자동 한 바퀴 → 좌표 CSV와 지도
저장**까지 팀원이 그대로 수행할 수 있는 실행 기준서다. 실물 한 바퀴 작업은 우선 이
문서 하나를 처음부터 끝까지 읽고 진행한다.

Isaac Sim 또는 디지털 트윈까지 연결하는 작업자는 이 문서와 함께 반드시
[`../docs/isaac-sim-연동-인수인계.md`](../docs/isaac-sim-연동-인수인계.md)를 읽는다.
그 문서에는 ROS 도메인 분리, MQTT 토픽 두 계열, yaw 단위, `base_link` 기준점처럼
실물 단독 실행만으로는 드러나지 않는 연동 충돌이 정리돼 있다. 특히 **실물은
`ROS_DOMAIN_ID=100`, 시뮬레이터는 `ROS_DOMAIN_ID=0`**을 사용하며 두 환경의
`/cmd_vel`이 섞이지 않게 한다.

## 0. 주 코드 위치와 파일 지도

현장 SLAM·Nav2·센서 브리지·장애물 회피·UART 주행·자동 한 바퀴의 주 코드는 다음
디렉터리에 있다.

```text
/home/orin/S15P11A304/ros2_ws/src/forklift_teleop/
```

문제가 생겼을 때는 임시 복구 스크립트부터 실행하지 말고 아래 파일을 기준으로
실행 경로를 확인한다.

| 역할 | 파일 |
|---|---|
| 전체 센서·SLAM·Nav2·회피·주행 기동 | `src/forklift_teleop/launch/field_slam_nav2.launch.py` |
| SSH 단절 대응 자동 한 바퀴 실행 | `tools/field_lap_session.sh` |
| 자동 순환 목표와 안전 대기 조건 | `src/forklift_teleop/forklift_teleop/field_lap_mission.py` |
| 현장 waypoint·분할 길이 | `src/forklift_teleop/config/field_lap.yaml` |
| LiDAR·ToF 장애물 회피 | `src/forklift_teleop/forklift_teleop/obstacle_avoidance_node.py` |
| 회피 임계값 | `src/forklift_teleop/config/obstacle_avoidance.yaml` |
| `/cmd_vel_safe` → ESP32 UART | `src/forklift_teleop/forklift_teleop/uart_teleop_bridge.py` |
| 속도·조향·UART 설정 | `src/forklift_teleop/config/teleop.yaml` |
| 10 Hz 좌표 변환·CSV·MQTT | `src/fast_mqtt_bridge/fast_mqtt_bridge/orin_telemetry.py` |
| 실물 Nav2 파라미터 | `nav2_params.yaml` |

현재 작업 브랜치는 다음 이름을 사용한다.

```text
feature/S15P11A304-52-fr-304-2-회피-경로-통합-현장-튜닝
```

`tools/left_recovery.py`와 `tools/rear_recovery.py`는 막힌 차량을 제한 거리만큼
꺼내기 위한 **현장 복구용**이다. 정상적인 한 바퀴 주행에는 실행하지 않는다.

팀원이 자동으로 한 바퀴를 돌고 좌표를 가져오는 기본 실행 절차는 **§1의 빌드와
§2의 무주행 센서 점검을 통과한 뒤 §7을 실행하는 것**이다. §4는 RViz에서 목표를
하나씩 지정해야 할 때 사용하는 수동 대안이다.

이 절차는 한 번의 launch로 다음 데이터 흐름을 구성한다.

```text
YDLiDAR -> /scan -> RF2O -> /odom_rf2o --+
ESP32 IMU ----------------> /imu/data ----+-> EKF -> odom -> base_link
ESP32 좌/우 ToF ----------> /tof/{left,right}/points -> Nav2 local costmap
/scan + odom TF -> slam_toolbox -> map -> odom
Nav2 /cmd_vel -> LiDAR+ToF 회피 -> /cmd_vel_safe -> UART
map -> base_link + /odom -> 10 Hz MQTT + 순회 CSV
```

## 1. 빌드

```bash
cd /home/orin/S15P11A304/ros2_ws
export ROS_DOMAIN_ID=100
rosdep install --from-paths src --ignore-src -r -y
colcon build --symlink-install
source install/setup.bash
```

실차의 저장소 위치가 다르면 첫 경로만 바꾼다. MQTT 비밀번호와 CA 인증서는
저장소에 넣지 않는다.

## 2. 첫 기동: 바퀴를 움직이지 않고 확인

```bash
ros2 launch forklift_teleop field_slam_nav2.launch.py \
  nav2_params_file:=/home/orin/S15P11A304/ros2_ws/nav2_params.yaml \
  drive_enabled:=false \
  mqtt_enabled:=false \
  trajectory_csv:=/tmp/fk01_slam_route.csv
```

다른 터미널에서 확인한다.

```bash
source /home/orin/S15P11A304/ros2_ws/install/setup.bash
ros2 lifecycle nodes
ros2 topic hz /scan
ros2 topic hz /tof/left/points
ros2 topic hz /tof/right/points
ros2 topic hz /odometry/filtered
ros2 run tf2_ros tf2_echo map base_link
ros2 topic echo /local_costmap/voxel_grid --once
```

통과 기준:

- `/scan` 약 11 Hz, 좌·우 ToF 각각 약 15 Hz, `/odometry/filtered`가 약 30 Hz로 발행된다.
- `map -> odom -> base_link` TF가 끊기지 않는다.
- RViz에서 LiDAR 벽과 실제 벽 방향이 같고, 좌·우 ToF 점이 포크 전방에 뜬다.
- 두 ToF 중 하나라도 끊기면 회피 노드가 정지시키는 구성을 유지한다.
- `uart_teleop_bridge`는 이 단계에서 없어야 한다.

## 3. 창고 원점 정렬과 MQTT 기동

SLAM의 `(0,0,0)`은 기동 순간 차량 자세다. MQTT 원점인 창고 좌하단과 같지
않으므로, 기동 전에 `base_link` 위치와 방향을 실측한다. 예를 들어 차가 창고
좌하단 기준 `(0.618 m, 0.618 m)`, +x 방향에 놓였다면 다음 값이다.

```text
origin_x_m=0.618
origin_y_m=0.618
origin_yaw_rad=0.0
```

방향까지 맞춰야 한다. +y를 보고 시작하면 `origin_yaw_rad=1.5708`이다. 잘못된
원점으로 관제에 좌표를 보내는 것을 막기 위해 `origin_configured=true` 없이는
MQTT 활성화가 실패한다.

첫 기동을 종료하고 아래처럼 다시 시작한다.

```bash
export MQTT_PASSWORD='<F팀에서 받은 비밀번호>'

ros2 launch forklift_teleop field_slam_nav2.launch.py \
  nav2_params_file:=/home/orin/S15P11A304/ros2_ws/nav2_params.yaml \
  drive_enabled:=false \
  mqtt_enabled:=true \
  origin_configured:=true \
  origin_x_m:=0.618 origin_y_m:=0.618 origin_yaw_rad:=0.0 \
  mqtt_username:='<F팀에서 받은 아이디>' \
  mqtt_ca_cert:=/absolute/path/to/fast-mqtt-ca.crt \
  mqtt_tls_insecure:=true \
  trajectory_csv:=/tmp/fk01_slam_route.csv
```

`mqtt_tls_insecure=true`는 현재 인증서의 이름이 호스트명이 아니라 IP인 현장
제약 때문에 필요하다. CA 신뢰 검증은 계속 수행하지만 hostname 검증만 끈다.
인증서가 수정되면 `false`로 되돌린다.

## 4. 한 바퀴 주행

먼저 RViz의 map, scan, 두 PointCloud2와 local costmap을 보고 통로가 비어 있는지
확인한다. 그 뒤 launch를 다시 시작하면서 `drive_enabled:=true`로 바꾼다.

고정 좌표를 미리 넣지 않는다. 새로 만든 SLAM 지도에 실제 장애물이 나타난 뒤
RViz의 **Nav2 Goal**로 순환로의 안전한 다음 지점을 하나씩 선택한다. 권장 순서는
오른쪽 순환로 상승 → 위쪽 횡단 → 왼쪽 순환로 하강 → 아래쪽 횡단 → 출발점이다.
각 목표가 성공한 뒤 다음 목표를 보낸다. 지게차는 제자리 회전이 불가능하므로
목표 yaw는 통로 진행 방향과 맞춘다.

긴급 정지는 별도 하드웨어를 우선 사용한다. 소프트웨어를 중단해야 하면 launch
터미널에서 `Ctrl-C`를 누르면 UART watchdog이 500 ms 뒤 구동을 정지하고 조향을
중립으로 복귀시킨다.

순회 중 `/tmp/fk01_slam_route.csv`에는 5 cm 간격으로 다음 값이 기록된다.

- SLAM 원본 좌표
- 창고 좌하단 기준 실측 m 좌표
- MQTT에 보내는 ×10 좌표
- 누적 이동 거리

누적 2 m 이상 이동한 뒤 시작점 0.15 m 안으로 돌아오면 로그에
`One lap complete`가 출력된다. 실제 지도는 순회 종료 후 저장한다.

```bash
mkdir -p /home/orin/S15P11A304/ros2_ws/maps/field
ros2 run nav2_map_server map_saver_cli \
  -f /home/orin/S15P11A304/ros2_ws/maps/field/fk01_field
```

## 5. MQTT 10 Hz 확인

```bash
mosquitto_sub -h i15a304.p.ssafy.io -p 8883 \
  -u '<아이디>' -P "$MQTT_PASSWORD" \
  --cafile /absolute/path/to/fast-mqtt-ca.crt --insecure \
  -t 'fast/v1/vehicle/fk01/telemetry' -v
```

페이로드 규칙:

- `pose.x`, `pose.y`, `velocity.linear`, `forkHeight`: 실물 m 값 ×10
- `pose.yaw`: rad 그대로, 반시계 방향 +
- `velocity.angular`: rad/s 그대로
- QoS 0, retained false, 100 ms 주기
- `map -> base_link`가 없거나 오래됐거나 창고 범위를 벗어나면 그 주기는 발행 안 함
- MQTT가 끊겼을 때의 오래된 표본은 재연결 후 몰아서 보내지 않음

현재 포크·적재·배터리 상태를 갱신할 때는 물리 단위(m)를 사용해 JSON을 보낸다.

```bash
ros2 topic pub --once /vehicle/telemetry_state std_msgs/msg/String \
  "{data: '{\"forkHeight\":0.02,\"loaded\":false,\"cargoId\":null,\"state\":\"IDLE\",\"taskId\":null,\"battery\":87.5}'}"
```

위 입력은 다음 telemetry부터 `forkHeight: 0.2`로 변환된다. 상태 입력이 없으면
속도 기준으로 `IDLE` 또는 `MOVING`을 자동 선택한다.

## 6. 현장 중단 조건

아래 중 하나라도 발생하면 자동 주행을 시작하지 않거나 즉시 중단한다.

- `/scan`, 좌·우 ToF, `/odometry/filtered`, `map -> base_link` 중 하나가 끊김
- RViz 장애물 방향이 실물과 반대이거나 ToF가 바닥/차체를 장애물로 계속 표시함
- MQTT 좌표가 x `0~20`, y `0~30` 밖이거나 실제 이동 축과 반대로 움직임
- 시작점 복귀 오차가 0.15 m를 반복해서 넘음
- local costmap에서 통로가 inflation으로 완전히 막힘

현장 완료 후에는 CSV의 시작/종료 좌표, 누적 거리, SLAM loop closure 뒤의 좌표
점프와 저장된 map을 함께 검토해 최종 Nav2 파라미터를 확정한다.

## 7. SSH가 끊겨도 자동 한 바퀴 실행

`field_lap_session.sh`는 launch를 SSH 터미널과 다른 세션으로 분리한다. 접속이
끊겨도 SLAM, Nav2, 좌표 CSV와 순회 미션이 계속 실행된다. 먼저 한 번 빌드한다.

```bash
cd /home/orin/S15P11A304/ros2_ws
source /opt/ros/humble/setup.bash
export ROS_DOMAIN_ID=100
colcon build --packages-select fast_mqtt_bridge forklift_teleop
chmod +x tools/field_lap_session.sh
```

차량을 창고 좌하단 기준으로 실측한 위치와 방향에 놓는다. 아래 예시는
`(0.618 m, 0.618 m)`에서 창고 +x 방향을 보고 시작할 때만 사용한다.

먼저 기존 자동 세션이 없는지 확인한다.

```bash
export ROS_DOMAIN_ID=100
./tools/field_lap_session.sh status
```

`NOT RUNNING`이면 시작한다.

```bash
./tools/field_lap_session.sh start 0.618 0.618 0.0
```

이미
실행 중인 `field_slam_nav2.launch.py`, `field_lap_mission`, `uart_teleop_bridge`가
있으면 새 launch를 겹쳐 띄우지 말고 기존 세션부터 정상 종료한다. 센서·TF·Nav2를
중복 실행하면 `/cmd_vel` 발행자와 좌표계가 서로 충돌한다.

자동 미션은 `/map`, `/scan`, 양쪽 ToF와 Nav2 action이 모두 준비될 때까지
기다린다. 따라서 ESP32 초기화 20여 초 동안 움직이지 않는 것이 정상이다. 진행
상태와 로그는 새 SSH 접속에서도 확인할 수 있다.

```bash
./tools/field_lap_session.sh status
./tools/field_lap_session.sh log
./tools/field_lap_session.sh csv
```

기본 순환 목표는 실물 창고 좌표로 다음과 같다.

```text
(1.55,0.50,0) -> (1.55,2.50,+pi/2) ->
(0.50,2.50,+pi) -> (0.50,0.50,-pi/2) -> 실제 시작점
```

현장 장애물 때문에 이 점이 안전하지 않으면
`src/forklift_teleop/config/field_lap.yaml`의 세 waypoint 배열을 함께 수정한다.
중단은 다음 명령으로 해당 launch 프로세스 그룹에만 SIGINT를 보낸다.

```bash
./tools/field_lap_session.sh stop
```

## 8. 주행 중 반복 정지·벽 앞 좌회전 실패 진단 기록

> 기록 시각: **2026-08-06 19:07 (KST)**  
> 목적: 생성형 AI나 코드를 오래 추적한 개발자뿐 아니라, 처음 인수인계받은 사람도
> 현장에서 “왜 가다가 멈추는가”를 빠르게 판단할 수 있도록 남긴다.

### 8.1 결론

현재 코드는 **벽을 따라 운동장 트랙처럼 도는 벽 추종 주행기**가 아니다. 짧게 나눈
Nav2 목표를 차례로 보내고, 별도 장애물 회피 노드가 위험 시 명령을 줄이거나 완전히
정지시키는 구조다. 따라서 다음 현상은 센서 임계값 하나의 문제가 아니라 현재 구조에서
예상되는 결과다.

```text
현재:  짧은 목표 전송 → 목표마다 정지 → 약한 좌/우 보정 → 0.25m에서 완전 정지
원하는 동작: 직진 → 벽 사전 감지 → 좌회전 방향 고정 → 코너 통과 → 벽 추종 → 직진
```

**정지선을 0.25m보다 더 낮추는 것만으로는 해결되지 않는다.** 연속 코너링, 방향
고정(hysteresis/latching), 벽 추종과 주행 중 재계획 로직이 필요하다.

### 8.2 사람이 먼저 확인할 핵심 원인

| 현상 | 코드 원인 | 결과 |
|---|---|---|
| 약 0.25m 진행 후 반복 정지 | 경로를 최대 0.25m 목표로 분할하고 목표 사이에도 `start_delay_sec=5.0` 적용 | 기본 경로 26개 목표, 목표 사이 대기만 최소 125초 |
| 벽 가까이에서 완전 정지 | LiDAR 중앙 또는 좌·우 ToF 중 하나가 0.25m 이하면 `STOP` | 선속도와 각속도를 모두 0으로 만들어 정지 후 좌회전 불가 |
| 벽 앞에서 좌회전이 늦음 | 회피 조향이 Nav2 명령에 `±0.02 rad/s`를 더하는 수준 | 0.08m/s 기준 회전반경 약 4m라 2×3m 트랙 코너를 돌 수 없음 |
| 좌·우 회피가 자주 바뀜 | 매 프레임 ToF/LiDAR 순간 차이만 사용하고 방향 유지 상태가 없음 | `AVOID_LEFT ↔ AVOID_RIGHT ↔ SLOW` 진동 |
| 정면 벽에서 방향을 못 정함 | 두 ToF가 비슷하고 LiDAR 좌우 차이도 작으면 `SLOW`, yaw 보정 0 | 벽을 향해 계속 접근한 뒤 정지선에서 STOP |
| 중간 장애물에서 멈춤 | BT가 `ComputePathToPose` 1회 후 `FollowPath`만 수행 | 주행 중 장애물 우회 재계획이나 복구 없음 |
| 낮은 장애물을 우회하지 못함 | ToF는 local costmap에만 들어가고 global costmap은 LiDAR만 사용 | global path는 장애물을 통과하고 local controller만 정지 |
| 코너 경로 생성·추종 실패 | Nav2 최소 회전반경 0.54m, 오른쪽 차선과 벽 사이는 약 0.45m | 기본 사각 waypoint 코너가 기하학적으로 맞지 않음 |
| 정지 후 재출발 실패 | 낮은 속도/PWM에서 조향된 바퀴의 정지마찰을 이기지 못함 | 목표별 정지와 재출발이 반복될수록 stall 가능성 증가 |
| 이유 없이 순간 정지 | 센서와 명령 timeout이 0.5초 | 센서/TF/CPU 지연이나 목표 전환 중 명령 공백도 즉시 정지 |

기본 `(0.618, 0.618, 0)` 원점과 현재 waypoint를 `0.25m` 단위로 분할하면 목표가
26개 생성된다. `field_lap_mission.py`는 목표 성공 뒤에도 `_ready_since`를 다시
설정하므로 다음 목표 전송 전 5초를 또 기다린다. 이 정지는 무작위 오류가 아니라
현재 코드에 들어 있는 동작이다.

### 8.3 관련 코드 위치

- 목표 분할: `src/forklift_teleop/forklift_teleop/lap_route.py`
- 목표 사이 대기와 재시도: `src/forklift_teleop/forklift_teleop/field_lap_mission.py`
- STOP/SLOW/좌우 판정: `src/forklift_teleop/forklift_teleop/obstacle_fusion.py`
- 센서·명령 0.5초 watchdog: `src/forklift_teleop/forklift_teleop/obstacle_avoidance_node.py`
- 회복 없는 Nav2 BT: `src/forklift_teleop/behavior_trees/navigate_to_pose_no_spin.xml`
- 회전반경·속도·costmap: `nav2_params.yaml`
- Twist → PWM/후륜 조향: `src/forklift_teleop/forklift_teleop/mapping.py`

### 8.4 중간 장애물에서 멈추는 이유

현재 BT는 아래 순서만 실행한다.

```text
ComputePathToPose 한 번 → FollowPath 한 번
```

주행 도중 장애물이 새로 감지돼도 global path를 주기적으로 다시 만들지 않는다.
낮은 장애물은 ToF가 local costmap에 표시하지만 global costmap에는 들어가지 않으므로,
global path는 장애물을 통과하는 상태로 남고 local controller 또는 회피 노드만 차량을
멈춘다. 계획 재생성, 후진, 좌측 우회 같은 다음 행동은 현재 BT에 없다.

또한 ToF costmap 입력은 `clearing: false`이므로 이전에 표시한 낮은 장애물 점이
남아 유령 벽처럼 보일 가능성도 확인해야 한다.

### 8.5 목표 동작으로 변경할 때의 우선순위

1. `start_delay_sec`를 최초 센서 준비에만 적용하고 목표 사이 5초 정지를 제거한다.
2. 0.25m마다 별도 `NavigateToPose`를 보내는 대신 연속 경로 또는 연속 waypoint
   추종으로 바꾼다.
3. `STRAIGHT → CORNER_APPROACH → LEFT_TURN_LATCHED → WALL_FOLLOW → STRAIGHT`
   상태 머신을 추가한다.
4. 좌회전을 시작하면 센서 차이가 잠깐 바뀌어도 코너가 끝날 때까지 방향을 유지한다.
5. `0.02 rad/s` 고정 bias 대신 속도와 실측 회전반경에 맞는 곡률 명령을 사용한다.
6. 실제 최소 회전반경을 다시 측정하고 Nav2의 `minimum_turning_radius=0.54` 및
   사각 waypoint를 둥근 코너 경로와 맞춘다.
7. Spin 없이 가능한 주기적 재계획과 `후진 → 전진 좌회전` 복구 BT를 추가한다.
8. ToF costmap clearing/잔상, 센서 timeout, RF2O·EKF 진행량을 각각 검증한다.

### 8.6 현장 로그 판독 기준

아래 상태를 구분해야 원인을 빨리 찾을 수 있다.

| 상태/로그 | 의미 |
|---|---|
| `WAITING` 또는 `settling 5.0s` | 센서 준비 또는 목표 사이 강제 대기 |
| `SLOW: both front ToFs similarly occupied` | 정면 벽에서 좌우 방향을 정하지 못함 |
| `AVOID_LEFT/RIGHT` | 방향 후보만 정해졌으며 실제 보정은 기본 `0.02rad/s` |
| `STOP: front obstacle` | 0.25m 정지선 도달, 선속도·각속도 모두 0 |
| `required sensor stale` | LiDAR/ToF 데이터가 0.5초 이상 늦음 |
| `Failed to make progress` | 10초 동안 progress checker 기준 이동량 부족 |
| `AUTO LAP FAILED` | 같은 짧은 목표를 한 번 재시도한 뒤 미션 종료 |

현장 튜닝 전에는 `/field_lap/status`, `/obstacle_avoidance/status`, `/cmd_vel`,
`/cmd_vel_safe`, `/odometry/filtered`를 같은 시간축으로 기록한다. 어느 노드가 정지를
만들었는지 확인하지 않고 정지거리나 PWM만 바꾸면 다른 정지 원인이 그대로 남는다.
