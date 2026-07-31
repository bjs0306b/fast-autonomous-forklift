# Forklift Tele-operation & Sensor Bridge

노드가 두 개입니다.

| 노드 | 링크 | 역할 |
|---|---|---|
| `uart_teleop_bridge` | UART1 `/dev/ttyTHS1` | `/cmd_vel` → `@CMD` 명령 (하행) |
| `sensor_bridge` | USB CDC `/dev/ttyACM0` | `@IMU`/`@IMS` → `/imu/data`, `@TOF` → `/tof/{left,right}/points` (상행) |

`uart_teleop_bridge`는 전륜 고정축·후륜 조향 차량의 전진/후진 조향 부호와
통신 안전 정지를 검증하기 위한 개방루프 텔레옵입니다.

`sensor_bridge`는 별도 프로세스입니다. 장치 파일이 다르므로 분리해도 되고,
그래야 텔레메트리 폭주가 명령 지연을 유발할 수 없고 동작 중인 텔레옵
경로를 건드리지 않습니다. 프레이밍·CRC는 `protocol.py`를 공유합니다.

**IMU와 ToF가 노드 하나인 이유**: `/dev/ttyACM0`은 한 프로세스만 열 수
있는데 `@IMU`·`@IMS`·`@TOF`가 모두 그 스트림 하나로 섞여 옵니다. 센서별로
노드를 나눌 수 없습니다.

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

## 전면 ToF (VL53L8CX ×2)

`sensor_usb.launch.py`가 같이 띄웁니다. `/tof/left/points`·`/tof/right/points`에
`sensor_msgs/PointCloud2`로 센서당 15Hz 발행합니다.

- 존 8×8을 3D 점으로 투영합니다. **LaserScan이 아닌 이유**는 존의 고도 정보가
  남아야 바닥 반사를 거르고 라이다와 같은 costmap에서 서로를 지우지 않기
  때문입니다 (아래 참고)
- `target_status == 5`만 통과시킵니다. 9(신뢰도 절반)는
  `tof_accept_low_confidence`로 켤 수 있습니다
- 유효하지 않은 존은 NaN으로 채우지 않고 **버립니다** — costmap이 유령 반환을
  보지 않게 합니다

### 확인해야 할 것 두 가지

**`tof_fov_deg`** — 데이터시트 실제 값과 대조합니다. 틀리면 모든 방위가
통째로 어긋납니다.

**`tof_azimuth_sign` / `tof_elevation_sign`** — 존 격자가 어느 방향으로
도는지는 센서 장착 방향에 달렸습니다. 열·행 순서가 뒤집힌 것은 **회전이 아니라
거울상이라 static TF로 고칠 수 없습니다.** 알려진 방위에 상자를 놓고 RViz에서
그 방위에 찍히는지 확인한 뒤 확정합니다. 자이로 `gyro_z_sign`과 같은 성질입니다.

### 라이다와의 관계

두 센서는 판단을 나누지도, 측정을 융합하지도 않습니다. **같은 costmap에 각자
`observation_source`로 등록**되고 플래너는 병합된 격자 하나만 봅니다.

조정이 필요한 곳은 clearing 한 곳입니다. 낮은 상자 위로 라이다 빔이 지나가면
라이다는 그 칸을 비었다고 raytrace clearing 하는데, **평면 레이어라면 ToF가
marking 해둔 상자가 지워집니다.** local costmap이 `VoxelLayer`라 marking·clearing이
복셀 단위로 일어나 이걸 피합니다 — 라이다(z≈0.2)와 ToF(z≈0.05~0.15)가 서로
침범하지 않습니다.

`nav2_params.yaml` 연결은 이 패키지 범위 밖입니다. 붙일 때 주의점:
- ToF는 `clearing: False`로 시작합니다. 45° 원뿔이 공격적으로 clearing 하면
  시야 가장자리 밖의 유효한 라이다 장애물을 지웁니다
- global costmap(평면 `ObstacleLayer`)에는 **추가하지 않습니다.** 높이가 접혀
  위 문제가 되살아나고, ToF는 매핑 센서가 아니라 단거리 반응 센서입니다
- 라이다 높이를 바꾸면 `lidar_odometry.launch.py`의 `LIDAR_TRANSLATION`을
  실측으로 갱신하고, VoxelLayer 천장 0.8m와 ToF 대비 최소 0.10~0.15m 간격을
  지키는지 확인합니다

### 범위 밖

EKF(`robot_localization`) 구성은 이 패키지에 없습니다. 붙일 때는 rf2o의
`publish_tf`를 `false`로 내려 `odom → base_link` 발행 주체를 EKF 하나로
정리해야 합니다. 둘이 동시에 발행하면 TF가 떨며 튑니다.
