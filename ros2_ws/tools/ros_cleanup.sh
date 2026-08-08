#!/usr/bin/env bash
#
# Kill every node this workspace starts, and wait until the serial ports are
# actually free.
#
# `ros2 launch` does not reap its children. Ctrl-C on the launcher leaves the
# nodes running, and the next launch then has two of everything. That is not a
# cosmetic problem:
#
#   - two sensor_bridge instances fight over /dev/ttyACM0 and both get partial
#     byte streams, so IMU or ToF frames simply stop appearing
#   - two rf2o instances publish contradictory odom, and the costmap logs
#     "Sensor origin is out of map bounds" while the vehicle sits still
#
# On 2026-08-07 there were twenty-odd orphans up to 4.8 hours old, and the
# symptom looked like a ToF hardware fault.
#
# Run this before every launch. It is idempotent.
set -uo pipefail

WORKSPACE=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PATTERNS='forklift_teleop/lib|ydlidar_ros2_driver/lib|rf2o_laser_odometry/lib'
PATTERNS+='|tf2_ros/static_transform_publisher|slam_toolbox'
PATTERNS+='|nav2_controller/controller_server|nav2_planner/planner_server'
PATTERNS+='|bt_navigator|behavior_server|smoother_server|velocity_smoother'
PATTERNS+='|waypoint_follower|lifecycle_manager|nav2_map_server'
# ⚠️ robot_localization/ekf_node 가 빠져 있었다. 살아남은 EKF 는 다른 것들보다
#    나쁘다 -- odom->base_link 를 자기도 발행하므로, 두 개가 동시에 살아 있으면
#    TF 리스너가 마지막에 온 것을 쓰면서 자세가 두 값 사이를 튄다.
#
#    2026-08-08 에 세 개가 떠 있었고, 정지한 차의 yaw 가 -45도와 0도 사이를
#    오갔다. RViz 에서는 점군이 통째로 흔들리는 것으로 보이고, 센서를 아무리
#    들여다봐도 원인이 안 나온다 -- 센서는 멀쩡하기 때문이다.
PATTERNS+='|robot_localization/ekf_node'
PORTS=${PORTS:-"/dev/ttyACM0 /dev/ttyUSB0 /dev/ttyTHS1"}

pkill -f "ros2 launch forklift_teleop" 2>/dev/null
sleep 1

# TERM first, then KILL. Nodes holding a serial port do not always close it on
# TERM, and a half-closed port is what produces the two-readers symptom.
for signal in TERM KILL; do
    mapfile -t pids < <(pgrep -f "$PATTERNS" 2>/dev/null)
    [ ${#pids[@]} -eq 0 ] && break
    printf '%s 로 %d개 종료\n' "$signal" "${#pids[@]}"
    for pid in "${pids[@]}"; do kill "-$signal" "$pid" 2>/dev/null; done
    sleep 2
done

# The daemon caches the discovery graph; a stale one makes `ros2 node list`
# report nodes that are already gone, which sends you chasing ghosts.
ros2 daemon stop >/dev/null 2>&1

remaining=$(pgrep -cf "$PATTERNS" 2>/dev/null || true)
[ "${remaining:-0}" -gt 0 ] && printf '⚠️  아직 %s개 남음 — 직접 확인할 것\n' "$remaining"

# Fast DDS leaves its shared-memory port files behind when a node is killed
# rather than shut down, and the leftovers are worse than useless: the segment
# itself is gone but the lock file and semaphore remain, so every later node
# logs "open_and_lock_file failed" and silently loses that port.
#
# It is not cosmetic noise. On 2026-08-07 it cost an action goal response --
# bt_navigator accepted a NavigateToPose goal, could not deliver the reply,
# and the client reported the goal rejected while the robot began driving.
# Only safe with everything stopped, which is where we are by now.
if ! pgrep -cf "$PATTERNS" >/dev/null 2>&1; then
    stale=$(ls /dev/shm 2>/dev/null | grep -c fastrtps || true)
    if [ "${stale:-0}" -gt 0 ]; then
        rm -f /dev/shm/fastrtps_* /dev/shm/sem.fastrtps_* 2>/dev/null
        printf '   DDS 공유메모리 찌꺼기 %s개 정리\n' "$stale"
    fi
fi

status=0
for port in $PORTS; do
    [ -e "$port" ] || continue
    if fuser "$port" >/dev/null 2>&1; then
        printf '⚠️  %s 사용 중:\n' "$port"
        fuser -v "$port" 2>&1 | tail -n +2
        status=1
    else
        printf '   %s 해제됨\n' "$port"
    fi
done

if [ "$status" -eq 0 ]; then
    printf '정리 완료 — 실행해도 됩니다\n'
    # The launch file sets this for the nodes it starts, but a tool run by
    # hand does not inherit it, and a participant that still opens shared
    # memory can recreate the stale files this script just removed.
    if [ -z "${FASTRTPS_DEFAULT_PROFILES_FILE:-}" ]; then
        profile="$WORKSPACE/install/forklift_teleop/share/forklift_teleop/config/fastdds_no_shm.xml"
        if [ -e "$profile" ]; then
            printf '\n손으로 도구를 실행할 때는 먼저:\n'
            printf '   export FASTRTPS_DEFAULT_PROFILES_FILE=%s\n' "$profile"
        fi
    fi
fi
exit "$status"
