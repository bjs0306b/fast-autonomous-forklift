# RTMDet-s 파인튜닝 — 온보드 포크 정렬, box·pallet·hole 3클래스, 해상도 640 (S15P11A304-145)
#
# 목적: 지게차 온보드 카메라가 미니어처 파렛트의 **포크 진입 구멍(hole)** 을 찾아 정렬하는 것.
# 스테이션 -m(측정)과 완전히 별개의 모델이며, 도메인도 KPI도 다르다.
#
# 데이터: 미니어처 온보드 촬영 358장(train_20260729). 회색 카펫 바닥, 흰 3D 프린트 파렛트,
# 갈색 크라프트지로 감싼 박스, 카메라는 바닥 높이(내려다보는 각 ≈ 0°).
# 구간 매핑·커버리지는 docs/ai/onboard-dataset-batches.md, 라벨 규약은
# docs/ai/onboard-hole-label-guide.md 참고.
#
# ─────────────────────────────────────────────────────────────────────────────
# 결정 1. 공개 데이터(LOCO 등)를 섞지 않는다 — exp8(스테이션)과 반대 결정이다.
#
# CLAUDE.md의 "기존 데이터를 반드시 섞는다(망각 방지)"는 **스테이션 -m 맥락**이었다.
# 거기서는 공개 도메인 성능이 참고 지표로 살아 있었다. 온보드 -s는 다르다:
#
#   ① 공개 데이터에는 hole 라벨이 없다. 섞으면 "파렛트가 보이는데 구멍 라벨은 없는"
#      프레임이 대량 들어가고, 이는 모델에게 **"여기엔 구멍이 없다"고 가르치는 것**이다.
#      라벨 부재가 음성 신호로 학습돼 hole 재현율을 직접 깎는다.
#   ② 온보드 -s의 도메인은 데모 환경 그 자체다. 미니어처 파렛트 하나, 갈색 박스 몇 개가
#      전부이고 공개 도메인 성능은 KPI가 아니다(그건 스테이션 -m 몫).
#
# 즉 여기서 "잊어도 되는 능력"을 지키느라 hole을 망치는 것은 손해다.
#
# 결정 2. warm-start는 exp7 계보 -s 체크포인트에서 한다.
#
# 2클래스 → 3클래스라 분류 헤드(rtm_cls)의 출력 채널이 바뀐다. mmengine이 shape 불일치
# 파라미터를 건너뛰므로 **분류 헤드만 재초기화되고 backbone·neck·회귀 헤드는 전이**된다.
# 초기 몇 epoch는 클래스 점수가 불안정한 것이 정상이며, lr 1e-4가 그 완충 역할을 한다.
#
# 결정 3. val은 구간 단위로 뗀다 — 랜덤 분할 금지.
#
# 버스트 촬영이라 한 배치 안의 프레임은 서로 거의 같다. 랜덤으로 나누면 train과 val에
# 사실상 동일한 사진이 갈려 들어가 **val이 부풀려진다**(리그에서 이미 겪은 오염 패턴).
# 구간 4(49장)·구간 7(18장)을 통째로 val로 뺀다 — 정면 + 배경변형 조합이라 대표성이 있다.
# ⚠️ 이 val은 학습 모니터링용이다. **KPI 판정은 다른 날 촬영할 eval셋**으로 한다.
# ─────────────────────────────────────────────────────────────────────────────

_base_ = ['mmdet::rtmdet/rtmdet_s_8xb32-300e_coco.py']

# --- 데이터 경로 ---
data_root = 'data/processed/'
img_prefix = 'staged_images_onboard/'

metainfo = dict(
    classes=('box', 'pallet', 'hole'),
    palette=[(220, 20, 60), (0, 128, 255), (255, 190, 0)],
)

model = dict(bbox_head=dict(num_classes=3))

# --- 학습 스케줄 ---
# 291장 × batch16 ≈ 18 iter/epoch로 한 epoch이 매우 짧다. rig(50ep, 17k장)보다 epoch을
# 크게 잡아야 같은 업데이트 횟수가 나온다.
max_epochs = 120
stage2_num_epochs = 20    # 마지막 20ep 약증강(ep100에서 PipelineSwitchHook)
val_interval = 2

# ⚠️ 온보드 TensorRT 엔진이 **정적 640**으로 빌드돼 있다
# (configs/deploy/detection_tensorrt_static_onboard.py, S15P11A304-68).
# 여기를 바꾸면 엔진을 다시 빌드해야 한다.
img_size = (640, 640)

train_batch_size = 16     # GPU1에 팀원 Isaac Sim 상주 → 보수적. lr은 배치비례로 올리지 않는다.
base_lr = 1e-4            # 고정값. 5e-4는 val 붕괴 전례(실험4).
num_workers = 8

# exp7 계보 -s 체크포인트에서 이어서 학습.
# ⚠️ 서버의 실제 파일명을 확인하고 맞출 것 (S15P11A304-68 코멘트 기준 경로).
load_from = 'work_dirs/rtmdet_s_forklift/best_epoch_5.pth'

# --- 파이프라인 ---
# hole은 640 입력에서 세로 약 27px(원본 54px × 0.5)로 작다. RandomResize 하한을 0.1까지
# 두면 3px 아래로 뭉개져 학습 신호가 사라지므로 0.5로 올린다. 상한은 확대라 문제없다.
train_pipeline = [
    dict(type='LoadImageFromFile', backend_args={{_base_.backend_args}}),
    dict(type='LoadAnnotations', with_bbox=True),
    dict(type='CachedMosaic', img_scale=img_size, pad_val=114.0),
    dict(
        type='RandomResize',
        scale=(img_size[0] * 2, img_size[1] * 2),
        ratio_range=(0.5, 2.0),
        keep_ratio=True),
    dict(type='RandomCrop', crop_size=img_size),
    dict(type='YOLOXHSVRandomAug'),
    # ⚠️ RandomFlip은 좌우만. 상하 뒤집기를 넣으면 안 된다 — 파렛트는 상판이 위이고
    # 구멍은 그 아래라는 상하 관계가 hole 판별의 단서다(라벨 가이드 §5 참고).
    dict(type='RandomFlip', prob=0.5, direction='horizontal'),
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
        ratio_range=(0.5, 2.0),
        keep_ratio=True),
    dict(type='RandomCrop', crop_size=img_size),
    dict(type='YOLOXHSVRandomAug'),
    dict(type='RandomFlip', prob=0.5, direction='horizontal'),
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
        ann_file='onboard_coco_train.json',
        data_prefix=dict(img=img_prefix),
        # 네거티브 42장(파렛트·박스 없는 카펫·의자 다리)을 반드시 학습에 남긴다.
        # 근거: 현역 exp8로 프리라벨을 돌렸을 때 그 42장에서 box를 35개나 잡았다.
        # base(coco_detection)의 기본값 filter_empty_gt=True를 덮어써야 한다.
        filter_cfg=dict(filter_empty_gt=False, min_size=8),
        pipeline=train_pipeline,
    ),
)
val_dataloader = dict(
    batch_size=train_batch_size,
    num_workers=num_workers,
    dataset=dict(
        data_root=data_root,
        metainfo=metainfo,
        ann_file='onboard_coco_val.json',
        data_prefix=dict(img=img_prefix),
        pipeline=test_pipeline,
    ),
)
test_dataloader = val_dataloader

val_evaluator = dict(ann_file=data_root + 'onboard_coco_val.json', classwise=True)
test_evaluator = val_evaluator

# --- 스케줄/옵티마이저/훅 ---
train_cfg = dict(
    max_epochs=max_epochs,
    val_interval=val_interval,
    dynamic_intervals=[(max_epochs - stage2_num_epochs, 1)],
)

optim_wrapper = dict(optimizer=dict(lr=base_lr))

param_scheduler = [
    dict(type='LinearLR', start_factor=1.0e-5, by_epoch=False, begin=0, end=500),
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
    # ⚠️ 오버샘플이 필요해지더라도 RepeatDataset을 쓰지 말 것 — 그러면 이 훅의 stage2
    # 전환이 먹지 않는다(실측). 필요하면 COCO json에서 항목을 복제한다.
    dict(
        type='PipelineSwitchHook',
        switch_epoch=max_epochs - stage2_num_epochs,
        switch_pipeline=train_pipeline_stage2,
    ),
]

default_hooks = dict(
    checkpoint=dict(
        interval=val_interval,
        max_keep_ckpts=5,
        # 포크 정렬의 실패는 "구멍을 못 찾는 것"이므로 재현율이 중요하다.
        # mAP@0.5로 best를 고르되, 승격 판정은 hole 클래스 AP와 실측 재현율로 따로 본다
        # (docs/ai/onboard-finetune-runbook.md 승격 게이트).
        save_best='coco/bbox_mAP_50',
    ),
    logger=dict(interval=20),
)
