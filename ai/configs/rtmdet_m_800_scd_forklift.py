# RTMDet-m 파인튜닝 — 지게차 박스·파렛트 2클래스, 해상도 800, SCD 추가 (S15P11A304-67 실험 5)
#
# 배경: 실험4(해상도 800, LOCO+Cardboard)를 epoch26(best mAP_50 0.662)에서 중단하고, SCD
# (coco_style_oneclass.zip, category 'Carton' 단일 클래스 → box로 매핑, train 6735장/
# val 1000장)를 추가한 combined 단일 run으로 전환. pallet은 LOCO에서만 유지(2클래스 계보
# 보존, SCD에는 pallet 라벨 없음).
#
# 데이터: box_coco_train_scd.json = box_coco_train.json(LOCO+Cardboard, 10501장) +
# SCD train(6735장, box만) = 17236장. val은 기존 box_coco_val.json 그대로 유지(공정 비교,
# "채점표 고정"). SCD val(1000장)은 별도로 학습 완료 후 scd_val_coco.json으로 참고 리포트.
#
# COCO 프리트레인 백본부터 시작(load_from 유지), lr=1e-4(사용자 지정, 배치비례 아님 — 데이터가
# 대폭 늘어난 만큼 명시적으로 높게 설정), batch20@800(실험4에서 메모리 25.57GB로 검증된 값 재사용).
#
# 주의: SCD는 CC BY-NC-SA(비영리) 라이선스 — 데모/연구용으로만 사용.

_base_ = ['mmdet::rtmdet/rtmdet_m_8xb32-300e_coco.py']

# --- 데이터 경로 ---
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

train_batch_size = 20
base_lr = 1e-4  # 사용자 지정 고정값(배치비례 아님)
num_workers = 8

load_from = (
    'https://download.openmmlab.com/mmdetection/v3.0/rtmdet/'
    'rtmdet_m_8xb32-300e_coco/rtmdet_m_8xb32-300e_coco_20220719_112220-229f527c.pth'
)

# --- 파이프라인: 실험4와 동일(해상도 800) ---
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
        ann_file='box_coco_train_scd.json',
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

val_evaluator = dict(ann_file=data_root + 'box_coco_val.json', classwise=True)
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
