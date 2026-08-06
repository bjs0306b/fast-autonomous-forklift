# 스크리닝 후보 1: mosaic 완화 (S15P11A304-67)
#
# 진단: 파렛트(큰 객체)가 CachedMosaic으로 조각나 컨텍스트를 잃는 것이 배경 오탐·미탐의
# 원인일 수 있다는 가설. CachedMosaic 적용 확률을 1.0→0.5로 낮추고, RandomResize의
# 스케일 지터 범위를 (0.1,2.0)→(0.3,1.7)로 좁혀 극단적 축소/확대를 줄인다.
# 그 외 스크리닝 베이스(rtmdet_m_screen_base.py)와 완전히 동일 — 변수 하나만 격리.

_base_ = ['rtmdet_m_screen_base.py']

train_pipeline = [
    dict(type='LoadImageFromFile', backend_args={{_base_.backend_args}}),
    dict(type='LoadAnnotations', with_bbox=True),
    dict(type='CachedMosaic', img_scale=(640, 640), pad_val=114.0, prob=0.5),
    dict(
        type='RandomResize',
        scale=(1280, 1280),
        ratio_range=(0.3, 1.7),
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
    dict(type='PackDetInputs'),
]

train_dataloader = dict(dataset=dict(pipeline=train_pipeline))
