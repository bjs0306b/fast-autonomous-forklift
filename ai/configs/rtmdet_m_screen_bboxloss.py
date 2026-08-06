# 스크리닝 후보 3: loss_bbox 가중치 상향 (S15P11A304-67)
#
# 진단: 파렛트 mAP_75가 박스의 절반 이하(0.21 vs 0.54)로 로컬라이제이션이 유독 헐겁다.
# augmentation과 독립적인 변수로, GIoU loss weight를 2.0→4.0으로 올려 박스 회귀에 더
# 강한 그래디언트를 준다. 그 외 스크리닝 베이스와 완전히 동일 — 변수 하나만 격리.

_base_ = ['rtmdet_m_screen_base.py']

model = dict(
    bbox_head=dict(
        num_classes=2,
        loss_bbox=dict(type='GIoULoss', loss_weight=4.0),
    ),
)
