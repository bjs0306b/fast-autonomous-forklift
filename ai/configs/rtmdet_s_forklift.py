# RTMDet-s 파인튜닝 — 지게차 박스·파렛트 2클래스 (FR-101-3)
#
# mmdet 3.3.0 기준. COCO 사전학습 rtmdet_s에서 파인튜닝한다. 데이터는 COCO 포맷
# (box=1, pallet=2), 원본 단위로 train/val/test 분할된 상태(FR-101-2).
#
# 이미지 경로 규칙: COCO json의 file_name이 "<소스>/<상대경로>" 형태다
# (예: loco/subset-1/x.jpg, logistics/..., carboard/...). mmdet은 단일
# data_prefix.img 아래에서 file_name을 찾으므로, 그 밑에 소스별 디렉터리가
# 모여 있어야 한다 → scripts/stage_images.sh 가 심볼릭 링크 트리를 만든다.
#
# ⚠️ 이 config는 아직 서버에서 실검증 전이다. 먼저 짧은 스모크 런으로 데이터·경로·
# 파이프라인을 확인한 뒤 풀 학습을 돌린다 (README 참고).

_base_ = ['mmdet::rtmdet/rtmdet_s_8xb32-300e_coco.py']

# --- 데이터 경로 ---
# 데이터 준비·학습 모두 ai/ 디렉터리에서 실행한다(datasets.yaml 경로가 ai/ 기준).
data_root = 'data/processed/'      # box_coco_{train,val,test}.json 위치
img_prefix = 'staged_images/'      # data_root 기준, 소스 디렉터리들이 모인 곳

metainfo = dict(
    classes=('box', 'pallet'),
    palette=[(220, 20, 60), (0, 128, 255)],
)

# --- 모델: 클래스 수만 2로 (나머지는 base rtmdet_s 그대로) ---
model = dict(bbox_head=dict(num_classes=2))

# --- 학습 스케줄 (단일 L40S 파인튜닝) ---
# base는 8GPU×batch32=256, base_lr 0.004. 단일 GPU batch 32 → lr 선형 축소.
max_epochs = 100
stage2_num_epochs = 10             # 마지막 10ep는 약증강(모자이크 off)으로 전환
base_lr = 0.004 * 32 / 256         # = 0.0005
val_interval = 5

train_batch_size = 32
num_workers = 8

# COCO 사전학습 가중치에서 시작 — 분류 헤드는 클래스 수가 달라 자동으로 무시된다.
# URL이 404면 mmdet 모델 주(zoo)에서 rtmdet_s 최신 체크포인트로 교체한다.
load_from = (
    'https://download.openmmlab.com/mmdetection/v3.0/rtmdet/'
    'rtmdet_s_8xb32-300e_coco/rtmdet_s_8xb32-300e_coco_20220905_161602-387a891e.pth'
)

# --- 데이터로더 ---
train_dataloader = dict(
    batch_size=train_batch_size,
    num_workers=num_workers,
    dataset=dict(
        data_root=data_root,
        metainfo=metainfo,
        ann_file='box_coco_train.json',
        data_prefix=dict(img=img_prefix),
    ),
)
val_dataloader = dict(
    batch_size=train_batch_size,
    num_workers=num_workers,
    dataset=dict(
        data_root=data_root,
        metainfo=metainfo,
        ann_file='box_coco_val.json',
        data_prefix=dict(img=img_prefix),
    ),
)
test_dataloader = val_dataloader

val_evaluator = dict(ann_file=data_root + 'box_coco_val.json')
test_evaluator = val_evaluator

# --- 스케줄/옵티마이저/훅 ---
train_cfg = dict(
    max_epochs=max_epochs,
    val_interval=val_interval,
    # 스위치 이후에는 매 epoch 검증(마지막 구간을 촘촘히 본다)
    dynamic_intervals=[(max_epochs - stage2_num_epochs, 1)],
)

optim_wrapper = dict(optimizer=dict(lr=base_lr))

param_scheduler = [
    dict(type='LinearLR', start_factor=1.0e-5, by_epoch=False, begin=0, end=1000),
    dict(
        type='CosineAnnealingLR',
        eta_min=base_lr * 0.05,
        begin=max_epochs // 2,
        end=max_epochs,
        T_max=max_epochs // 2,
        by_epoch=True,
        convert_to_iter_based=True,
    ),
]

# 마지막 stage2_num_epochs 구간에서 base의 약증강 파이프라인으로 스위치.
# {{_base_.x}}는 base config 변수를 그대로 끌어오는 mmengine 문법이다.
custom_hooks = [
    dict(
        type='PipelineSwitchHook',
        switch_epoch=max_epochs - stage2_num_epochs,
        switch_pipeline={{_base_.train_pipeline_stage2}},
    ),
]

default_hooks = dict(
    checkpoint=dict(
        interval=val_interval,
        max_keep_ckpts=3,
        save_best='coco/bbox_mAP',   # 목표 KPI: mAP@0.5:0.95 기준 최고본 보관
    ),
    logger=dict(interval=50),
)
