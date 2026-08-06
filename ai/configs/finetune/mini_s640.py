# 미니어처 온보드 도메인 파인튜닝 — RTMDet-s@640, 박스·파렛트 2클래스 (S15P11A304, 파이프라인 스테이징)
#
# 온보드 -s는 포크정렬 비전용(미니어처 도메인) — -m@800(리그)보다 파인튜닝 가능성이 높다고
# 판단된 쪽. measure-first 원칙은 동일: 평가셋이 도착하면 먼저 tools/eval_domain.py로 현재
# fallback -s 성능(work_dirs/rtmdet_s_loco_cardboard/best_coco_bbox_mAP_epoch_99.pth,
# box_coco_val mAP@0.5=0.680)을 채점하고, 미달일 때만 이 config로 파인튜닝한다.
#
# load_from은 COCO 프리트레인이 아니라 위 체크포인트다 — 박스·파렛트 도메인을 이미 학습한
# 모델을 미니어처 온보드 도메인으로 마저 적응시키는 것이 목적.
#
# ⚠️ 지금은 실행 금지 — 데이터가 아직 없다. data_root/ann_file은 TODO 플레이스홀더이며,
# 스모크 테스트는 기존 box_coco_{train,val}.json을 --cfg-options로 임시 지정해 돌린다
# (PIPELINE_STAGING.md 참고).

_base_ = ['mmdet::rtmdet/rtmdet_s_8xb32-300e_coco.py']

# --- 데이터 경로: 데이터 도착 후 실제 값으로 교체 ---
data_root = 'data/processed/mini/'  # TODO: 데이터 도착 후 경로로 교체
img_prefix = 'images/'              # TODO: 데이터 도착 후 경로로 교체 (data_root 기준)
ann_train = 'TODO_mini_train.json'  # TODO: 데이터 도착 후 파일명으로 교체
ann_val = 'TODO_mini_val.json'      # TODO: 데이터 도착 후 파일명으로 교체

metainfo = dict(
    classes=('box', 'pallet'),
    palette=[(220, 20, 60), (0, 128, 255)],
)

model = dict(bbox_head=dict(num_classes=2))

# --- 학습 스케줄: 짧은 파인튜닝(25ep), 매 epoch 검증해 악화를 바로 잡아낸다 ---
max_epochs = 25
val_interval = 1

img_size = (640, 640)  # -s는 640 고정(온보드 배포 해상도)

base_lr = 1e-4  # 고정값(사용자 지정) — 5e-4는 val 붕괴가 확인됨. 배치비례로 올리지 말 것.
train_batch_size = 32  # 640res 기준 실험1과 동일 배치(메모리 여유 충분). 데이터 적으면 낮춰도 무방.
num_workers = 8

# COCO 프리트레인이 아니라 현재 fallback -s 체크포인트에서 이어서 학습
load_from = 'work_dirs/rtmdet_s_loco_cardboard/best_coco_bbox_mAP_epoch_99.pth'

# --- 파이프라인 ---
# 정규화(mean/std/BGR)는 base data_preprocessor 그대로(mean=[103.53,116.28,123.675],
# std=[57.375,57.12,58.395], bgr_to_rgb=False) — 변경 없음.
# CachedMosaic/CachedMixUp은 생략한다: 25ep·소량 실데이터 파인튜닝에서는 캐시 기반 강증강이
# 오히려 불안정할 위험이 커서, keep_ratio 리사이즈+패딩 위주의 가벼운 파이프라인만 쓴다.
train_pipeline = [
    dict(type='LoadImageFromFile', backend_args={{_base_.backend_args}}),
    dict(type='LoadAnnotations', with_bbox=True),
    dict(
        type='RandomResize',
        scale=img_size,
        ratio_range=(0.5, 1.5),
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
        ann_file=ann_train,
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
        ann_file=ann_val,
        data_prefix=dict(img=img_prefix),
        pipeline=test_pipeline,
    ),
)
test_dataloader = val_dataloader

val_evaluator = dict(ann_file=data_root + ann_val, classwise=True)
test_evaluator = val_evaluator

# --- 스케줄/옵티마이저/훅 ---
train_cfg = dict(max_epochs=max_epochs, val_interval=val_interval)

optim_wrapper = dict(optimizer=dict(lr=base_lr))

param_scheduler = [
    dict(type='LinearLR', start_factor=1.0e-5, by_epoch=False, begin=0, end=200),
    dict(
        type='CosineAnnealingLR',
        eta_min=base_lr * 0.05,
        begin=max_epochs // 2,
        end=max_epochs,
        T_max=max_epochs - max_epochs // 2,
        by_epoch=True,
        convert_to_iter_based=True,
    ),
]

default_hooks = dict(
    checkpoint=dict(
        interval=val_interval,
        max_keep_ckpts=5,               # 악화 시 이전 체크포인트로 롤백할 수 있게 넉넉히 유지
        save_best='coco/bbox_mAP_50',   # KPI가 mAP@0.5라 이 지표 기준으로 best 저장
    ),
    logger=dict(interval=20),
)
