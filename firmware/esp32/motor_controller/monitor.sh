#!/usr/bin/env bash
#
# Watch the ESP32 console without reflashing.
#
# `flash.sh monitor` 는 **플래시부터 하고** 모니터로 넘어간다. 로그만 보고 싶을
# 때 쓰라고 이 파일이 따로 있다.
#
# ⚠️ **모니터를 열면 ESP32 가 리셋된다** (idf.py monitor 가 RTS 로 리셋한다).
#    리셋되면 부팅 호밍이 돌아 **10초 뒤 포크가 내려간다.** 포크 밑에 손·파렛트가
#    없어야 한다. 리셋 없이 보려면 아래 miniterm 쪽을 쓴다:
#
#      python3 -m serial.tools.miniterm /dev/ttyACM0 115200      # 종료 Ctrl+]
#
#    다만 miniterm 은 리셋을 안 하므로 **부팅 로그를 놓친다.** 하한 리밋
#    스위치를 보려면 부팅 로그가 필요하다 -- 아래 두 줄이 거기서 나온다:
#
#      "Lower limit initial state: ACTIVE/released (raw=N)"
#      "Lower limit changed: ..."      (스위치가 바뀔 때마다)
#
# ⚠️ ROS 스택이 떠 있으면 sensor_bridge 가 /dev/ttyACM0 를 쥐고 있다.
#    tools/ros_cleanup.sh 를 먼저 돌린다.
#
# Usage:  ./monitor.sh
set -euo pipefail

IDF_ACTIVATE=/home/orin/.espressif/tools/activate_idf_v5.5.5.sh
IDF_PYTHON=/home/orin/.espressif/python_env/idf5.5_py3.10_env/bin/python
PORT=${PORT:-/dev/ttyACM0}

cd "$(dirname "$0")"

if fuser "$PORT" >/dev/null 2>&1; then
    echo "$PORT 를 다른 프로세스가 쓰고 있습니다:" >&2
    fuser -v "$PORT" >&2 || true
    echo "먼저 ros2_ws/tools/ros_cleanup.sh 를 돌리세요." >&2
    exit 1
fi

# The activate script decides whether it was sourced by looking at $0 and
# accepting only shell names, so sourcing it from a file called monitor.sh is
# refused however it is invoked. Its own -e mode prints the assignments instead.
while IFS='=' read -r name value; do
    case "$name" in
        PATH) toolchain_path=$value ;;
        SYSTEM_PATH) system_path=$value ;;
        ?*) export "$name=$value" ;;
    esac
done < <("$IDF_ACTIVATE" -e)

export PATH="${toolchain_path:?활성화 스크립트가 PATH 를 내놓지 않았습니다}:${system_path:-$PATH}"

if [ -z "${IDF_PATH:-}" ]; then
    echo "IDF_PATH 가 설정되지 않았습니다. $IDF_ACTIVATE 를 확인하세요." >&2
    exit 1
fi

echo "종료는 Ctrl+]  (포크가 곧 내려갑니다 — 밑을 비우세요)"
exec "$IDF_PYTHON" "$IDF_PATH/tools/idf.py" -p "$PORT" monitor
