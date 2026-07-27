# RTMDet-m 파인튜닝 — 파렛트 증강 재학습, 박스·파렛트 2클래스, 해상도 800 (S15P11A304-156 실험 7)
#
# 배경: 실험6(rig 289장 혼합)이 이미 성공(rig 파렛트 11/12 검출, box 회귀 없음, onnx 반출 완료).
# 실험7은 파렛트 증강 데이터를 더 넣어 "실험6을 이길 수 있나" 확인하는 별도 실험. exp6을
# 이겨야만 교체하고, 못 이기면 버린다(exp6 유지). exp6 산출물은 절대 건드리지 않는다(별도 work_dir).
#
# 실험6 대비 추가 데이터(전부 train, val 금지):
#   - astraws_pallet(원본, 파렛트 1340) + astraws_dark(저조도 쌍둥이, 파렛트 1340)
#   - rig_composites(합성 300, box 303/pallet 300)
#   - rig 원본 289장은 RepeatDataset(times=5)로 5배 oversampling(exp6은 1배였음)
# base(non-rig) = box_coco_train_scd_astraws.json(19120장) + rig×5(RepeatDataset).
#
# 데이터셋 구조: ConcatDataset[ base(CocoDataset), RepeatDataset(times=5, rig(CocoDataset)) ].
# 두 서브셋 모두 filter_empty_gt=False — rig 네거티브 8장(0121~0128) 보존(오탐 억제용).
# astraws/composites는 네거티브 아님(전부 라벨). val은 box_coco_val.json 고정(채점표 불변).
#
# 설정은 실험6과 동일: 실험5 best warm-start, lr=1e-4 고정, batch16@800, 50ep, save_best=mAP_50.
#
# 주의: 학습셋의 SCD는 CC BY-NC-SA(비영리) — 데모/연구용으로만 사용.

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
load_from = 'work_dirs/rtmdet_m_800_scd/best_coco_bbox_mAP_epoch_99.pth'

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
        # base(LOCO+Cardboard+SCD+astraws+astraws_dark+composites) + rig×5(json에 baked).
        # RepeatDataset 대신 json 복제로 5배 — PipelineSwitchHook가 ConcatDataset에 안 먹어서.
        ann_file='box_coco_train_exp7.json',
        data_prefix=dict(img=img_prefix),
        # rig 네거티브 8장(0121~0128, ×5) 보존 — base 기본값 filter_empty_gt=True를 덮어씀.
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
