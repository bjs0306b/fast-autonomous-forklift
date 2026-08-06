# RTMDet-m 파인튜닝 — 지게차 박스·파렛트 2클래스, 고해상도 (S15P11A304-67 실험 3)
#
# 실험 2(rtmdet_m_loco_cardboard, RTMDet-m)가 mAP_50 0.710(목표 0.72까지 0.01)로
# 근접했으나 미달 → 우선순위 (b) 입력 해상도 확대(640→800)로 재시도. 실험2가 지금까지
# 최고 성능이므로 모델은 RTMDet-m을 유지하고 해상도만 변수로 격리한다.
# 해상도 상승분 메모리를 흡수하기 위해 배치를 16→8로 낮추고 lr도 배치 비례 축소.

_base_ = ['mmdet::rtmdet/rtmdet_m_8xb32-300e_coco.py']

# --- 데이터 경로 (실험 1·2와 동일) ---
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

img_size = (800, 800)

base_lr = 2.5e-5
train_batch_size = 8
num_workers = 8

load_from = (
    'https://download.openmmlab.com/mmdetection/v3.0/rtmdet/'
    'rtmdet_m_8xb32-300e_coco/rtmdet_m_8xb32-300e_coco_20220719_112220-229f527c.pth'
)

# --- 파이프라인: base(640) 대비 스케일만 800으로 확대, 나머지 구조는 동일 ---
train_pipeline = [
    dict(type='LoadImageFromFile', backend_args={{_base_.backend_args}}),
    dict(type='LoadAnnotations', with_bbox=True),
    dict(type='CachedMosaic', img_scale=img_size, pad_val=114.0),
    dict(
        type='RandomResize',
        scale=(img_size[0] * 2, img_size[1] * 2),
        ratio_range=(0.1, 2.0),
        keep_ratio=True),
    dict(type='RandomCrop', crop_size=img_size),
    dict(type='YOLOXHSVRandomAug'),
    dict(type='RandomFlip', prob=0.5),
    dict(type='Pad', size=img_size, pad_val=dict(img=(114, 114, 114))),
    dict(
        type='CachedMixUp',
        img_scale=img_size,
        ratio_range=(1.0, 1.0),
        max_cached_images=20,
        pad_val=(114, 114, 114)),
    dict(type='PackDetInputs'),
]

train_pipeline_stage2 = [
    dict(type='LoadImageFromFile', backend_args={{_base_.backend_args}}),
    dict(type='LoadAnnotations', with_bbox=True),
    dict(
        type='RandomResize',
        scale=img_size,
        ratio_range=(0.1, 2.0),
        keep_ratio=True),
    dict(type='RandomCrop', crop_size=img_size),
    dict(type='YOLOXHSVRandomAug'),
    dict(type='RandomFlip', prob=0.5),
    dict(type='Pad', size=img_size, pad_val=dict(img=(114, 114, 114))),
    dict(type='PackDetInputs'),
]

test_pipeline = [
    dict(type='LoadImageFromFile', backend_args={{_base_.backend_args}}),
    dict(type='Resize', scale=img_size, keep_ratio=True),
    dict(type='Pad', size=img_size, pad_val=dict(img=(114, 114, 114))),
    dict(type='LoadAnnotations', with_bbox=True),
    dict(
        type='PackDetInputs',
        meta_keys=('img_id', 'img_path', 'ori_shape', 'img_shape',
                   'scale_factor')),
]

# --- 데이터로더 ---
train_dataloader = dict(
    batch_size=train_batch_size,
    num_workers=num_workers,
    dataset=dict(
        data_root=data_root,
        metainfo=metainfo,
        ann_file='box_coco_train.json',
        data_prefix=dict(img=img_prefix),
        pipeline=train_pipeline,
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
        pipeline=test_pipeline,
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
        switch_pipeline=train_pipeline_stage2,
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
