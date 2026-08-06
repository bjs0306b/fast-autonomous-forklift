"""실험7 학습셋 병합 (S15P11A304-156).

실험6 combined(box_coco_train_scd.json, LOCO+Cardboard+SCD, 17236장 — rig 제외)에
파렛트 증강 3소스를 추가한다:
  - astraws_pallet (원본, 파렛트 1340)
  - astraws_dark   (저조도 쌍둥이, 파렛트 1340)
  - rig_composites (합성 300, box 303 + pallet 300)

rig 원본 289장은 5배 oversampling한다. 원래 RepeatDataset를 쓰려 했으나, RTMDet의
PipelineSwitchHook가 top-level dataset의 .pipeline만 교체해서 ConcatDataset/RepeatDataset로
감싸면 ep40 stage2 약증강 전환이 서브셋에 먹지 않는다(exp6의 큰 상승이 이 전환에서 나왔으므로
공정 비교가 깨진다). 그래서 rig를 json 레벨에서 5배 복제해 단일 CocoDataset을 유지한다 —
oversampling 효과는 동일하고 exp6과 학습 메커니즘(단일 dataset+PipelineSwitchHook)이 같아진다.
rig 네거티브 8장(0121~0128)도 5배 복제되어 남고, config의 filter_empty_gt=False로 살린다.
(rig_coco_train.json도 별도로 남겨둔다 — 참고/재사용용.)

이 스크립트는 configs/datasets.yaml에 방금 wiring한 세 소스 정의(annotations/prefix/class_map/
group)를 그대로 읽어 레포의 convert_coco로 변환한다 — yaml wiring이 실제로 쓰이도록.

val은 건드리지 않는다(box_coco_val.json 고정). astraws/rig 계열 절대 val 금지.

ai/ 에서: python scripts/make_exp7_merge.py
"""
import json
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from dataset.coco import CocoBuilder          # noqa: E402
from dataset.sources import convert_coco       # noqa: E402

ROOT = Path('.')
YAML = Path('configs/datasets.yaml')

BASE_TRAIN = Path('data/processed/box_coco_train_scd.json')      # 17236, rig 제외
EXP6_TRAIN = Path('data/processed/box_coco_train_scd_rig.json')  # rig 포함(rig subset 추출용)

OUT_BASE = Path('data/processed/box_coco_train_scd_astraws.json')  # 실험7 base(non-rig)
OUT_RIG = Path('data/processed/rig_coco_train.json')               # rig 단독(참고용)
OUT_EXP7 = Path('data/processed/box_coco_train_exp7.json')         # 최종: base + rig×5

RIG_OVERSAMPLE = 5

NEW_SOURCES = [
    'astraws_pallet_train', 'astraws_pallet_valid', 'astraws_pallet_test',
    'astraws_dark_train', 'astraws_dark_valid', 'astraws_dark_test',
    'rig_composites',
]

STAGED = Path('data/processed/staged_images')
# prefix → staged_images 기준 심볼릭 링크 대상(상대경로)
STAGE_LINKS = {
    'astraws_pallet':       '../../raw/astraws_pallet/train',
    'astraws_pallet_valid': '../../raw/astraws_pallet/valid',
    'astraws_pallet_test':  '../../raw/astraws_pallet/test',
    'astraws_dark':         '../astraws_dark/train',
    'astraws_dark_valid':   '../astraws_dark/valid',
    'astraws_dark_test':    '../astraws_dark/test',
    'rig_composites':       '../rig_composites',
}


def stage_symlinks():
    print('== staged_images 심볼릭 링크 ==')
    for prefix, target in STAGE_LINKS.items():
        link = STAGED / prefix
        if link.is_symlink() or link.exists():
            assert link.resolve() == (link.parent / target).resolve(), \
                f'{link} 가 예상 밖 대상: {link.resolve()}'
            print(f'  존재: {prefix} -> {target}')
        else:
            link.symlink_to(target, target_is_directory=True)
            print(f'  생성: {prefix} -> {target}')


def convert_new_sources():
    cfg = yaml.safe_load(YAML.read_text(encoding='utf-8'))
    by_name = {s['name']: s for s in cfg['sources']}
    builder = CocoBuilder(classes=('box', 'pallet'))
    print('\n== 새 소스 변환(convert_coco, datasets.yaml wiring 사용) ==')
    for name in NEW_SOURCES:
        s = by_name[name]
        ann = ROOT / s['annotations']
        assert ann.exists(), f'{name}: 어노테이션 없음 {ann}'
        st = convert_coco(
            builder, ann,
            prefix=s.get('prefix', name),
            class_map=s['class_map'],
            path_key=s.get('path_key', 'file_name'),
            strip_path_prefix=s.get('strip_path_prefix', ''),
            group=s.get('group', name),
        )
        print(f'  {name:<22} img={st.images:<5} ann={st.annotations:<5} '
              f'skip_img={st.skipped_images} skip_ann={st.skipped_annotations} '
              f'per_class={dict(st.per_class)}')
    removed = builder.drop_empty_images()  # astraws/composites는 네거티브 아님 — 빈 이미지 제거
    if removed:
        print(f'  [정리] 어노테이션 없는 이미지 {removed}장 제거(설계상 네거티브 아님)')
    return builder.to_dict()


def append_to_base(new):
    base = json.loads(BASE_TRAIN.read_text())
    img_off = max(i['id'] for i in base['images']) + 1000
    ann_off = max(a['id'] for a in base['annotations']) + 1000

    old2new = {}
    add_imgs, add_anns = [], []
    for k, im in enumerate(new['images']):
        nid = img_off + k
        old2new[im['id']] = nid
        add_imgs.append({'id': nid, 'file_name': im['file_name'],
                         'width': im['width'], 'height': im['height']})
    for k, a in enumerate(new['annotations']):
        add_anns.append({'id': ann_off + k, 'image_id': old2new[a['image_id']],
                         'category_id': a['category_id'], 'bbox': a['bbox'],
                         'area': a.get('area', a['bbox'][2] * a['bbox'][3]),
                         'iscrowd': a.get('iscrowd', 0)})

    # 파일 존재 전수 확인
    missing = [im['file_name'] for im in add_imgs
               if not (STAGED / im['file_name']).exists()]
    assert not missing, f'이미지 누락 {len(missing)}건: {missing[:5]}'

    merged = {
        'images': base['images'] + add_imgs,
        'annotations': base['annotations'] + add_anns,
        'categories': base['categories'],
    }
    OUT_BASE.write_text(json.dumps(merged))
    print(f'\n== base 병합 저장: {OUT_BASE} ==')
    print(f'  {len(base["images"])} + {len(add_imgs)} = {len(merged["images"])}장 / '
          f'{len(merged["annotations"])} ann')
    return len(add_imgs), len(add_anns)


def extract_rig():
    """box_coco_train_scd_rig.json에서 rig 289장만 뽑아 단독 json으로."""
    d = json.loads(EXP6_TRAIN.read_text())
    rig_ids = {i['id'] for i in d['images'] if i['file_name'].startswith('rig/')}
    imgs = [i for i in d['images'] if i['id'] in rig_ids]
    anns = [a for a in d['annotations'] if a['image_id'] in rig_ids]
    out = {'images': imgs, 'annotations': anns, 'categories': d['categories']}
    OUT_RIG.write_text(json.dumps(out))
    empt = rig_ids - {a['image_id'] for a in anns}
    print(f'\n== rig 단독 저장: {OUT_RIG} ==')
    print(f'  {len(imgs)}장 / {len(anns)} ann / empty-GT(네거티브) {len(empt)}장')


def bake_final():
    """box_coco_train_scd_astraws.json(base) + rig×5 → box_coco_train_exp7.json (단일 CocoDataset)."""
    base = json.loads(OUT_BASE.read_text())
    rig = json.loads(OUT_RIG.read_text())
    img_off = max(i['id'] for i in base['images']) + 1000
    ann_off = max(a['id'] for a in base['annotations']) + 1000

    imgs = list(base['images'])
    anns = list(base['annotations'])
    for rep in range(RIG_OVERSAMPLE):
        o2n = {}
        for im in rig['images']:
            nid = img_off; img_off += 1
            o2n[im['id']] = nid
            imgs.append({'id': nid, 'file_name': im['file_name'],
                         'width': im['width'], 'height': im['height']})
        for a in rig['annotations']:
            anns.append({'id': ann_off, 'image_id': o2n[a['image_id']],
                         'category_id': a['category_id'], 'bbox': a['bbox'],
                         'area': a.get('area', a['bbox'][2] * a['bbox'][3]),
                         'iscrowd': a.get('iscrowd', 0)})
            ann_off += 1
    out = {'images': imgs, 'annotations': anns, 'categories': base['categories']}
    OUT_EXP7.write_text(json.dumps(out))
    # 검증: id 유일성
    assert len({i['id'] for i in imgs}) == len(imgs), 'image id 충돌'
    assert len({a['id'] for a in anns}) == len(anns), 'ann id 충돌'
    print(f'\n== 최종 exp7 학습셋 저장: {OUT_EXP7} ==')
    print(f'  base {len(base["images"])} + rig {len(rig["images"])}×{RIG_OVERSAMPLE} '
          f'= {len(imgs)}장 / {len(anns)} ann')


def main():
    stage_symlinks()
    new = convert_new_sources()
    append_to_base(new)
    extract_rig()
    bake_final()
    print('\n완료.')


if __name__ == '__main__':
    main()
