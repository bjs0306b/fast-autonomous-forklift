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

