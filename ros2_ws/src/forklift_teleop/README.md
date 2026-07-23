# Forklift UART Tele-operation

ROS2 Humble의 `/cmd_vel`을 ESP32-S3용 UART 명령으로 변환합니다. 이번
패키지는 전륜 고정축·후륜 조향 차량의 전진/후진 조향 부호와 통신 안전
정지를 검증하기 위한 개방루프 텔레옵입니다.

## Build

```bash
cd ~/S15P11A304/ros2_ws
source /opt/ros/humble/setup.bash
rosdep install --from-paths src --ignore-src -r -y
colcon build --packages-select forklift_teleop
source install/setup.bash
```

## Run

터미널 1:

```bash
ros2 launch forklift_teleop teleop_uart.launch.py
```

터미널 2:

```bash
source /opt/ros/humble/setup.bash
ros2 run teleop_twist_keyboard teleop_twist_keyboard \
  --ros-args -p speed:=0.20 -p turn:=0.35
```

`teleop_twist_keyboard`의 제자리 회전 키는 차량 안전을 위해 정지·중앙
조향으로 처리됩니다. `/cmd_vel` 또는 UART가 500ms 이상 끊기면 ESP32가
모터를 정지하고 서보를 100도로 복귀시킵니다.

## UART

- Jetson device: `/dev/ttyTHS1`
- 115200 baud, 8N1, no flow control
- ESP32-S3 TX GPIO17 -> Jetson Pin 10 RX
- ESP32-S3 RX GPIO18 <- Jetson Pin 8 TX
- Common ground is required

정확한 Ackermann 곡률과 속도 odometry는 이 패키지의 범위가 아닙니다.
