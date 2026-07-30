# Forklift Tele-operation & Sensor Bridge

노드가 두 개입니다.

| 노드 | 링크 | 역할 |
|---|---|---|
| `uart_teleop_bridge` | UART1 `/dev/ttyTHS1` | `/cmd_vel` → `@CMD` 명령 (하행) |
| `imu_bridge` | USB CDC `/dev/ttyACM0` | `@IMU`/`@IMS` → `/imu/data` (상행) |

`uart_teleop_bridge`는 전륜 고정축·후륜 조향 차량의 전진/후진 조향 부호와
통신 안전 정지를 검증하기 위한 개방루프 텔레옵입니다.

`imu_bridge`는 별도 프로세스입니다. 장치 파일이 다르므로 분리해도 되고,
그래야 텔레메트리 폭주가 명령 지연을 유발할 수 없고 동작 중인 텔레옵
경로를 건드리지 않습니다. 프레이밍·CRC는 `protocol.py`를 공유합니다.

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

## UART (하행 명령)

- Jetson device: `/dev/ttyTHS1`
- 115200 baud, 8N1, no flow control
- ESP32-S3 TX GPIO17 -> Jetson Pin 10 RX
- ESP32-S3 RX GPIO18 <- Jetson Pin 8 TX
- Common ground is required

정확한 Ackermann 곡률과 속도 odometry는 이 패키지의 범위가 아닙니다.

## IMU 브리지 (상행 텔레메트리)

```bash
ros2 launch forklift_teleop sensor_usb.launch.py
```

`imu_bridge`와 `base_link → imu_link` static TF를 함께 띄웁니다.

- ESP32-S3 네이티브 USB를 Jetson에 연결하면 `/dev/ttyACM0`으로 잡힙니다
- 프레임 규격은 [펌웨어 README](../../../firmware/esp32/motor_controller/README.md) 참고
- ESP32 콘솔 로그가 같은 스트림으로 나오지만 `@`로 시작하지 않는 줄은 버립니다
- `idf.py monitor`가 포트를 잡고 있으면 이 노드가 열 수 없습니다

### 발행 내용

`/imu/data` (`sensor_msgs/Imu`)에 **자이로 Z만** 담습니다.

| 필드 | 값 |
|---|---|
| `angular_velocity.z` | 바이어스 보정된 요레이트 (rad/s) |
| `angular_velocity_covariance[8]` | `yaw_rate_stddev**2` (기본 4e-4) |
| `angular_velocity_covariance[0]`, `[4]` | 1e6 (미신뢰) |
| `orientation_covariance[0]` | -1.0 (ROS 관례: 이 필드 무효) |
| `linear_acceleration_covariance` 대각 | 1e6 (미신뢰) |

**covariance를 0으로 두면 안 됩니다.** EKF가 "오차 없는 완벽한 센서"로
착각하고 라이다 오도메트리를 완전히 무시합니다.

### 시각 보정

ESP32는 자체 시계를 쓰므로 `esp_timer` 값을 그대로 쓰면 ROS 시각과 어긋나고
TF가 조용히 망가집니다. `ClockOffsetTracker`가 `host - mcu` 차이의 **최소값**을
추적해 큐잉 지연을 걷어내고, 20ppm 상향 여유로 클럭 스큐를 따라갑니다.
MCU 리부트(시간 역행)나 1초 이상 점프는 리셋으로 처리하며, 스탬프는 절대
미래로 가지 않습니다.

### 부호 확인 (건너뛰지 말 것)

```bash
ros2 topic echo /imu/data --field angular_velocity.z
```

위에서 봤을 때 **반시계 회전이 양수**여야 합니다(REP-103). 반대로 나오면
`config/sensors.yaml`의 `gyro_z_sign`을 `-1`로 바꿉니다.

참고: 부호를 뒤집으려고 static TF의 **yaw를 180도 돌리는 것은 효과가
없습니다.** Z축 회전은 ωz 부호를 바꾸지 않습니다. 센서를 물리적으로 뒤집어
장착했다면 `roll`을 π로 주는 것이 맞습니다.

### 범위 밖

EKF(`robot_localization`) 구성은 이 패키지에 없습니다. 붙일 때는 rf2o의
`publish_tf`를 `false`로 내려 `odom → base_link` 발행 주체를 EKF 하나로
정리해야 합니다. 둘이 동시에 발행하면 TF가 떨며 튑니다.
