# RTMDet-m 파인튜닝 — 지게차 박스·파렛트 2클래스 (S15P11A304-67 실험 2)
#
# 실험 1(rtmdet_s_loco_cardboard, LOCO+Cardboard)이 mAP_50 0.680(baseline 0.596 대비 +0.084)로
# 목표(0.72) 미달 → 우선순위 (a) 모델 확대(RTMDet-s → RTMDet-m)로 재시도.
# 데이터·클래스·epoch 등 나머지 조건은 실험 1과 동일하게 유지해 모델 크기만 변수로 격리한다.

_base_ = ['mmdet::rtmdet/rtmdet_m_8xb32-300e_coco.py']

# --- 데이터 경로 (실험 1과 동일) ---
data_root = 'data/processed/'
img_prefix = 'staged_images/'

metainfo = dict(
    classes=('box', 'pallet'),
    palette=[(220, 20, 60), (0, 128, 255)],
)

model = dict(bbox_head=dict(num_classes=2))

# --- 학습 스케줄 ---
max_epochs = 100
stage2_num_epochs = 10
val_interval = 5

# RTMDet-m은 배치32 기준 학습 메모리 27.8GB(mmdet metafile) — Isaac Sim과 GPU1을 공유하며
# 합산 20GB 한도를 지켜야 하므로 배치를 16으로 낮춘다(예상 ~14-15GB). LR은 배치 비율대로
# 실험 1의 1e-4(batch32) 기준을 절반(5e-5)으로 선형 축소.
base_lr = 5e-5
train_batch_size = 16
num_workers = 8

load_from = (
    'https://download.openmmlab.com/mmdetection/v3.0/rtmdet/'
    'rtmdet_m_8xb32-300e_coco/rtmdet_m_8xb32-300e_coco_20220719_112220-229f527c.pth'
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
        save_best='coco/bbox_mAP',
    ),
    logger=dict(interval=50),
)
