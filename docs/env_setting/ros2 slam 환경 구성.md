# ROS 2 자율주행 맵핑 트러블슈팅 일지

## 개발 환경
* **하드웨어:** Jetson Orin, YDLidar (X4-Pro)
* **운영체제 및 프레임워크:** Ubuntu, ROS 2
* **주요 패키지:** `ydlidar_ros2_driver`, `rf2o_laser_odometry`, `slam_toolbox`, `nav2_map_server`

---

## 🛠️ Issue 1. rf2o_laser_odometry 데이터 수신 불가 (Waiting for laser_scans)

**현상**
라이다 데이터가 정상적으로 들어오고 있음에도 불구하고, 노드 실행 시 `Waiting for laser_scans....` 메시지만 출력되며 오도메트리 계산이 진행되지 않음.

**원인 파악**
패키지의 런치 파일(`rf2o_laser_odometry.launch.py`) 내에 발행할 오도메트리 토픽 이름이 잘못 지정되어 있어, 시스템 내에서 데이터 흐름이 엇갈림.

**해결 방법**
1. 런치 파일(`rf2o_laser_odometry.launch.py`)을 열어 `odom_topic` 파라미터를 정상적인 이름으로 수정
   * 변경 전: `'odom_topic' : '/odom'_rf20`
   * 변경 후: `'odom_topic' : '/odom'`
2. 패키지 재빌드 후 런치 파일로 다시 실행하여 해결
   `colcon build --packages-select rf2o_laser_odometry`
   `source install/setup.bash`
   `ros2 launch rf2o_laser_odometry rf2o_laser_odometry.launch.py`

---

## 🛠️ Issue 2. YDLidar 통신 에러 및 패킷 드랍

**현상**
라이다 구동 시 `Checksum error`가 무더기로 발생하며, `Real points 432 > fixed points 430` 경고와 함께 스캔 데이터가 정상적으로 파싱되지 않음.

**원인 파악**
* 리눅스의 USB 직렬 통신(16ms 대기) 방식으로 인한 고속 센서 데이터 병목 발생.
* 라이다 드라이버 파라미터에 해상도가 고정되어 있어, 물리 모터 회전에 따른 미세한 데이터 개수 변동을 처리하지 못하고 패킷을 버림.

**해결 방법**
1. USB 시리얼 포트 Low Latency(저지연) 모드 활성화
   `sudo apt-get install setserial`
   `sudo setserial /dev/ttyUSB0 low_latency`
2. 드라이버 설정 파일(`ydlidar.yaml`)에서 고정 해상도 옵션 해제
   `fixed_resolution: false`
3. 패키지 재빌드 및 적용
   `colcon build --packages-select ydlidar_ros2_driver --symlink-install`
   `source install/setup.bash`

---

## 🛠️ Issue 3. SLAM Toolbox 오도메트리 매칭 실패 (Fail to compute odom pose)

**현상**
라이다와 오도메트리가 모두 정상 구동 중이나, SLAM 툴박스 실행 시 `Fail to compute odom pose` 에러가 발생하며 맵이 그려지지 않음.

**원인 파악**
SLAM 툴박스의 기본 설정 파일이 로봇 중심 좌표계를 `base_footprint`로 기대하고 있으나, 시스템은 `base_link`를 사용 중이어야 프레임 이름이 엇갈림.

**해결 방법**
1. SLAM 파라미터 파일 복사 및 `base_frame` 수정
   `base_frame: base_link` 로 변경
2. 수정한 파라미터 파일을 지정하고 시뮬레이션 시간 동기화 옵션을 꺼서 실행
   `ros2 launch slam_toolbox online_async_launch.py slam_params_file:=/home/orin/ros2_ws/mapper_params_online_async.yaml use_sim_time:=false`

---

## 🏁 최종 결과
모든 노드가 정상 연동되었으며, 라이다 형상 매칭만으로 성공적인 실시간 맵핑(SLAM) 수행.
완성된 맵은 아래 명령어를 통해 정상적으로 저장 완료.
`ros2 run nav2_map_server map_saver_cli -f ~/ros2_ws/my_map`