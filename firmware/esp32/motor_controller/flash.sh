#!/usr/bin/env bash
#
# Build and flash the motor controller.
#
# Two things make the bare `idf.py` call fail here, and both are easy to hit
# again after a break:
#
#   1. activate_idf_v5.5.5.sh puts a different interpreter on PATH than the one
#      the project was configured with, and idf.py refuses to run rather than
#      silently reconfiguring. It has to be named explicitly. Do NOT answer
#      that refusal with `idf.py fullclean` -- that throws the build away.
#   2. The ROS bridge holds /dev/ttyACM0. `ros2 launch` leaves the node behind
#      when the launcher dies, so killing the launcher is not enough.
#
# Usage:  ./flash.sh [monitor]
set -euo pipefail

IDF_ACTIVATE=/home/orin/.espressif/tools/activate_idf_v5.5.5.sh
IDF_PYTHON=/home/orin/.espressif/python_env/idf5.5_py3.10_env/bin/python
PORT=${PORT:-/dev/ttyACM0}

cd "$(dirname "$0")"

if fuser "$PORT" >/dev/null 2>&1; then
    echo "$PORT 를 다른 프로세스가 쓰고 있어 브리지를 내립니다."
    pkill -f "forklift_teleop/lib/forklift_teleop/sensor_bridge" || true
    pkill -f "ros2 launch forklift_teleop" || true
    sleep 2
fi

if fuser "$PORT" >/dev/null 2>&1; then
    echo "$PORT 가 아직 사용 중입니다. 아래 프로세스를 확인하세요:" >&2
    fuser -v "$PORT" >&2 || true
    exit 1
fi

# The activate script decides whether it was sourced by looking at $0 and
# accepting only shell names, so sourcing it from a file called flash.sh is
# refused however it is invoked. Its own -e mode exists for exactly this: it
# prints the assignments instead of applying them.
#
# SYSTEM_PATH is the caller's PATH, which -e reports separately; PATH alone
# holds only the toolchain, so the two are joined or ninja is found and cmake
# is not.
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

"$IDF_PYTHON" "$IDF_PATH/tools/idf.py" -p "$PORT" flash

echo
echo "플래시 완료. 매핑 확인:"
echo "  python3 tools/tof_probe.py 30 | grep mapping"

if [ "${1:-}" = "monitor" ]; then
    "$IDF_PYTHON" "$IDF_PATH/tools/idf.py" -p "$PORT" monitor
fi
