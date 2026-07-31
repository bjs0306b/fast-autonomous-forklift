#!/usr/bin/env bash
# Launch Nav2 for SIM_F02 with STATIC map->odom instead of AMCL.
#
# Why not just kill amcl after run_nav2.sh:
#   localization_launch starts amcl under a lifecycle_manager that watches it
#   with a bond. Killing amcl makes the manager declare
#     "CRITICAL FAILURE: SERVER amcl IS DOWN ... Shutting down related nodes"
#   and it tears down map_server too, so /map disappears and the costmaps break.
#   The fix is to never start amcl: run map_server on its own lifecycle manager
#   and publish map->odom as a fixed transform.
#
# Why static at all:
#   The kinematic odom (odom->base_link from kinematic_vehicle.py) is ground
#   truth here - it is written from the same numbers that move the prim. AMCL's
#   scan matching only adds error: in this repetitive warehouse it locks onto the
#   wrong aisle and drifts metres off. map->odom = identity works because the
#   odom origin is the world origin and scan_map.py built the map in the same
#   world coordinates, so map cells line up with reality.
#
# Prerequisites: Isaac running + Play, kinematic_vehicle.py loaded, 2D lidar
# publishing /sim_f02/scan.

set -e

source /home/ubuntu/ros_env.sh

MAP=${1:-/home/ubuntu/forklift_ws/nav2/maps/sim_warehouse.yaml}
PARAMS=${2:-/home/ubuntu/forklift_ws/nav2/config/nav2_sim.yaml}

echo "map:    $MAP"
echo "params: $PARAMS"
echo

# Clear anything left from a previous run. pkill -f nav2 does NOT work: the node
# executables are named controller_server, planner_server, ... with no "nav2" in
# the command line, so they survive and the new run collides with the leftovers
# (symptom: "Failed to change state for node" on a different node each restart).
pkill -9 -f 'controller_server|smoother_server|planner_server|bt_navigator|behavior_server|waypoint_follower|velocity_smoother|collision_monitor|opennav_docking|route_server|amcl|map_server|lifecycle_manager|static_transform_publisher' 2>/dev/null || true
sleep 3

# 1) map->odom, fixed. This replaces AMCL entirely.
ros2 run tf2_ros static_transform_publisher \
    --x 0 --y 0 --z 0 --roll 0 --pitch 0 --yaw 0 \
    --frame-id map --child-frame-id SIM_F02_odom \
    --ros-args -p use_sim_time:=true &
STATIC_PID=$!
sleep 1

# 2) map_server alone (the static layer of global_costmap needs /map), managed by
#    its own lifecycle manager so nothing is watching a non-existent amcl.
ros2 run nav2_map_server map_server --ros-args \
    -p use_sim_time:=true \
    -p yaml_filename:="$MAP" \
    -r __node:=map_server &
MAP_PID=$!
sleep 2

ros2 run nav2_lifecycle_manager lifecycle_manager --ros-args \
    -p use_sim_time:=true \
    -p autostart:=true \
    -p node_names:="['map_server']" \
    -r __node:=lifecycle_manager_localization &
LM_PID=$!
sleep 4

# 3) navigation (planner, controller, bt_navigator, behaviors, smoother,
#    collision_monitor, docking). Runs in the foreground so Ctrl+C stops it.
ros2 launch nav2_bringup navigation_launch.py \
    use_sim_time:=True \
    params_file:="$PARAMS"

# On Ctrl+C, take the background helpers down too.
kill $STATIC_PID $MAP_PID $LM_PID 2>/dev/null || true
