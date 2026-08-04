# 파인튜닝/평가 파이프라인 스테이징

데모 도메인 데이터(실물 리그 박스+파렛트 / 미니어처 온보드 뷰)가 도착하면 원커맨드로
평가·파인튜닝이 돌아가게 미리 준비해 둔 config·스크립트. **2026-07-23 기준 실제 학습은
아직 실행하지 않았다** — 데이터가 없어 config 작성과 기존 데이터로의 스모크/검증만 마쳤다.

## 핵심 원칙: measure-first

리그/미니어처 데이터가 오면 **먼저 현재 모델을 그 위에서 평가**해서 KPI(mAP@0.5)를 넘는지
본다. 넘으면 파인튜닝 없이 그대로 배포. 미달일 때만 파인튜닝한다 — 학습을 기본값으로 삼지 않는다.

## 현재 체크포인트 (`ai/work_dirs/REPORT.md` 기준)

| 용도 | 체크포인트 | config | box_coco_val mAP@0.5 |
|---|---|---|---|
| **주력 -m@800** (실물 리그 KPI 판정 무대) | `work_dirs/rtmdet_m_800_scd/best_coco_bbox_mAP_epoch_99.pth` | `configs/rtmdet_m_800_scd_forklift.py` | **0.753** |
| fallback -s@640 (미니어처 온보드, 포크정렬 비전용) | `work_dirs/rtmdet_s_loco_cardboard/best_coco_bbox_mAP_epoch_99.pth` | `configs/rtmdet_s_forklift.py` | 0.680 |

이 값들은 공개 데이터(LOCO+Cardboard+SCD) 기준이다. 실물/미니어처 도메인에서는 다시 측정해야
의미가 있다 — 그래서 ① eval_domain이 항상 먼저다.

## 데이터 도착 시 절차

### ① measure-first: 현재 모델 채점

```bash
cd ai
# 실물 리그 평가셋 → 주력 -m@800으로 채점 (KPI: mAP@0.5 ≥ 0.92)
CUDA_VISIBLE_DEVICES=1 python tools/eval_domain.py \
  --config configs/rtmdet_m_800_scd_forklift.py \
  --checkpoint work_dirs/rtmdet_m_800_scd/best_coco_bbox_mAP_epoch_99.pth \
  --ann /path/to/rig_val.json --img-root /path/to/rig_images \
  --out work_dirs/eval_domain_rig.json --kpi 0.92

# 미니어처 온보드 평가셋 → fallback -s@640으로 채점
CUDA_VISIBLE_DEVICES=1 python tools/eval_domain.py \
  --config configs/rtmdet_s_forklift.py \
  --checkpoint work_dirs/rtmdet_s_loco_cardboard/best_coco_bbox_mAP_epoch_99.pth \
  --ann /path/to/mini_val.json --img-root /path/to/mini_images \
  --out work_dirs/eval_domain_mini.json --kpi 0.92
```

콘솔에 `KPI(mAP@0.5 ≥ 0.92): PASS/FAIL`이 바로 찍힌다. PASS면 여기서 끝 — 파인튜닝 불필요.
평가셋 COCO category_id는 프로젝트 스키마(box=1, pallet=2)를 따라야 한다.

### ② 미달 시: 파인튜닝

`configs/finetune/rig_m800.py`(실물 리그용) / `configs/finetune/mini_s640.py`(미니어처
온보드용) 상단의 `data_root`/`ann_train`/`ann_val`(TODO 표시)을 실제 경로로 채운 뒤:

```bash
cd ai
# 스모크(포그라운드, 필수 — 전체 데이터로 바로 돌리기 전에 항상 먼저)
CUDA_VISIBLE_DEVICES=1 mim train mmdet configs/finetune/rig_m800.py --gpus 1 \
  --work-dir work_dirs/smoke_finetune_rig_m800 \
  --cfg-options train_cfg.max_epochs=1 \
                train_dataloader.dataset.indices=200 \
                val_dataloader.dataset.indices=100

# 본 학습(백그라운드, nohup detached — 상주 세션은 tmux `cc`)
CUDA_VISIBLE_DEVICES=1 nohup mim train mmdet configs/finetune/rig_m800.py --gpus 1 \
  --work-dir work_dirs/finetune_rig_m800 \
  > work_dirs/finetune_rig_m800/train.log 2>&1 &
```

미니어처 온보드는 `configs/finetune/mini_s640.py` / `work_dirs/finetune_mini_s640`로 동일하게.

- lr은 **1e-4로 고정**(5e-4는 val 붕괴 확인됨) — cfg-options로 올리지 말 것.
- `save_best='coco/bbox_mAP_50'` — KPI가 mAP@0.5라 이 지표 기준으로 best가 저장된다.
- `max_keep_ckpts=5` — 학습이 악화되면 이전 체크포인트로 롤백할 수 있게 여러 개 유지한다.
- 학습 완료 후 다시 ① `eval_domain.py`로 KPI 통과 여부 재확인.

### ③ 프리라벨 (라벨 없는 원본이 있을 때)

파인튜닝하려는데 라벨이 없다면, 현재 모델로 먼저 감지를 돌려 사람이 수정만 하게 만든다:

```bash
cd ai
CUDA_VISIBLE_DEVICES=1 python tools/prelabel.py \
  --img-dir /path/to/raw_images \
  --config configs/rtmdet_m_800_scd_forklift.py \
  --checkpoint work_dirs/rtmdet_m_800_scd/best_coco_bbox_mAP_epoch_99.pth \
  --threshold 0.3 \
  --out data/processed/prelabel_rig.json
```

CVAT → Tasks → Create → Upload annotations → **COCO 1.0**으로 임포트, 사람이 검수·수정 후
export하면 그게 `ann_train`/`ann_val`이 된다.

## 환경 체크리스트 (매번 확인)

- GPU: `CUDA_VISIBLE_DEVICES=1`만. 다른 GPU·팀원 프로세스 절대 건드리지 않는다.
- conda env `rtmdet` (torch 2.1.2+cu121 / mmcv 2.1 / mmdet 3.3 / **numpy 1.26.4**).
  numpy를 2.x로 올리는 설치는 하지 않는다(mim/opencv가 끌어올리면 `.numpy()` 호출부에서 죽는다).
- 전처리는 config에 이미 박혀 있다(keep_ratio 리사이즈+114 패딩 → 정규화
  mean=[103.53,116.28,123.675]/std=[57.375,57.12,58.395], BGR 유지) — 도구를 거치면
  신경 쓸 필요 없음. 직접 onnxruntime 등으로 별도 추론할 때만
  `work_dirs/REPORT.md`의 "ONNX 저성능 진단" 절 레시피를 그대로 따를 것.
- 장시간 명령(본 학습)은 nohup detached. 상주는 tmux `cc`.

## 산출물 목록

- `tools/eval_domain.py` (+ `tools/README.md`) — measure-first 평가 하네스
- `tools/prelabel.py` — 프리라벨(CVAT용 COCO json 생성)
- `configs/finetune/rig_m800.py` — 실물 리그용 파인튜닝(작성만, 미실행)
- `configs/finetune/mini_s640.py` — 미니어처 온보드용 파인튜닝(작성만, 미실행)

## 검증 기록 (2026-07-23)

- `eval_domain.py`가 기존 `box_coco_val.json` + `rtmdet_m_800_scd` 체크포인트로 mAP@0.5
  **0.753**을 재현(REPORT.md 기록값과 일치) — 하네스 정상.
- `rig_m800.py`/`mini_s640.py` 둘 다 기존 데이터로 스모크(1epoch·200/100장) 통과 — 체크포인트
  로드(shape mismatch 없음, 이미 2클래스라 COCO 프리트레인 로드 때와 달리 완전히 일치)·
  전처리·loss 계산·`save_best='coco/bbox_mAP_50'` 저장까지 정상 확인.
- `prelabel.py`가 LOCO 샘플 3장에서 정상 동작하는 COCO json(box=0건/pallet=198건, 표준
  `info/licenses/categories/images/annotations` 구조) 생성 확인.
- 진짜 장시간 파인튜닝은 실행하지 않음 — 데이터 도착 후 진행.
