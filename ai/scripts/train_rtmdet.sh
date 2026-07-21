#!/usr/bin/env bash
# RTMDet 학습 실행 (FR-101-3). 배정 GPU 1장에 고정하고 detached(nohup)로 던진다.
#
# ai/ 디렉터리에서 실행한다:  cd ai
#   bash scripts/train_rtmdet.sh smoke   # 200장·1ep 스모크 — 경로·파이프라인 검증(포그라운드)
#   bash scripts/train_rtmdet.sh         # 풀 학습 (백그라운드, 브라우저 닫아도 유지)
#
# 공유 GPU 서버라 반드시 배정 device에 고정한다(매뉴얼 Device1 기본).
# 다른 번호면:  GPU=2 bash scripts/train_rtmdet.sh
set -euo pipefail

MODE=${1:-full}
GPU=${GPU:-1}
CONFIG=configs/rtmdet_s_forklift.py
WORKDIR=work_dirs/rtmdet_s_forklift
mkdir -p "$WORKDIR"

if [ "$MODE" = "smoke" ]; then
  echo "[스모크] GPU $GPU · 200장 · 1epoch — 포그라운드"
  CUDA_VISIBLE_DEVICES=$GPU mim train mmdet "$CONFIG" --gpus 1 \
    --work-dir "$WORKDIR/smoke" \
    --cfg-options train_cfg.max_epochs=1 \
                  train_dataloader.dataset.indices=200 \
                  val_dataloader.dataset.indices=100
else
  LOG="$WORKDIR/train_$(date +%Y%m%d_%H%M%S).log"
  echo "[풀학습] GPU $GPU — nohup 백그라운드"
  CUDA_VISIBLE_DEVICES=$GPU nohup mim train mmdet "$CONFIG" --gpus 1 \
    --work-dir "$WORKDIR" > "$LOG" 2>&1 &
  echo "PID $!  로그: tail -f $LOG"
fi
