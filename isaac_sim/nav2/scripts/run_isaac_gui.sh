#!/bin/bash

# DDS 공유메모리 전송을 끈다(UDP 전용). /dev/shm 잔재로 발행이 막히면
# Isaac 이 /clock 을 메인 스레드에서 발행하다 통째로 멈춘다.
export FASTRTPS_DEFAULT_PROFILES_FILE=/home/ubuntu/forklift_ws/nav2/config/fastdds_udp_only.xml
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

# 옵션
#   --safe   NVIDIA GPU 로 고정 + 멀티GPU 끔 + 렌더 부하 낮춤
#            (omni.ui.scene 드로우 중 세그폴트가 날 때 쓴다. 이 노트북은
#             NVIDIA RTX 4050 + Intel Arc 하이브리드라 Isaac 이 어느 쪽으로
#             그릴지 헷갈리면 libnvidia-glcore 안에서 죽는다)
#   --clean  셰이더/쉐이프 캐시를 지우고 시작 (캐시 손상 의심 시)
#   --prime  Intel 로 그려질 때만. NVIDIA PRIME 오프로드를 강제한다.
#            (이 장비는 보통 필요 없다 — 강제하면 GPU 를 못 찾을 수 있다)
SAFE=0
CLEAN=0
PRIME=0
for a in "$@"; do
  case "$a" in
    --safe)  SAFE=1 ;;
    --clean) CLEAN=1 ;;
    --prime) PRIME=1 ;;
    *) echo "알 수 없는 옵션: $a"; exit 1 ;;
  esac
done

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

if [ "$CLEAN" -eq 1 ]; then
    echo ">>> 캐시 정리"
    # clear_caches.sh 는 매번 Y/N 을 묻고 마지막에 키 입력을 기다린다.
    [ -x "$ISAAC/clear_caches.sh" ] && \
        yes | "$ISAAC/clear_caches.sh" >/dev/null 2>&1 || true
    rm -rf "$HOME/.cache/ov/Kit/shadercache" \
           "$HOME/.nv/GLCache" 2>/dev/null || true
fi

KIT_ARGS=()
GPU_ENV=()
if [ "$SAFE" -eq 1 ]; then
    echo ">>> 안전 모드: 멀티GPU 끔, 0번 GPU 고정, 비동기 렌더 끔"
    # 환경변수(VK_ICD_FILENAMES / PRIME 오프로드)는 건드리지 않는다.
    # 이 장비는 NVIDIA 가 이미 활성 렌더러(Active: Yes: 0)라, ICD 를 강제하면
    # 오히려 GPU 를 못 찾는다. Kit 인자만으로 충분하다.
    KIT_ARGS=(
        --/renderer/multiGpu/enabled=false
        --/renderer/activeGpu=0
        --/app/asyncRendering=false
        --/app/renderer/skipWhileMinimized=true
    )
fi

# --prime : 그래도 Intel 로 그려질 때만 쓴다 (하이브리드 강제 전환)
if [ "$PRIME" -eq 1 ]; then
    echo ">>> PRIME 오프로드: NVIDIA 로 강제"
    GPU_ENV=(
        __NV_PRIME_RENDER_OFFLOAD=1
        __GLX_VENDOR_LIBRARY_NAME=nvidia
        __VK_LAYER_NV_optimus=NVIDIA_only
    )
fi

# env 는 옵션(-u)을 변수 대입보다 먼저 받아야 한다.
# 대입이 먼저 오면 그 뒤 -u 를 명령 이름으로 보고 실패한다.
exec env \
    -u PYTHONPATH \
    -u AMENT_PREFIX_PATH \
    -u CMAKE_PREFIX_PATH \
    -u COLCON_PREFIX_PATH \
    -u ROS_DISTRO \
    ROS_DISTRO=humble \
    RMW_IMPLEMENTATION=rmw_fastrtps_cpp \
    LD_LIBRARY_PATH="$BRIDGE/humble/lib:${LD_LIBRARY_PATH}" \
    "${GPU_ENV[@]}" \
    "$ISAAC/isaac-sim.sh" "${KIT_ARGS[@]}"
