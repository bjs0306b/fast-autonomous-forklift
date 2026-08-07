#!/usr/bin/env bash
#
# Save the SLAM map in both forms, into one dated directory.
#
# Two forms, because they are not interchangeable:
#
#   *.posegraph / *.data  (slam_toolbox serialize_map)
#       The pose graph. Only slam_toolbox reads it, and only it can carry on
#       mapping from where this left off. Keep it -- a map you cannot extend
#       has to be rebuilt from scratch when the mockup changes.
#
#   *.pgm / *.yaml        (nav2_map_server map_saver_cli)
#       A plain occupancy image. This is what nav2's static layer, Isaac Sim,
#       and anything outside ROS can read. This is the handover artifact.
#
# Usage:
#   tools/save_map.sh                 # maps/2026-08-07-1830/
#   tools/save_map.sh mockup-v2       # maps/mockup-v2/
set -uo pipefail

WORKSPACE=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
NAME=${1:-$(date +%Y-%m-%d-%H%M)}
OUT="$WORKSPACE/maps/$NAME"

if ! ros2 service list 2>/dev/null | grep -q /slam_toolbox/serialize_map; then
    printf '⚠️  slam_toolbox 가 안 떠 있습니다. 스택을 먼저 실행하세요.\n' >&2
    exit 1
fi

mkdir -p "$OUT"

printf '포즈 그래프 저장 (slam_toolbox 전용, 이어서 매핑 가능)\n'
ros2 service call /slam_toolbox/serialize_map \
    slam_toolbox/srv/SerializePoseGraph \
    "{filename: '$OUT/posegraph'}" >/dev/null

# map_saver_cli exits on its own once it has written the file, but it hangs
# forever if /map never arrives -- which is what happens when slam_toolbox is
# starved and has stopped publishing. Bound it rather than wait for a person
# to notice.
printf '점유 격자 저장 (nav2 · Isaac Sim 이 읽는 형식)\n'
timeout 60 ros2 run nav2_map_server map_saver_cli \
    -f "$OUT/map" --ros-args -p save_map_timeout:=30.0 >/dev/null 2>&1
status=$?

if [ $status -ne 0 ] || [ ! -f "$OUT/map.yaml" ]; then
    printf '⚠️  점유 격자 저장 실패. /map 이 발행 중인지 확인하세요:\n' >&2
    printf '      ros2 topic hz /map\n' >&2
    printf '   부하가 높으면 slam_toolbox 가 스캔을 버리며 발행을 멈춥니다.\n' >&2
    printf '   RViz 를 끄고 다시 시도하세요.\n' >&2
    exit 1
fi

printf '\n저장 완료: %s\n' "$OUT"
ls -la "$OUT" | tail -n +2
printf '\n실물 좌표계입니다. 시뮬 좌표는 여기에 x10 -- '
printf 'isaac_sim/nav2/README.md 참고\n'
