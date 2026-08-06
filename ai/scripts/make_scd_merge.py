"""LOCO+Cardboard 학습 json(box_coco_train.json)에 SCD(Carton, box 단일 클래스)를 병합한다.

SCD categories는 {'id': 1, 'name': 'Carton'} 단일 클래스 — 우리 스키마의 box(id=1)로
매핑한다. pallet은 SCD에 없으므로 LOCO 쪽 pallet 라벨만 유지된다(2클래스 계보 보존).
val은 건드리지 않는다(box_coco_val.json 그대로 = 기존 고정 채점표).

SCD val(instances_val2017.json)은 별도로 scd_val_coco.json으로 리맵해 저장한다
(학습에는 안 씀 — 학습 완료 후 참고용 별도 리포트에 사용).

CC BY-NC-SA(비영리) — 연구/데모 용도로만 사용.
ai/ 디렉터리에서 실행: python scripts/extract_scd.py 로 먼저 압축 해제 후 실행.
  python scripts/make_scd_merge.py
"""
import json
from pathlib import Path

TRAIN_SRC = Path('data/processed/box_coco_train.json')
SCD_TRAIN_ANN = Path('data/processed/scd_annotations/instances_train2017.json')
SCD_VAL_ANN = Path('data/processed/scd_annotations/instances_val2017.json')

TRAIN_DST = Path('data/processed/box_coco_train_scd.json')
SCD_VAL_DST = Path('data/processed/scd_val_coco.json')

BOX_CATEGORY_ID = 1  # 우리 스키마: box=1, pallet=2
SCD_CARTON_CATEGORY_ID = 1  # SCD 원본: Carton=1 (단일 클래스)


def remap_scd(scd: dict, img_prefix: str, image_id_start: int, ann_id_start: int):
    cats = {c['id'] for c in scd['categories']}
    assert cats == {SCD_CARTON_CATEGORY_ID}, f'SCD에 예상 밖 카테고리 존재: {scd["categories"]}'

    old_to_new_img_id = {}
    images = []
    for i, im in enumerate(scd['images']):
        new_id = image_id_start + i
        old_to_new_img_id[im['id']] = new_id
        images.append({
            'id': new_id,
            'file_name': f'{img_prefix}/{im["file_name"]}',
            'width': im['width'],
            'height': im['height'],
        })

    annotations = []
    for i, a in enumerate(scd['annotations']):
        annotations.append({
            'id': ann_id_start + i,
            'image_id': old_to_new_img_id[a['image_id']],
            'category_id': BOX_CATEGORY_ID,
            'bbox': a['bbox'],
            'area': a['area'],
            'iscrowd': a.get('iscrowd', 0),
        })
    return images, annotations


def main() -> None:
    train = json.load(open(TRAIN_SRC))
    scd_train = json.load(open(SCD_TRAIN_ANN))
    scd_val = json.load(open(SCD_VAL_ANN))

    next_img_id = max(im['id'] for im in train['images']) + 1
    next_ann_id = max(a['id'] for a in train['annotations']) + 1

    scd_train_imgs, scd_train_anns = remap_scd(
        scd_train, 'scd/train2017', next_img_id, next_ann_id)

    merged = {
        'images': train['images'] + scd_train_imgs,
        'annotations': train['annotations'] + scd_train_anns,
        'categories': train['categories'],
    }
    json.dump(merged, open(TRAIN_DST, 'w'))

    # SCD val: 별도 리포트용, id는 학습 json과 충돌만 없으면 되므로 SCD val 자체 기준 0부터 재부여
    scd_val_imgs, scd_val_anns = remap_scd(scd_val, 'scd/val2017', 0, 0)
    scd_val_out = {
        'images': scd_val_imgs,
        'annotations': scd_val_anns,
        'categories': train['categories'],
    }
    json.dump(scd_val_out, open(SCD_VAL_DST, 'w'))

    from collections import Counter
    cat_names = {c['id']: c['name'] for c in train['categories']}
    print(f'merged train: {len(train["images"])} (기존) + {len(scd_train_imgs)} (SCD) '
          f'= {len(merged["images"])} images')
    print(f'merged train anns: {len(train["annotations"])} (기존) + {len(scd_train_anns)} (SCD) '
          f'= {len(merged["annotations"])}')
    print('merged train class dist:',
          Counter(cat_names[a['category_id']] for a in merged['annotations']))
    print(f'SCD val (별도): {len(scd_val_imgs)} images, {len(scd_val_anns)} anns -> {SCD_VAL_DST}')
    print(f'-> {TRAIN_DST}')


if __name__ == '__main__':
    main()
