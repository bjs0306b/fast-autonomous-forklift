#!/bin/bash
# build_scene.py 를 올바른 ROS2 환경으로 실행한다.
#
# 왜 필요한가:
#   시스템 ROS2 를 source 한 상태로 Isaac 을 켜면 PYTHONPATH 충돌로 rclpy 로딩이
#   실패한다(시스템은 Python 3.10, Isaac 은 3.11). ROS 관련 환경변수를 지우고
#   Isaac 에 내장된 배포판 라이브러리를 쓰게 한다.
#
# 로컬(~/isaacsim, humble)과 서버(conda, jazzy) 양쪽에서 동작한다.
#
# 사용법:
#   ./scripts/run_scene.sh                    헤드리스(기본)
#   HEADLESS=0 ./scripts/run_scene.sh         창 띄우기
#   VEHICLE_NS=SIM-F02 ./scripts/run_scene.sh 다른 차량으로
#
# 주의: 이 터미널에서는 ros2 명령을 쓸 수 없다. 별도 터미널에서 쓸 것.

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Isaac Sim 위치 찾기 (직접 설치 또는 conda pip 설치) ---
if [ -n "$ISAAC_SIM_ROOT" ]; then
    ISAAC="$ISAAC_SIM_ROOT"
elif [ -x "$HOME/isaacsim/python.sh" ]; then
    ISAAC="$HOME/isaacsim"
    PYRUN="$ISAAC/python.sh"
elif [ -n "$CONDA_PREFIX" ] && [ -d "$CONDA_PREFIX/lib/python3.11/site-packages/isaacsim" ]; then
    ISAAC="$CONDA_PREFIX/lib/python3.11/site-packages/isaacsim"
    PYRUN="python"
else
    echo "Isaac Sim 을 찾을 수 없습니다."
    echo "  conda 설치면 'conda activate isaacsim' 후 다시 실행하세요."
    echo "  또는 ISAAC_SIM_ROOT 환경변수로 경로를 지정하세요."
    exit 1
fi
[ -z "$PYRUN" ] && PYRUN="$ISAAC/python.sh"

# --- 내장 ROS2 배포판 선택 ---
# Isaac 은 humble/jazzy 라이브러리를 둘 다 들고 있을 수 있다.
# ROS2 는 배포판이 다르면 DDS 로 서로를 못 보므로, 이 머신에 설치된
# 시스템 ROS2 와 반드시 같은 배포판을 골라야 한다.
# (안 맞추면 Isaac 은 정상 동작하는데 ros2 topic list 에 아무것도 안 뜬다)
BRIDGE="$ISAAC/exts/isaacsim.ros2.bridge"
DISTRO=""

# 0) 명시적 지정이 최우선.
#    RoboStack conda 로 ROS2 를 깐 경우 /opt/ros 에 없으므로 자동 감지가 실패한다.
#    실물 Orin 이 humble 이면 서버도 humble 로 맞춰야 Nav2 설정을 한 벌로 쓴다.
#      예: FAST_ROS_DISTRO=humble ./run_scene.sh
if [ -n "$FAST_ROS_DISTRO" ]; then
    if [ -d "$BRIDGE/$FAST_ROS_DISTRO/lib" ]; then
        DISTRO="$FAST_ROS_DISTRO"
    else
        echo "요청한 배포판이 Isaac 에 없습니다: $FAST_ROS_DISTRO"
        echo "사용 가능: $(ls -d $BRIDGE/*/lib 2>/dev/null | xargs -n1 dirname | xargs -n1 basename | tr '\n' ' ')"
        exit 1
    fi
fi

# 1) 시스템에 설치된 배포판과 일치시킨다
[ -z "$DISTRO" ] && for d in /opt/ros/*/; do
    [ -d "$d" ] || continue
    sysd=$(basename "$d")
    if [ -d "$BRIDGE/$sysd/lib" ]; then
        DISTRO="$sysd"
        break
    fi
done

# 2) 시스템 ROS2 가 없으면(서버처럼) 내장된 것 중 하나
if [ -z "$DISTRO" ]; then
    for d in jazzy humble; do
        [ -d "$BRIDGE/$d/lib" ] && DISTRO="$d" && break
    done
fi

if [ -z "$DISTRO" ]; then
    echo "ROS2 브리지 라이브러리를 찾을 수 없습니다: $BRIDGE"
    exit 1
fi

echo "Isaac : $ISAAC"
echo "ROS2  : $DISTRO (내장)"

# libGLU 를 사용자 홈에 풀어둔 경우 경로에 포함 (서버에 root 가 없을 때)
EXTRA_LIB=""
[ -d "$HOME/local/usr/lib/x86_64-linux-gnu" ] && EXTRA_LIB="$HOME/local/usr/lib/x86_64-linux-gnu:"

exec env \
    -u PYTHONPATH \
    -u AMENT_PREFIX_PATH \
    -u CMAKE_PREFIX_PATH \
    -u COLCON_PREFIX_PATH \
    ROS_DISTRO="$DISTRO" \
    RMW_IMPLEMENTATION=rmw_fastrtps_cpp \
    LD_LIBRARY_PATH="${EXTRA_LIB}${BRIDGE}/${DISTRO}/lib:${LD_LIBRARY_PATH}" \
    HEADLESS="${HEADLESS:-1}" \
    VEHICLE_NS="${VEHICLE_NS:-SIM_F01}" \
    SCALE="${SCALE:-10}" \
    "$PYRUN" "$SCRIPT_DIR/build_scene.py"
