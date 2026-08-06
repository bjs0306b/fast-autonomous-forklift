#!/usr/bin/env bash
# 회피 크로싱 테스트: TF 안정 대기 -> 위치 확인 -> 목표 -> 경로(plan) y범위 출력.
#
# 먼저 Isaac Script Editor 에서 위치 리셋을 실행한 뒤 이 스크립트를 돌린다.
#   기본 목표 (10,4). 인자로 바꿀 수 있음:  ./test_cross.sh 2 4
#
# 리셋 스니펫(Script Editor):
#   for vid, sp in [("SIM_F02",(2.0,4.0,0.0)), ("SIM_F03",(6.0,4.0,0.0))]:
#       v = fleet.vehicles[vid]
#       v.x, v.y, v.yaw = sp; v.v=0.0; v.steer=0.0; v._steps=[]
#   print("리셋")

source /opt/ros/humble/setup.bash

GX=${1:-10.0}
GY=${2:-4.0}

echo "TF 안정 대기 (stale 방지)..."; sleep 3
echo -n "F02 위치: "; timeout 5 ros2 run tf2_ros tf2_echo map SIM_F02_base_link \
  --ros-args -p use_sim_time:=true 2>/dev/null | grep -m1 Translation
echo -n "F03 위치: "; timeout 5 ros2 run tf2_ros tf2_echo map SIM_F03_base_link \
  --ros-args -p use_sim_time:=true 2>/dev/null | grep -m1 Translation

# 순간이동으로 리셋하면 costmap 에 옛 위치의 마킹이 잔상으로 남는다. 지우고 시작.
echo "costmap 청소..."
for cm in global local; do
  timeout 5 ros2 service call /sim_f02/${cm}_costmap/clear_entirely_${cm}_costmap \
      nav2_msgs/srv/ClearEntireCostmap "{}" >/dev/null 2>&1
done
sleep 2

echo ">>> 목표 ($GX,$GY) 로 크로싱"
ros2 action send_goal /sim_f02/navigate_to_pose nav2_msgs/action/NavigateToPose \
  "{pose: {header: {frame_id: map}, pose: {position: {x: $GX, y: $GY}, orientation: {w: 1.0}}}}" &
GOAL_PID=$!

sleep 2
echo -n "경로: "
ros2 topic echo /sim_f02/plan --once | python3 -c "
import sys
s=sys.stdin.read(); ys=[]; m=None
for l in s.splitlines():
    t=l.strip()
    if t=='position:': m='p'
    elif t=='orientation:': m='o'
    elif m=='p' and t.startswith('y:'):
        try: ys.append(float(t.split(':')[1]))
        except: pass
print('경로점',len(ys),'| y범위',round(min(ys),2),'~',round(max(ys),2) if ys else 'X')"

wait $GOAL_PID
