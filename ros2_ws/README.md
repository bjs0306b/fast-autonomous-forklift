# FAST ROS2 workspace

현재 패키지는 다음 두 개입니다.

| 패키지 | 역할 |
|---|---|
| `forklift_teleop` | `/cmd_vel`(`geometry_msgs/Twist`)을 ESP32 UART 명령으로 변환하고, ESP32 USB 텔레메트리를 `/imu/data`로 발행 |
| `fast_mqtt_bridge` | ROS2 상태·위치·경로와 백엔드 MQTT 명령/결과 연결 |

빌드:

```bash
cd ros2_ws
rosdep install --from-paths src --ignore-src -r -y
colcon build
source install/setup.bash
```

실행:

```bash
ros2 launch fast_mqtt_bridge mqtt_bridge.launch.py
ros2 launch forklift_teleop teleop_uart.launch.py
ros2 launch forklift_teleop sensor_usb.launch.py
```

`fast_mqtt_bridge`의 상세 설정, MQTT 토픽, 미연결 ROS2 adapter 범위는
[`src/fast_mqtt_bridge/README.md`](src/fast_mqtt_bridge/README.md)를 참고합니다.

LiDAR·좌우 전면 ToF·SLAM·Nav2·장애물 회피·10 Hz Orin 좌표 전송을 한 번에
기동하는 현장 절차는 [`FIELD_SLAM_MQTT.md`](FIELD_SLAM_MQTT.md)를 참고합니다.

## LiDAR AMCL 및 Nav2 통합 현황

2026-07-30 기준 `S15P11A304-98`과 `S15P11A304-99`에서 확인한
실차 위치 추정 및 주행 제어 통합 현황입니다.

### 지도

- 실차 시험 지도 설정: `maps/sim_warehouse_real.yaml`
- 지도 이미지: `maps/sim_warehouse.pgm`
- 해상도: `0.005 m/pixel`
- 지도 원점: `[0.0, 0.0, 0.0]`
- 지도 크기: `400 x 600 pixel` (`2.0 x 3.0 m`)

기존 `my_map.yaml`과 `my_map.pgm`은 이전 AMCL 연동 시험 기록을 위해
유지합니다. 새 실차 시험에서는 `maps/sim_warehouse_real.yaml`을
명시적으로 지정합니다.

지도 원점은 로봇의 출발 위치가 아닙니다. 수신한 지도 픽셀을 분석한 결과
다음 직선 시험 좌표는 장애물로부터 충분히 떨어진 자유 공간입니다.

- 출발 후보: `(x=0.618, y=0.618, yaw=0.0)`
- 가까운 목표 후보: `(x=0.818, y=0.618, yaw=0.0)`
- 이동 거리: `0.20 m`
- 출발점의 장애물 여유 거리: 약 `0.595 m`
- 목표점의 장애물 여유 거리: 약 `0.395 m`

실차 시험에서는 지게차를 지도의 출발 후보에 대응하는 실제 위치와
방향에 놓은 후 `/initialpose`를 발행해야 합니다. 좌표만 입력하고 실차를
다른 장소에 두면 AMCL 위치와 LiDAR 관측이 일치하지 않습니다.

### 확인 완료

- YDLiDAR `/scan` 발행 및 약 11 Hz 수신
- RF2O laser odometry의 `/odom` 발행
- `base_link -> laser_frame`, `odom -> base_link` TF 연결
- map server와 AMCL lifecycle `active`
- `/initialpose` 입력 후 `/amcl_pose` 및 `map -> odom` 생성
- Nav2 controller, planner, BT navigator, velocity smoother lifecycle `active`
- 실측 차체 footprint 적용
- 명시적인 안전 시작점과 목표점을 사용한 `ComputePathToPose` 성공

### 실측 좌표계

`base_link`는 전륜 구동축 중심입니다.

- `base_link`에서 포크 앞쪽 끝: `+0.190 m`
- `base_link`에서 차량 뒤쪽 끝: `-0.245 m`
- 차체 폭: 약 `0.148 m`
- `base_link`에서 LiDAR 중심: `x=-0.025 m`, `y=0.000 m`,
  `z=0.202 m`

차체 footprint:

```text
[[0.190, 0.074], [0.190, -0.074],
 [-0.245, -0.074], [-0.245, 0.074]]
```

### 다음 실차 시험

1. 지게차를 `maps/sim_warehouse_real.yaml`의 출발 후보에 대응하는
   물리적 위치와 방향에 놓습니다.
2. `(0.618, 0.618, 0.0)`을 `/initialpose`로 발행합니다.
3. `/amcl_pose`와 `map -> base_link` TF가 생성되는지 확인합니다.
4. `(0.818, 0.618, 0.0)`을 가까운 시험 목표로 사용합니다.
5. UART 브리지가 실행되지 않았는지 확인한 뒤 `NavigateToPose`를
   실행합니다.
6. 생성된 경로와 `/cmd_vel`만 관찰해 Nav2 비구동 시험을 완료합니다.

UART 비구동 시험 전 확인:

```bash
ros2 node list | grep uart_teleop_bridge
ros2 topic info /cmd_vel -v
```

`/uart_teleop_bridge`가 없어야 하며 `/cmd_vel`에 ESP32 UART 브리지
구독자가 없어야 합니다. 이 상태에서 별도 터미널로 다음 명령을 실행해
Nav2 출력만 확인합니다.

```bash
ros2 topic echo /plan
ros2 topic echo /cmd_vel
```

### 2026-07-30 검증 결과

- `(0.618, 0.618, 0.0)`을 `/initialpose`로 입력해 AMCL pose와
  `map -> odom -> base_link` TF 생성을 확인했습니다.
- 안전한 시작점과 목표점을 지정한 `ComputePathToPose`가 성공했습니다.
- Nav2 controller, planner, BT navigator, velocity smoother가 모두
  `active` 상태로 전환되는 것을 확인했습니다.
- 축거 `0.144 m`를 사용하는 후륜 조향 Ackermann 곡률 변환을 적용하고
  Python 단위 테스트 29개가 통과했습니다.
- 바퀴를 띄운 상태에서 수동 `/cmd_vel`을 UART 브리지로 전달해 전진,
  좌·우 조향, 정지 및 서보 중앙 복귀를 확인했습니다.
- DC 모터와 서보를 동시에 구동했으며 UART CRC/ACK 왕복 통신이
  정상적으로 유지됐습니다.
- `/cmd_vel` 발행 중단 후 500 ms watchdog에 의해 DC 모터가 자동으로
  정지하고 서보가 중앙으로 복귀하는 것을 확인했습니다.

### 2026-08-05 갱신 — 조향 실각도가 명령의 1/4이었다

`ros2_ws` 밖(펌웨어)의 문제였지만 **여기의 곡률 변환 전제가 깨져 있었다**.
`servo.c`가 각도를 180으로 정규화해 `SERVO_MIN/MAX_PULSE_US` 사이에 매핑하는데
그 범위가 `1000~2000 µs`였다. MG996R은 **500~2500 µs가 180°**다.

- 최대 조향을 명령했을 때 뒷바퀴 실측 **8°** (코드가 믿던 값 28°)
- 실효 회전반경 **1200~1450 mm** (설계값 537 mm의 2~3배)
- 명령 대비 실제 회전 **21~26%** = `tan(8°)/tan(28°)`

수정 후 값(`config/teleop.yaml`이 단일 출처):

| 항목 | 값 |
|---|---|
| 서보 펄스 범위 | `500~2500 µs` |
| `rear_steering_limit_deg` | `36.0` |
| `steering_center_cdeg` | `9000` (직진 편차 0.33°) |
| `steering_min/max_cdeg` | `5400 / 12600` (±36° 대칭) |
| `max_angular_rps` | `0.75` |
| `max_drive_percent` / `_reverse` | `60` / `100` |

⚠️ **조향 한계가 네 곳에 서로 다른 값으로 박혀 있었다.** `protocol.py`의 하드코딩이
범위 밖 명령에 `ValueError`를 던져 **브리지 프로세스를 죽이고 있었다**(증상은
"조향이 아예 안 움직임" — `/cmd_vel` 구독자가 사라진다). 지금은 **봉투**(`protocol.py`
·펌웨어)와 **운전값**(`teleop.yaml`)이 분리돼 있어 범위를 바꿔도 재플래시가 필요 없다.

⚠️ **경로 추종 게인을 이 이전에 잡았다면 다시 봐야 한다.** 같은 `angular_z`가 이제
4.5배로 돈다.

### 남은 실차 검증

- `NavigateToPose -> /cmd_vel -> UART bridge -> ESP32`를 동시에 연결한
  저속 자동 주행
- 🔴 **`/cmd_vel` 인계 규칙**(S15P11A304-**199**) — Nav2와 포크 정렬 루프
  (`ai/scripts/onboard_fork_align_node.py`)가 **같은 토픽을 동시에 잡을 수 있다.**
  누가 언제 놓고 받는지 정해져 있지 않다. 지금은 포크 정렬 전에 **사람이 Nav2를
  내린다**(`docs/ai/onboard-fork-align-runbook.md` §1).
- ~~서보 명령각과 실제 후륜 바퀴각 캘리브레이션~~ → **완료**(위 2026-08-05 절)
- 지면에서 직선·곡선 경로 추종 오차 측정
- 후륜 조향 후미 스윙과 obstacle footprint 검증

