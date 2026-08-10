#!/usr/bin/env bash
# 원격 RViz 가 오린을 볼 수 있는지 노트북에서 확인한다.
#
# 원격 디스커버리는 **에러 없이 실패한다.** 도메인이 다르면 토픽 목록이 그냥
# 비고, DDS 프로파일이 한쪽만 걸리면 목록은 보이는데 데이터가 안 온다. 둘 다
# "RViz 가 안 뜬다" 로만 보여서, 무엇을 고쳐야 할지 알 수 없다.
#
# 이 스크립트는 그 둘을 갈라 준다. 노트북에서 실행할 것:
#
#     ROS_DOMAIN_ID=100 bash remote_rviz_check.sh
#
# 오린에서 실행하면 자기 자신이 보이므로 통과해도 의미가 없다.
set -uo pipefail

echo "환경"
echo "  ROS_DOMAIN_ID              = ${ROS_DOMAIN_ID:-(미설정 → 0)}"
echo "  ROS_LOCALHOST_ONLY         = ${ROS_LOCALHOST_ONLY:-(미설정 → 0)}"
echo "  FASTRTPS_DEFAULT_PROFILES_FILE = ${FASTRTPS_DEFAULT_PROFILES_FILE:-(미설정)}"
echo "  ROS_DISTRO                 = ${ROS_DISTRO:-(미설정)}"

if [ "${ROS_LOCALHOST_ONLY:-0}" = "1" ]; then
    echo
    echo "⚠️  ROS_LOCALHOST_ONLY=1 이면 다른 기기를 볼 수 없다. 0 으로 둘 것."
fi
if [ -n "${FASTRTPS_DEFAULT_PROFILES_FILE:-}" ] && \
   [ ! -f "${FASTRTPS_DEFAULT_PROFILES_FILE}" ]; then
    echo
    echo "⚠️  프로파일 파일이 없다: ${FASTRTPS_DEFAULT_PROFILES_FILE}"
    echo "    경로가 틀리면 Fast DDS 는 **조용히 기본값으로 돈다.**"
fi

echo
echo "1) 토픽이 보이는가 (도메인·네트워크)"
topics=$(timeout 20 ros2 topic list 2>/dev/null | grep -v '^/parameter_events$\|^/rosout$')
count=$(printf '%s\n' "$topics" | grep -c . || true)
if [ "${count:-0}" -eq 0 ]; then
    echo "   ✗ 하나도 안 보인다."
    echo "     - 오린에서 echo \$ROS_DOMAIN_ID 로 값을 확인해 맞출 것"
    echo "     - 같은 공유기인지, VPN 이면 initialPeersList 프로파일을 쓸 것"
    exit 1
fi
echo "   ✓ ${count}개"

echo
echo "2) 데이터가 실제로 오는가 (전송·프로파일)"
ok=0
for t in /scan /debug/hud /plan; do
    printf '%s\n' "$topics" | grep -qx "$t" || { printf '   - %-12s 없음\n' "$t"; continue; }
    if timeout 12 ros2 topic echo "$t" --once >/dev/null 2>&1; then
        printf '   ✓ %-12s 수신\n' "$t"; ok=$((ok + 1))
    else
        printf '   ✗ %-12s 목록엔 있는데 데이터가 안 온다\n' "$t"
    fi
done

if [ "$ok" -eq 0 ]; then
    echo
    echo "   토픽은 보이는데 데이터가 없다 = **전송 설정 불일치**가 거의 확실하다."
    echo "   양쪽에 같은 DDS 프로파일을 걸었는지 확인할 것."
    exit 1
fi

echo
echo "준비됐다. rviz2 -d forklift.rviz 로 띄우고 /debug/hud 를 MarkerArray 로 추가할 것."
