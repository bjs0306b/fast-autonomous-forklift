#!/usr/bin/env bash
# Isaac 이 멈췄을 때 "어디서 막혀 있는지" 를 실제로 들여다본다.
#
# 지금까지는 증상만 보고 원인을 추측했다. 이 스크립트는 멈춘 순간의 파이썬
# 스택을 그대로 떠서, 어느 함수에서 블로킹 중인지 확정한다.
#
# 사용: Isaac 이 "not responding" 인 상태에서 다른 터미널에서
#   ./nav2/scripts/why_frozen.sh
#
# 결과 해석
#   rclpy / _rclpy / publish 계열에서 멈춤   -> DDS 발행 블로킹
#   usd / Sdf / pcp 계열                    -> USD 스테이지 재파싱
#   physx / omni.physx                      -> 물리
#   render / rtx / lidar                    -> 렌더·센서
#   아무 스택도 못 뜸                        -> 파이썬 밖(C++)에서 멈춤

export PATH="$HOME/.local/bin:$PATH"
OUT=/tmp/isaac_frozen.txt

PID=$(pgrep -f "isaac-sim|kit/kit|omni.isaac" | head -1)
if [ -z "$PID" ]; then
  echo "Isaac 프로세스를 못 찾음. 실행 중인지 확인하세요."
  exit 1
fi
echo "Isaac PID: $PID"

echo "=== 프로세스 상태 ===" | tee "$OUT"
ps -o pid,stat,pcpu,pmem,etime,wchan:24 -p "$PID" | tee -a "$OUT"
echo "  (STAT D = 디스크/IO 대기, S = 슬립, R = 실행)" | tee -a "$OUT"

echo "" | tee -a "$OUT"
echo "=== GPU ===" | tee -a "$OUT"
nvidia-smi --query-gpu=utilization.gpu,memory.used,memory.free --format=csv 2>/dev/null | tee -a "$OUT"

echo "" | tee -a "$OUT"
echo "=== 파이썬 스택 (멈춘 지점) ===" | tee -a "$OUT"
if command -v py-spy >/dev/null; then
  # py-spy 는 다른 프로세스 메모리를 읽으므로 권한이 필요하다(ptrace).
  # 먼저 그냥 시도하고, 권한 거부면 sudo 로 다시 시도한다.
  if ! py-spy dump --pid "$PID" 2>&1 | tee -a "$OUT" | grep -q "Permission Denied"; then
    :
  else
    echo "  권한 필요 - sudo 로 재시도합니다 (비밀번호 입력)" | tee -a "$OUT"
    sudo env "PATH=$PATH" py-spy dump --pid "$PID" 2>&1 | tee -a "$OUT"
  fi
else
  echo "py-spy 없음:  pip3 install --user py-spy" | tee -a "$OUT"
fi

echo "" | tee -a "$OUT"
echo "=== 스레드별 CPU (누가 태우는가) ===" | tee -a "$OUT"
top -H -b -n 1 -p "$PID" 2>/dev/null | tail -n +7 | head -12 | tee -a "$OUT"

echo "" | tee -a "$OUT"
echo "=== DDS 공유메모리 잔재 ===" | tee -a "$OUT"
echo "  $(ls /dev/shm 2>/dev/null | grep -ci -E 'fastrtps|fastdds') 개" | tee -a "$OUT"

echo ""
echo "전체 결과: $OUT"
