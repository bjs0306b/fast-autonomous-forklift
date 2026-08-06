"""SCD(coco_style_oneclass.zip)를 staged_images 레이아웃으로 압축 해제한다.

원본: data/raw/coco_style_oneclass.zip (COCO 스타일, category 'Carton' 단일 클래스)
대상: data/processed/staged_images/scd/{train2017,val2017}/*.jpg
      annotation json은 원본 그대로 data/processed/scd_annotations/ 에 보관(후속 병합용).

CC BY-NC-SA(비영리) 라이선스 — 연구/데모 용도로만 사용.
ai/ 디렉터리에서 실행: python scripts/extract_scd.py
"""
import zipfile
from pathlib import Path

ZIP_PATH = Path('data/raw/coco_style_oneclass.zip')
IMG_DEST = Path('data/processed/staged_images/scd')
ANN_DEST = Path('data/processed/scd_annotations')


def main() -> None:
    z = zipfile.ZipFile(ZIP_PATH)
    IMG_DEST.mkdir(parents=True, exist_ok=True)
    ANN_DEST.mkdir(parents=True, exist_ok=True)

    n_extracted = 0
    for info in z.infolist():
        name = info.filename
        if name.endswith('/'):
            continue
        if name.startswith('images/train2017/'):
            rel = name[len('images/'):]  # train2017/<file>
            out = IMG_DEST / rel
        elif name.startswith('images/val2017/'):
            rel = name[len('images/'):]  # val2017/<file>
            out = IMG_DEST / rel
        elif name.startswith('annotations/') and name.endswith('.json'):
            out = ANN_DEST / Path(name).name
        else:
            continue
        out.parent.mkdir(parents=True, exist_ok=True)
        with z.open(info) as src, open(out, 'wb') as dst:
            dst.write(src.read())
        n_extracted += 1
        if n_extracted % 1000 == 0:
            print(f'  ...{n_extracted} files extracted')

    print(f'done: {n_extracted} files extracted to {IMG_DEST} / {ANN_DEST}')


if __name__ == '__main__':
    main()
