#!/bin/bash
# Isaac Sim GUI 를 올바른 ROS2 환경으로 실행한다. (직접 손으로 그래프를 만들 때 사용)
#
# 그냥 ~/isaacsim/isaac-sim.sh 를 실행하면 ROS2 브리지가 rclpy 로딩에 실패한다.
# 시스템 ROS2 는 Python 3.10, Isaac Sim 은 Python 3.11 이라 서로 안 맞기 때문이다.
# 이 스크립트는 ROS 관련 환경변수를 지우고 Isaac 내장 humble 라이브러리를 쓰게 한다.
#
# 사용법:
#   ./scripts/run_gui.sh
#
# 주의: 이 터미널에서는 ros2 명령을 쓸 수 없다.
#       ros2 명령은 별도 터미널에서 'source /opt/ros/humble/setup.bash' 후 사용할 것.

set -e

ISAAC_ROOT="${ISAAC_SIM_ROOT:-$HOME/isaacsim}"

if [ ! -x "$ISAAC_ROOT/isaac-sim.sh" ]; then
    echo "Isaac Sim 을 찾을 수 없습니다: $ISAAC_ROOT"
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
    "$ISAAC_ROOT/isaac-sim.sh"
