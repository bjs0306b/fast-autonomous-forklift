#!/usr/bin/env bash
# 터미널 쪽을 한 번에 — Nav2 + fleet_obstacles + MQTT 브릿지.
#
# 먼저 Isaac 이 Play 중이고 Script Editor 에서 isaac_setup.py 를 실행한 상태여야 한다.
#   exec(open('/home/ubuntu/forklift_ws/nav2/scripts/isaac_setup.py').read())
#
# 사용:
#   ./nav2/scripts/run_all.sh              # 전부 (2대 상호 인식)
#   ./nav2/scripts/run_all.sh --solo       # 단독 주행용 (F03 을 costmap 에서 뺌)
#   ./nav2/scripts/run_all.sh --no-mqtt    # MQTT 브릿지 없이
#
# 종료: Ctrl+C (자식 프로세스 전부 정리)

# set -u 는 쓰지 않는다. ROS 의 setup.bash 가 미설정 변수를 참조해 죽는다.
DIR=/home/ubuntu/forklift_ws/nav2/scripts
SOLO=""
USE_MQTT=1
for a in "$@"; do
  case "$a" in
    --solo)    SOLO="--only sim_f02" ;;
    --no-mqtt) USE_MQTT=0 ;;
    *) echo "알 수 없는 옵션: $a"; exit 1 ;;
  esac
done

source /opt/ros/humble/setup.bash

# DDS 공유메모리 전송을 끈다(UDP 전용). pkill -9 로 남은 /dev/shm/fastrtps_*
# 세그먼트가 쌓이면 새 포트를 못 열어 발행이 블로킹되고, Isaac 은 /clock 을
# 메인 스레드에서 발행하므로 창 전체가 멈춘다. 정리는 clean_dds.sh.
export FASTRTPS_DEFAULT_PROFILES_FILE=/home/ubuntu/forklift_ws/nav2/config/fastdds_udp_only.xml

PIDS=()
cleanup() {
  echo ""
  echo ">>> 정리 중..."
  kill "${PIDS[@]}" 2>/dev/null
  pkill -9 -f 'controller_server|planner_server|bt_navigator|behavior_server|smoother_server|velocity_smoother|collision_monitor|waypoint_follower|lifecycle_manager|map_server|static_transform|demo_loop2|demo_solo|demo_rack2|demo_ccw_bay|fleet_manager|coordinator|loop_traffic' 2>/dev/null
  exit 0
}
trap cleanup INT TERM

# ── 0. clock 확인 — 이게 없으면 나머지가 전부 무의미하다 ──────────────
echo ">>> /clock 확인"
if ! timeout 5 ros2 topic hz /clock 2>/dev/null | grep -q "average rate"; then
  echo "  ✗ /clock 이 돌지 않습니다."
  echo "    Isaac 이 Play 중인지, Script Editor 에서 isaac_setup.py 를 실행했는지"
  echo "    확인하세요. (clock 이 없으면 Nav2 는 시간 0 에 멈춰 아무것도 안 움직입니다)"
  exit 1
fi
echo "  ✓ clock 정상"

# ── 1. 좀비 정리 — 두 번 켜지면 유령 노드가 생겨 가짜 SUCCEEDED 가 뜬다 ──
echo ">>> 이전 Nav2 정리"
pkill -9 -f 'controller_server|planner_server|bt_navigator|behavior_server|smoother_server|velocity_smoother|collision_monitor|waypoint_follower|lifecycle_manager|map_server|static_transform|demo_loop2|demo_solo|demo_rack2|demo_ccw_bay|fleet_manager|coordinator|loop_traffic' 2>/dev/null
sleep 3

# ── 2. Nav2 ─────────────────────────────────────────────────────────
echo ">>> Nav2 기동 (로그: /tmp/nav2.log)"
"$DIR/run_nav2_multi.sh" > /tmp/nav2.log 2>&1 &
PIDS+=($!)

# grep -c 는 0건일 때 0 을 출력하면서 종료코드 1 을 낸다. `|| echo 0` 을 붙이면
# 출력이 "0\n0" 이 되어 [ ] 비교가 깨지므로, 종료코드만 무시한다.
count_active() {
  grep -c "Managed nodes are active" /tmp/nav2.log 2>/dev/null | head -1
}

echo -n "  활성화 대기"
n=0
for i in $(seq 1 60); do
  n=$(count_active)
  [ -z "$n" ] && n=0
  if [ "$n" -ge 3 ]; then echo " ✓ (map + 차량 2대)"; break; fi
  echo -n "."
  sleep 2
done
if [ "$n" -lt 3 ]; then
  echo " ✗ 시간 초과 (활성 $n/3) — /tmp/nav2.log 를 확인하세요"
fi

# ── 3. 상대 차량 몸통을 costmap 에 넣기 ──────────────────────────────
echo ">>> fleet_obstacles ${SOLO:+(단독 모드)}"
python3 "$DIR/fleet_obstacles.py" $SOLO > /tmp/fleet_obstacles.log 2>&1 &
PIDS+=($!)
sleep 2

# ── 4. MQTT 브릿지 ──────────────────────────────────────────────────
if [ "$USE_MQTT" -eq 1 ]; then
  if python3 -c "import paho.mqtt.client" 2>/dev/null; then
    echo ">>> MQTT 브릿지 (${MQTT_HOST:-localhost}:${MQTT_PORT:-1883}, 로그: /tmp/mqtt_bridge.log)"
    # 브로커 주소·계정은 환경변수로 넘긴다. 인증이 걸린 팀 브로커면:
    #   MQTT_HOST=192.168.0.10 MQTT_USER=fast MQTT_PASS=... ./run_all.sh
    python3 "$DIR/mqtt_bridge.py" > /tmp/mqtt_bridge.log 2>&1 &
    PIDS+=($!)
  else
    echo ">>> MQTT 건너뜀 (paho-mqtt 없음:  pip3 install paho-mqtt)"
  fi
fi

sleep 2
echo ""
echo "============================================================"
echo " 준비 완료"
echo "   상태 점검 :  $DIR/diagnose.sh"
echo "   단독 순환 :  python3 $DIR/demo_ccw_bay.py"
echo "   통합 관제 :  python3 $DIR/fleet_manager.py"
echo "   로그      :  /tmp/nav2.log  /tmp/fleet_obstacles.log  /tmp/mqtt_bridge.log"
echo "   종료      :  Ctrl+C"
echo "============================================================"

wait
