# RTMDet-m 파인튜닝 — 리그 실물 도메인 적응, 박스·파렛트 2클래스, 해상도 800 (S15P11A304-156 실험 6)
#
# 배경: 실험5(mAP_50 0.753) 모델이 스테이션 실물 검증에서 검은 플라스틱 파렛트를 전혀 못 잡음
# (score 0.02까지 낮춰도 파렛트 후보 0건, 같은 프레임 박스는 0.91~0.92). 목재 파렛트 위주인
# LOCO 도메인과 회색 카펫 위 검은 플라스틱 파렛트가 어긋난 것이 원인. 자체 촬영 rig 289장을
# 기존 combined 학습셋에 섞어 도메인 적응. 2단 순차 파인튜닝은 실험4에서 pallet catastrophic
# forgetting으로 폐기됐으므로 실험5 방식(단일 combined run)을 따른다.
#
# 데이터: box_coco_train_scd_rig.json = box_coco_train_scd.json(LOCO+Cardboard+SCD, 17236장) +
# rig(289장, box 871/pallet 273) = 17525장. rig는 한 세션 한 배치 촬영이라 val을 떼면 누수 →
# 전량 train. val은 기존 box_coco_val.json 그대로 유지(공정 비교, "채점표 고정"). rig 네거티브
# (파렛트 없는 프레임)를 오탐 억제용으로 살리기 위해 filter_empty_gt=False가 필수.
#
# 실험5와 달리 COCO 프리트레인이 아니라 실험5 best 체크포인트에서 이어서 학습(load_from 교체).
# 이미 박스·파렛트를 학습한 모델을 리그 도메인으로 마저 적응시키는 것이 목적. lr=1e-4(사용자
# 지정 고정, 5e-4는 val 붕괴 전례). batch16@800(보수적 — GPU1에 팀원 Isaac Sim 상주).
#
# 주의: 학습셋에 포함된 SCD는 CC BY-NC-SA(비영리) 라이선스 — 데모/연구용으로만 사용.

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
max_epochs = 50           # 짧은 파인튜닝(30~50). val 곡선 보고 조기 중단 판단.
stage2_num_epochs = 10    # 마지막 10ep는 약증강 전환(ep40에서 PipelineSwitchHook)
val_interval = 1          # 소량 실데이터 적응 — 매 epoch 검증해 회귀/최적점을 놓치지 않는다

img_size = (800, 800)

train_batch_size = 16     # 보수적(GPU1 팀원 Isaac Sim 상주). lr은 배치비례로 올리지 않는다.
base_lr = 1e-4  # 사용자 지정 고정값(배치비례 아님, 5e-4는 val 붕괴 전례)
num_workers = 8

# 실험5 best 체크포인트에서 이어서 학습(COCO 프리트레인 아님) — 리그 도메인 적응
load_from = 'work_dirs/exp7_aug/epoch_50.pth'

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
        ann_file='box_coco_train_occ.json',
        data_prefix=dict(img=img_prefix),
        # rig 네거티브(파렛트 없는 프레임)를 오탐 억제용으로 학습에 남긴다.
        # base(coco_detection)의 기본값 filter_empty_gt=True를 반드시 덮어써야 함.
        filter_cfg=dict(filter_empty_gt=False, min_size=32),
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
        max_keep_ckpts=5,               # 악화 시 이전 체크포인트로 롤백할 수 있게 넉넉히
        save_best='coco/bbox_mAP_50',   # 스테이션 KPI가 mAP@0.5 — 이 지표로 best 선정
    ),
    logger=dict(interval=50),
)
