# RTMDet-m 스크리닝 베이스(대조군) — 서브셋 저비용 후보 비교용 (S15P11A304-67)
#
# 실험2(RTMDet-m, 640해상도, 풀데이터 100ep)가 mAP_50 0.710으로 최고 성능.
# classwise 진단 결과 pallet 클래스가 배경 오탐·미탐·로컬라이제이션 모두 약함(REPORT 참고).
# 이 config는 그 진단에서 나온 개선 후보들을 "순위 비교"용으로 저비용 스크리닝하기 위한
# 대조군(변경 없음)이다 — train만 27% 서브셋, val은 전체 2,398장 그대로 유지(채점표 고정).
#
# 메모리 정책 갱신(2026-07-22, 합산 40GB 기준)에 따라 batch32로 복귀(실험2의 batch16 제약 해제),
# lr은 실험1·2에서 검증된 batch32 기준 1e-4 계열 유지.

_base_ = ['mmdet::rtmdet/rtmdet_m_8xb32-300e_coco.py']

data_root = 'data/processed/'
img_prefix = 'staged_images/'

metainfo = dict(
    classes=('box', 'pallet'),
    palette=[(220, 20, 60), (0, 128, 255)],
)

model = dict(bbox_head=dict(num_classes=2))

# --- 스크리닝 스케줄: 40ep, 마지막 4ep이 stage2(약증강) ---
max_epochs = 40
stage2_num_epochs = 4
val_interval = 4

base_lr = 1e-4
train_batch_size = 32
num_workers = 8

load_from = (
    'https://download.openmmlab.com/mmdetection/v3.0/rtmdet/'
    'rtmdet_m_8xb32-300e_coco/rtmdet_m_8xb32-300e_coco_20220719_112220-229f527c.pth'
)

train_dataloader = dict(
    batch_size=train_batch_size,
    num_workers=num_workers,
    dataset=dict(
        data_root=data_root,
        metainfo=metainfo,
        ann_file='box_coco_train_subset.json',  # 27% 서브셋 — 스크리닝 전용
        data_prefix=dict(img=img_prefix),
    ),
)
val_dataloader = dict(
    batch_size=train_batch_size,
    num_workers=num_workers,
    dataset=dict(
        data_root=data_root,
        metainfo=metainfo,
        ann_file='box_coco_val.json',  # 전체 2,398장 — 절대 축소하지 않음
        data_prefix=dict(img=img_prefix),
    ),
)
test_dataloader = val_dataloader

val_evaluator = dict(ann_file=data_root + 'box_coco_val.json', classwise=True)
test_evaluator = val_evaluator

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
        max_keep_ckpts=2,
        save_best='coco/bbox_mAP',
    ),
    logger=dict(interval=50),
)
