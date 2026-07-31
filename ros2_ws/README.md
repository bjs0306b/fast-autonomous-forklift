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

### 남은 실차 검증

- `NavigateToPose -> /cmd_vel -> UART bridge -> ESP32`를 동시에 연결한
  저속 자동 주행
- 서보 명령각과 실제 후륜 바퀴각 캘리브레이션
- 지면에서 직선·곡선 경로 추종 오차 측정
- 후륜 조향 후미 스윙과 obstacle footprint 검증

