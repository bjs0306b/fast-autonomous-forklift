#!/bin/bash
# Isaac Sim 을 올바른 ROS2 환경으로 실행한다.
#
# 왜 이 스크립트가 필요한가:
#   시스템 ROS2(/opt/ros/humble)는 Python 3.10 용이고 Isaac Sim 은 Python 3.11 을 쓴다.
#   터미널에서 setup.bash 를 source 한 상태로 Isaac 을 켜면 PYTHONPATH 에 3.10 경로가
#   남아 rclpy 로딩이 실패한다("No module named 'rclpy._rclpy_pybind11'").
#   따라서 ROS 관련 환경변수를 지우고, Isaac 에 내장된 humble 라이브러리를 쓰게 한다.
#
# 사용법:
#   ./scripts/run_sim.sh              창을 띄우고 실행
#   HEADLESS=1 ./scripts/run_sim.sh   창 없이 실행
#   SELFTEST=1 ./scripts/run_sim.sh   그래프 생성만 확인하고 종료
#
# 주의: 이 스크립트를 실행하는 터미널에서는 ROS2 명령(ros2 topic 등)을 쓸 수 없다.
#       ros2 명령은 별도 터미널에서 'source /opt/ros/humble/setup.bash' 후 사용할 것.

set -e

ISAAC_ROOT="${ISAAC_SIM_ROOT:-$HOME/isaacsim}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -x "$ISAAC_ROOT/python.sh" ]; then
    echo "Isaac Sim 을 찾을 수 없습니다: $ISAAC_ROOT"
    echo "ISAAC_SIM_ROOT 환경변수로 경로를 지정하세요."
    exit 1
fi

exec env \
    -u PYTHONPATH \
    -u AMENT_PREFIX_PATH \
    -u CMAKE_PREFIX_PATH \
    -u COLCON_PREFIX_PATH \
    ROS_DISTRO=humble \
    RMW_IMPLEMENTATION=rmw_fastrtps_cpp \
    LD_LIBRARY_PATH="$ISAAC_ROOT/exts/isaacsim.ros2.bridge/humble/lib" \
    HEADLESS="${HEADLESS:-0}" \
    SELFTEST="${SELFTEST:-0}" \
    "$ISAAC_ROOT/python.sh" "$SCRIPT_DIR/run_forklift_sim.py"
