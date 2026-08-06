"""기존 combined 학습 json(box_coco_train_scd.json)에 실물 리그 촬영분(rig)을 병합한다.

배경(S15P11A304-156): 실험5 모델이 스테이션 실물 검증에서 검은 플라스틱 파렛트를 전혀
잡지 못했다(score 0.02까지 낮춰도 후보 0건, 같은 프레임 박스는 0.91~0.92). 목재 파렛트 위주인
LOCO 도메인과 어긋나는 것이 원인 — 자체 촬영 289장을 도메인 적응용으로 섞는다.

정책:
- rig 289장은 한 세션·한 배치 촬영이라 여기서 val을 떼면 누수다. **전량 train**에 넣는다.
- val(box_coco_val.json)은 건드리지 않는다 — 기존 채점표 고정, 회귀 감시 전용.
- 2단 순차 파인튜닝은 실험4에서 pallet catastrophic forgetting으로 폐기됐다. 단일 combined run.

카테고리는 rig 원본이 이미 우리 스키마(box=1, pallet=2)와 동일 — 리맵 불필요, assert로만 검증.

이미지는 복사하지 않고 staged_images/rig 심볼릭 링크로 노출한다(다른 소스와 동일하게
data/processed/staged_images/ 기준 상대경로 file_name을 쓰기 위함).

ai/ 디렉터리에서 실행:
  python scripts/make_rig_merge.py
"""
import json
from pathlib import Path

TRAIN_SRC = Path('data/processed/box_coco_train_scd.json')
RIG_SRC = Path('data/processed/rig_labeled.json')
RIG_IMG_DIR = Path('data/raw/rig/20260724')

STAGED_ROOT = Path('data/processed/staged_images')
RIG_STAGE_NAME = 'rig'  # staged_images/rig -> ../../raw/rig/20260724

TRAIN_DST = Path('data/processed/box_coco_train_scd_rig.json')

# 기존 계보와 동일한 스키마
EXPECTED_CATS = {1: 'box', 2: 'pallet'}


def stage_images() -> None:
    """staged_images/rig 심볼릭 링크를 만든다(이미 있으면 대상만 검증)."""
    link = STAGED_ROOT / RIG_STAGE_NAME
    target = Path('../../raw/rig/20260724')
    if link.is_symlink() or link.exists():
        resolved = link.resolve()
        assert resolved == RIG_IMG_DIR.resolve(), f'{link} 가 예상 밖 대상을 가리킴: {resolved}'
        print(f'  심볼릭 링크 이미 존재: {link} -> {target}')
        return
    link.symlink_to(target, target_is_directory=True)
    print(f'  심볼릭 링크 생성: {link} -> {target}')


def clip_bbox(bbox, width, height):
    """이미지 경계 밖으로 나간 bbox를 잘라낸다. (clipped_bbox, 변경여부) 반환."""
    x, y, w, h = bbox
    x1, y1 = max(0.0, x), max(0.0, y)
    x2, y2 = min(float(width), x + w), min(float(height), y + h)
    clipped = [x1, y1, x2 - x1, y2 - y1]
    changed = any(abs(a - b) > 1e-6 for a, b in zip(clipped, bbox))
    return clipped, changed


def remap_rig(rig: dict, img_prefix: str, image_id_start: int, ann_id_start: int):
    cats = {c['id']: c['name'] for c in rig['categories']}
    assert cats == EXPECTED_CATS, f'rig 카테고리가 기존 계보와 불일치: {rig["categories"]}'

    old_to_new_img_id = {}
    images = []
    sizes = {}
    for i, im in enumerate(sorted(rig['images'], key=lambda x: x['file_name'])):
        new_id = image_id_start + i
        old_to_new_img_id[im['id']] = new_id
        sizes[new_id] = (im['width'], im['height'])
        images.append({
            'id': new_id,
            'file_name': f'{img_prefix}/{im["file_name"]}',
            'width': im['width'],
            'height': im['height'],
        })

    annotations = []
    n_clipped = 0
    n_dropped = 0
    for j, ann in enumerate(sorted(rig['annotations'], key=lambda a: a['id'])):
        new_img_id = old_to_new_img_id[ann['image_id']]
        w_img, h_img = sizes[new_img_id]
        bbox, changed = clip_bbox(ann['bbox'], w_img, h_img)
        if changed:
            n_clipped += 1
        if bbox[2] <= 1.0 or bbox[3] <= 1.0:  # 클리핑 후 퇴화한 박스는 버린다
            n_dropped += 1
            continue
        annotations.append({
            'id': ann_id_start + j,
            'image_id': new_img_id,
            'category_id': ann['category_id'],
            'bbox': [round(v, 2) for v in bbox],
            'area': round(bbox[2] * bbox[3], 2),
            'iscrowd': ann.get('iscrowd', 0),
        })
    return images, annotations, n_clipped, n_dropped


def main() -> None:
    print('== staged_images 준비 ==')
    stage_images()

    print('\n== 원본 로드 ==')
    train = json.loads(TRAIN_SRC.read_text())
    rig = json.loads(RIG_SRC.read_text())
    print(f'  기존 train : {len(train["images"])}장 / {len(train["annotations"])} ann')
    print(f'  rig        : {len(rig["images"])}장 / {len(rig["annotations"])} ann')

    # id 충돌 방지 오프셋
    image_id_start = max(im['id'] for im in train['images']) + 1000
    ann_id_start = max(a['id'] for a in train['annotations']) + 1000

    images, annotations, n_clipped, n_dropped = remap_rig(
        rig, RIG_STAGE_NAME, image_id_start, ann_id_start)
    print(f'\n== rig 리맵 ==')
    print(f'  image id {image_id_start}~{image_id_start + len(images) - 1}')
    print(f'  ann   id {ann_id_start}~{ann_id_start + len(rig["annotations"]) - 1}')
    print(f'  경계 클리핑된 bbox: {n_clipped}건, 퇴화로 제거된 bbox: {n_dropped}건')

    # 실제 파일 존재 확인
    missing = [im['file_name'] for im in images
               if not (STAGED_ROOT / im['file_name']).exists()]
    assert not missing, f'파일 없음 {len(missing)}건: {missing[:5]}'
    print(f'  이미지 파일 존재 확인: {len(images)}장 전수 통과')

    # id 충돌 최종 검증
    train_img_ids = {im['id'] for im in train['images']}
    train_ann_ids = {a['id'] for a in train['annotations']}
    assert not (train_img_ids & {im['id'] for im in images}), 'image id 충돌'
    assert not (train_ann_ids & {a['id'] for a in annotations}), 'annotation id 충돌'

    merged = {
        'images': train['images'] + images,
        'annotations': train['annotations'] + annotations,
        'categories': train['categories'],
    }
    TRAIN_DST.write_text(json.dumps(merged))
    print(f'\n== 저장: {TRAIN_DST} ==')
    print(f'  총 {len(merged["images"])}장 / {len(merged["annotations"])} ann')


if __name__ == '__main__':
    main()
