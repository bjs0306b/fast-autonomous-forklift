"""실험5 vs 실험6 체크포인트를 rig 파렛트 프레임에서 직접 대조 추론한다.

핵심 판정: 실험6 모델이 검은 플라스틱 파렛트를 score>=0.5로 잡는가.
실험5는 스테이션에서 score 0.02까지 낮춰도 파렛트 후보 0건이었다.

CUDA_VISIBLE_DEVICES=1 python scripts/infer_rig_compare.py
"""
import json
import sys
from collections import defaultdict

import numpy as np
from mmdet.apis import init_detector, inference_detector

RIG_JSON = 'data/processed/rig_labeled.json'
RIG_DIR = 'data/raw/rig/20260724'
PALLET_CLS = 1  # 모델 라벨: box=0, pallet=1 (metainfo classes=('box','pallet'))
SCORE_THR = 0.5

MODELS = {
    'exp5(old)': (
        'configs/rtmdet_m_800_scd_forklift.py',
        'work_dirs/rtmdet_m_800_scd/best_coco_bbox_mAP_epoch_99.pth',
    ),
    'exp6(rig)': (
        'configs/rtmdet_m_800_rig_forklift.py',
        'work_dirs/exp6_rig/best_coco_bbox_mAP_50_epoch_49.pth',
    ),
    'exp7(aug)': (
        'configs/rtmdet_m_800_astraws_forklift.py',
        'work_dirs/exp7_aug/best_coco_bbox_mAP_50_epoch_49.pth',
    ),
}


def pick_pallet_images(n=12):
    d = json.load(open(RIG_JSON))
    imgs = {i['id']: i['file_name'] for i in d['images']}
    pallet_imgs = sorted({a['image_id'] for a in d['annotations']
                          if a['category_id'] == 2})
    # 세션 전체에 고르게 퍼지도록 등간격 샘플
    idx = np.linspace(0, len(pallet_imgs) - 1, n).round().astype(int)
    return [imgs[pallet_imgs[i]] for i in idx]


def max_pallet_score(result):
    inst = result.pred_instances
    labels = inst.labels.cpu().numpy()
    scores = inst.scores.cpu().numpy()
    pal = scores[labels == PALLET_CLS]
    return (float(pal.max()) if len(pal) else 0.0,
            int((pal >= SCORE_THR).sum()))


def main():
    device = 'cuda:0'  # CUDA_VISIBLE_DEVICES=1 로 물리 GPU1에 매핑됨
    files = pick_pallet_images()
    print(f'파렛트 포함 rig 프레임 {len(files)}장 샘플 추론 (score_thr={SCORE_THR})\n')

    results = defaultdict(dict)
    for name, (cfg, ckpt) in MODELS.items():
        print(f'== {name}: {ckpt} 로드 ==', file=sys.stderr)
        model = init_detector(cfg, ckpt, device=device)
        for f in files:
            r = inference_detector(model, f'{RIG_DIR}/{f}')
            results[f][name] = max_pallet_score(r)
        del model
        import torch
        torch.cuda.empty_cache()

    names = list(MODELS.keys())
    print(f"{'frame':<38}" + ''.join(f'{n+" maxP/n>=.5":>22}' for n in names))
    print('-' * (38 + 22 * len(names)))
    agg = {n: {'hit': 0, 'scores': []} for n in names}
    for f in files:
        row = f'{f:<38}'
        for n in names:
            ms, cnt = results[f][n]
            row += f'{ms:>10.3f} / {cnt:<9}'.rjust(22)
            agg[n]['scores'].append(ms)
            if ms >= SCORE_THR:
                agg[n]['hit'] += 1
        print(row)
    print('-' * (38 + 22 * len(names)))
    print(f'\n== 요약 (파렛트 검출, {len(files)}장 중) ==')
    for n in names:
        s = agg[n]['scores']
        print(f'  {n:<12} score>=0.5 프레임: {agg[n]["hit"]}/{len(files)}'
              f'  |  max score 평균 {np.mean(s):.3f}  최소 {min(s):.3f}  최대 {max(s):.3f}')


if __name__ == '__main__':
    main()
