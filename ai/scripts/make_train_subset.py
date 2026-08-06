"""box_coco_train.json에서 소스 비율을 유지한 스크리닝용 서브셋을 만든다.

스크리닝(저비용 후보 비교) 전용 — val은 절대 건드리지 않는다(채점표 고정).
ai/ 디렉터리에서 실행: python scripts/make_train_subset.py
"""
import json
import random
from collections import Counter, defaultdict

SRC = 'data/processed/box_coco_train.json'
DST = 'data/processed/box_coco_train_subset.json'
FRACTION = 0.27
SEED = 42


def source(file_name: str) -> str:
    return file_name.split('/')[0]


def main() -> None:
    d = json.load(open(SRC))

    by_source = defaultdict(list)
    for im in d['images']:
        by_source[source(im['file_name'])].append(im)

    rng = random.Random(SEED)
    selected_images = []
    for imgs in by_source.values():
        n = max(1, round(len(imgs) * FRACTION))
        rng.shuffle(imgs)
        selected_images.extend(imgs[:n])

    selected_ids = {im['id'] for im in selected_images}
    anns = [a for a in d['annotations'] if a['image_id'] in selected_ids]

    json.dump(
        {
            'images': selected_images,
            'annotations': anns,
            'categories': d['categories'],
        },
        open(DST, 'w'),
    )

    cat_names = {c['id']: c['name'] for c in d['categories']}
    print(f'train images: {len(d["images"])} -> subset {len(selected_images)} '
          f'({len(selected_images) / len(d["images"]):.1%})')
    print('subset source dist:', Counter(source(im['file_name']) for im in selected_images))
    print('subset class dist:', Counter(cat_names[a['category_id']] for a in anns))


if __name__ == '__main__':
    main()
