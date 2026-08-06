#!/usr/bin/env bash
# 회피 파이프라인 전체를 한 번에 진단한다.
# 어디가 끊겼는지 한 화면에 보여주는 것이 목적.
#
#   ./diagnose.sh

source /opt/ros/humble/setup.bash

echo "════════ 1. Isaac 시뮬 (clock) ════════"
HZ=$(timeout 4 ros2 topic hz /clock 2>&1 | grep -m1 "average rate")
if [ -z "$HZ" ]; then
    echo "  ✗ clock 안 돎 -> Isaac 정지. Stop→Play 필요. (이하 전부 무의미)"
else
    echo "  ✓ $HZ"
fi

echo
echo "════════ 2. 차량 위치 ════════"
for f in SIM_F02 SIM_F03; do
    P=$(timeout 4 ros2 run tf2_ros tf2_echo map ${f}_base_link \
        --ros-args -p use_sim_time:=true 2>/dev/null | grep -m1 Translation)
    echo "  $f: ${P:-✗ TF 없음}"
done

echo
echo "════════ 3. 라이다 ════════"
for ns in sim_f02 sim_f03; do
    R=$(timeout 4 ros2 topic echo /$ns/scan --once --field ranges 2>/dev/null | python3 -c "
import sys,re
n=[float(x) for x in re.findall(r'-?\d+\.\d+',sys.stdin.read())]
v=[x for x in n if 0.05<x<25]
print(f'유효 {len(v)}개, 최소 {min(v):.2f} m' if v else '전부 -1 (죽음)')" 2>/dev/null)
    echo "  /$ns/scan: ${R:-✗ 토픽 없음}"
done

echo
echo "════════ 4. fleet_obstacles (상대 몸통) ════════"
for ns in sim_f02 sim_f03; do
    N=$(timeout 4 ros2 topic echo /$ns/fleet_obstacles --once --field width 2>/dev/null | head -1)
    if [ -z "$N" ]; then
        echo "  /$ns/fleet_obstacles: ✗ 발행 없음 -> fleet_obstacles.py 를 켜야 함"
    else
        echo "  /$ns/fleet_obstacles: ✓ 점 $N 개"
    fi
done

echo
echo "════════ 5. Nav2 가 fleet 소스를 쓰나 (재시작 여부) ════════"
for cm in global_costmap local_costmap; do
    S=$(timeout 5 ros2 param get /sim_f02/$cm/$cm obstacle_layer.observation_sources 2>/dev/null | tail -1)
    echo "  $cm: ${S:-✗ 조회 실패}"
done
echo "  (fleet 이 없으면 config 변경 후 Nav2 를 재시작하지 않은 것)"

echo
echo "════════ 6. 유령 노드 ════════"
D=$(ros2 node list 2>/dev/null | grep sim_f02 | sort | uniq -c | awk '$1>1' | head -5)
if [ -z "$D" ]; then echo "  ✓ 중복 없음"; else echo "  ✗ 중복:"; echo "$D"; fi

echo
echo "════════ 7. inflation ════════"
V=$(timeout 5 ros2 param get /sim_f02/global_costmap/global_costmap \
    inflation_layer.inflation_radius 2>/dev/null | tail -1)
echo "  global inflation_radius: ${V:-조회 실패}"
