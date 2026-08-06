# 스크리닝 후보 2: bbox 기반 copy-paste 증강 (S15P11A304-67)
#
# 진단: 파렛트 배경 오탐(10,084건)·미탐(5,355건)이 압도적으로 많음. 세그멘테이션 마스크가
# 없어 표준 mmdet CopyPaste(마스크 필요)는 쓸 수 없으므로, bbox 크롭을 다른 이미지에
# 합성하는 커스텀 변환(src/rtmdet_ext/copy_paste.py)으로 파렛트 등장 맥락 다양성을 늘린다.
# target_category_ids=[1] → metainfo classes=('box','pallet')에서 pallet의 0-based label.
# 그 외 스크리닝 베이스와 완전히 동일 — 변수 하나만 격리.

_base_ = ['rtmdet_m_screen_base.py']

custom_imports = dict(imports=['rtmdet_ext'], allow_failed_imports=False)

train_pipeline = [
    dict(type='LoadImageFromFile', backend_args={{_base_.backend_args}}),
    dict(type='LoadAnnotations', with_bbox=True),
    dict(type='CachedMosaic', img_scale=(640, 640), pad_val=114.0),
    dict(
        type='RandomResize',
        scale=(1280, 1280),
        ratio_range=(0.1, 2.0),
        keep_ratio=True),
    dict(type='RandomCrop', crop_size=(640, 640)),
    dict(type='YOLOXHSVRandomAug'),
    dict(type='RandomFlip', prob=0.5),
    dict(type='Pad', size=(640, 640), pad_val=dict(img=(114, 114, 114))),
    dict(
        type='CachedMixUp',
        img_scale=(640, 640),
        ratio_range=(1.0, 1.0),
        max_cached_images=20,
        pad_val=(114, 114, 114)),
    dict(
        type='CachedBBoxCopyPaste',
        target_category_ids=[1],
        max_paste=2,
        scale_range=(0.7, 1.3),
        max_cached_images=30,
        prob=0.5),
    dict(type='PackDetInputs'),
]

train_dataloader = dict(dataset=dict(pipeline=train_pipeline))
