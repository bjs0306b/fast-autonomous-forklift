#!/usr/bin/env bash
# 같은 노드가 두 번 떠 있지 않은지 본다. launch 직후에 한 번 돌릴 것.
#
# 중복 노드는 이 프로젝트에서 **가장 비싼 버그였다.** 셋 다 증상만 보면 원인이
# 안 나온다:
#
#   ekf_node 3개        정지한 차의 yaw 가 -45도와 0도 사이를 튄다. 센서는 정상.
#   orin_telemetry 7개  같은 좌표를 일곱 번 발행한다. 관제는 도착 순서대로 본다.
#   uart_teleop_bridge 3개  같은 시리얼 포트에 서로 다른 조향을 번갈아 쓴다 --
#                       서보가 10 Hz 로 54도와 0도를 오가고, 차는 안 돈다.
#
# 어느 것도 에러를 내지 않는다. 각 노드는 자기 일을 정확히 하고 있고, 로그도
# 깨끗하다. 그래서 밖에서 세는 수밖에 없다.
#
#     bash tools/check_duplicates.sh
#
# ⚠️ ros2 launch 를 ros_cleanup.sh 와 **같은 명령줄에 이어 쓰지 말 것.**
#    정리 스크립트의 패턴이 그 셸의 명령줄에도 걸려 launch 가 뜨기 전에 죽는다.
#    (스크립트가 자기 조상을 걸러 내도록 고쳐 두었지만, 나눠 쓰는 편이 낫다.)
set -uo pipefail

if ! command -v ros2 >/dev/null 2>&1; then
    echo "ros2 를 못 찾았다 -- setup.bash 를 source 할 것"
    exit 2
fi

# ros2 node list 는 같은 이름을 중복해서 그대로 내보낸다. 그것이 여기서 쓰는
# 신호다 -- 이름이 겹치면 경고까지 함께 찍어 준다.
listing=$(timeout 25 ros2 node list 2>/dev/null | grep -v '^/transform_listener_impl' | sort)
if [ -z "$listing" ]; then
    echo "노드가 하나도 안 보인다 -- 스택이 안 떠 있거나 DDS 가 막혔다"
    exit 2
fi

duplicates=$(printf '%s\n' "$listing" | uniq -c | awk '$1 > 1')
if [ -z "$duplicates" ]; then
    printf '중복 없음 (노드 %d개)\n' "$(printf '%s\n' "$listing" | wc -l)"
    exit 0
fi

# ⚠️ **이름이 두 번 보인다고 프로세스가 둘인 것은 아니다.** 어떤 노드는
#    디스커버리에 자기를 두 번 등록하고(rf2o_laser_odometry 가 그렇다),
#    데몬이 죽은 노드를 잠시 캐시하기도 한다. 거짓 경보를 그대로 두면 진짜
#    경보까지 무시하게 되므로, 실제 프로세스 수로 한 번 더 확인한다.
real=0
echo "이름이 겹치는 노드:"
while read -r count name; do
    [ -z "$name" ] && continue
    short=${name#/}
    # 대괄호로 자기 pgrep 명령줄이 매치되는 것을 막는다.
    pattern="[${short:0:1}]${short:1}"
    procs=$(pgrep -cf "$pattern" 2>/dev/null || echo 0)
    if [ "${procs:-0}" -gt 1 ]; then
        printf '   ⚠️  %s x%s (프로세스 %s개) -- 진짜 중복\n' "$name" "$count" "$procs"
        real=$((real + 1))
    else
        printf '   %s x%s (프로세스 %s개) -- 디스커버리 중복, 무해\n' \
            "$name" "$count" "$procs"
    fi
done <<< "$duplicates"

if [ "$real" -eq 0 ]; then
    echo
    echo "진짜 중복은 없다."
    exit 0
fi
echo
echo "정리: bash tools/ros_cleanup.sh 를 **따로** 실행한 뒤 다시 띄울 것."
echo "손으로 죽일 때는 대괄호를 쓸 것 -- pkill -f drive_mux 는 자기 셸도 죽인다:"
echo "   kill \$(pgrep -f '[d]rive_mux')"
exit 1
