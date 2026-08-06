#!/usr/bin/env bash
# DDS 공유메모리 잔재 정리 + UDP 전용 설정 안내.
#
# 프로세스를 pkill -9 로 끊으면 /dev/shm 의 fastrtps_* 세그먼트가 남는다.
# 수백 개가 쌓이면 새 포트를 못 열어 발행이 블로킹되고, Isaac 은 /clock 을
# 메인 스레드에서 발행하므로 창 전체가 멈춘다(GPU 0% 인 채로 not responding).
#
# 사용: 모든 ROS/Isaac 프로세스를 끈 상태에서
#   ./nav2/scripts/clean_dds.sh

echo "=== 실행 중인 ROS/Isaac 프로세스 ==="
RUNNING=$(pgrep -c -f "isaac-sim|kit.*isaac|controller_server|planner_server|bt_navigator" 2>/dev/null || echo 0)
if [ "$RUNNING" -gt 0 ]; then
  echo "  ⚠ 아직 $RUNNING 개가 돌고 있습니다."
  echo "    Isaac 창을 닫고, Nav2 터미널을 Ctrl+C 한 뒤 다시 실행하세요."
  echo "    (돌고 있는 상태에서 지우면 그 프로세스들이 통신을 잃습니다)"
  read -p "  그래도 진행할까요? [y/N] " ans
  [ "$ans" = "y" ] || exit 1
fi

BEFORE=$(ls /dev/shm 2>/dev/null | grep -ci -E "fastrtps|fastdds")
echo "=== 정리 전: $BEFORE 개 ==="
rm -f /dev/shm/fastrtps_* /dev/shm/fastdds_* /dev/shm/sem.fastrtps_* 2>/dev/null
AFTER=$(ls /dev/shm 2>/dev/null | grep -ci -E "fastrtps|fastdds")
echo "=== 정리 후: $AFTER 개 ==="

echo ""
echo "재발 방지: 공유메모리 대신 UDP 만 쓰도록 설정돼 있습니다."
echo "  FASTRTPS_DEFAULT_PROFILES_FILE=/home/ubuntu/forklift_ws/nav2/config/fastdds_udp_only.xml"
echo "  (run_isaac_gui.sh / run_all.sh 가 자동으로 설정합니다)"
