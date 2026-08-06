#!/usr/bin/env bash
# 다중 지게차용 Nav2 — 각 차량을 자기 네임스페이스 안에서 실행한다.
#
# 왜 네임스페이스인가:
#   Nav2 노드(controller_server, planner_server ...)는 이름이 고정이라, 두 대를
#   그냥 두 번 실행하면 같은 이름 노드가 충돌한다. 네임스페이스(/sim_f02, /sim_f03)
#   안에서 실행하면 /sim_f02/controller_server 처럼 분리되어 충돌하지 않는다.
#
# 각 차량은 자기 map->odom static TF 를 갖고, 공용 맵(pgm)을 각자 로드한다.
#
# 사용:
#   ./nav2/scripts/run_nav2_multi.sh
#   (Isaac + kinematic_vehicle.py(F02,F03) 가 먼저 떠 있어야 함)
#
# 종료: Ctrl+C

set -e
source /home/ubuntu/ros_env.sh

MAP=/home/ubuntu/forklift_ws/nav2/maps/sim_warehouse.yaml
CFG_DIR=/home/ubuntu/forklift_ws/nav2/config

# 차량 목록: "네임스페이스:프레임접두사:파라미터파일"
VEHICLES=(
  "sim_f02:SIM_F02:$CFG_DIR/nav2_sim.yaml"
  "sim_f03:SIM_F03:$CFG_DIR/nav2_sim_f03.yaml"
)

# 이전 잔여물 정리 (모든 Nav2 노드)
pkill -9 -f 'controller_server|smoother_server|planner_server|bt_navigator|behavior_server|waypoint_follower|velocity_smoother|collision_monitor|opennav_docking|route_server|amcl|map_server|lifecycle_manager|static_transform_publisher' 2>/dev/null || true
sleep 3

PIDS=()

# 1) 공용 맵 서버 한 개 (모든 차량이 /map 공유)
ros2 run nav2_map_server map_server --ros-args \
    -p use_sim_time:=true -p yaml_filename:="$MAP" -r __node:=map_server &
PIDS+=($!)
sleep 2
ros2 run nav2_lifecycle_manager lifecycle_manager --ros-args \
    -p use_sim_time:=true -p autostart:=true \
    -p node_names:="['map_server']" -r __node:=lifecycle_manager_map &
PIDS+=($!)
sleep 3

# 2) 차량별 static TF (map -> {frame}_odom) + Nav2 스택 (네임스페이스 안)
for entry in "${VEHICLES[@]}"; do
    IFS=':' read -r ns frame params <<< "$entry"
    echo ">>> $ns ($frame) Nav2 시작"

    # map -> {frame}_odom 고정 (AMCL 대신)
    ros2 run tf2_ros static_transform_publisher \
        --x 0 --y 0 --z 0 --roll 0 --pitch 0 --yaw 0 \
        --frame-id map --child-frame-id "${frame}_odom" \
        --ros-args -p use_sim_time:=true -r __node:="static_tf_${ns}" &
    PIDS+=($!)
    sleep 1

    # Nav2 스택을 PushRosNamespace 로 감싸 확실히 /{ns}/ 아래로 밀어넣는다.
    # (humble navigation_launch 의 namespace 인자만으론 노드에 안 붙는다)
    ros2 launch /home/ubuntu/forklift_ws/nav2/scripts/ns_nav2_launch.py \
        namespace:="$ns" \
        params_file:="$params" &
    PIDS+=($!)
    sleep 6
done

echo ""
echo "=== 다중 Nav2 기동 완료 (${#VEHICLES[@]}대) ==="
echo "목표 예: ros2 action send_goal /sim_f02/navigate_to_pose ..."
echo "Ctrl+C 로 전체 종료"

# Ctrl+C 시 전부 정리
trap 'kill ${PIDS[@]} 2>/dev/null; exit 0' INT TERM
wait
