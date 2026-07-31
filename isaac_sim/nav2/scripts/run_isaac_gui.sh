#!/bin/bash
# Isaac Sim GUI 를 올바른 ROS2 환경으로 켠다 (로컬, humble).
#
# 왜 필요한가:
#   시스템 ROS2(/opt/ros/humble)는 Python 3.10, Isaac 은 3.11 이다. 시스템 ROS2 를
#   source 한 터미널에서 Isaac 을 켜면 PYTHONPATH 에 3.10 경로가 남아 rclpy 로딩이
#   실패한다("No module named 'rclpy._rclpy_pybind11'"). ROS 환경변수를 지우고
#   Isaac 내장 humble 라이브러리를 쓰게 한다.
#
# 사용법:
#   ./nav2/scripts/run_isaac_gui.sh
#   그 다음 GUI 에서 ff.usd 열고 Play, Script Editor 에서:
#     exec(open('/home/ubuntu/forklift_ws/nav2/scripts/kinematic_vehicle.py').read())
#
# 주의: 이 터미널에서는 ros2 명령을 쓸 수 없다. 별도 터미널에서 쓸 것.

set -e
ISAAC="${ISAAC_SIM_ROOT:-$HOME/isaacsim}"
BRIDGE="$ISAAC/exts/isaacsim.ros2.bridge"

if [ ! -x "$ISAAC/isaac-sim.sh" ]; then
    echo "Isaac Sim 을 찾을 수 없습니다: $ISAAC"
    exit 1
fi
if [ ! -d "$BRIDGE/humble/lib" ]; then
    echo "내장 humble 라이브러리가 없습니다: $BRIDGE/humble/lib"
    exit 1
fi

exec env \
    -u PYTHONPATH \
    -u AMENT_PREFIX_PATH \
    -u CMAKE_PREFIX_PATH \
    -u COLCON_PREFIX_PATH \
    -u ROS_DISTRO \
    ROS_DISTRO=humble \
    RMW_IMPLEMENTATION=rmw_fastrtps_cpp \
    LD_LIBRARY_PATH="$BRIDGE/humble/lib:${LD_LIBRARY_PATH}" \
    "$ISAAC/isaac-sim.sh"
