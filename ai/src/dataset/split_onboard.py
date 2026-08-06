"""온보드 라벨 반입 — 검수 + 구간 단위 train/val 분할 + 이미지 스테이징.

CVAT에서 COCO로 내보낸 라벨을 학습에 쓸 형태로 바꾼다.

    python -m dataset.split_onboard \
        --coco data/labels/onboard_cvat.json \
        --images data/raw/onboard/train_20260729/b01_upright

**val은 구간 단위로 뗀다 — 랜덤 분할 금지.**
버스트 촬영이라 한 배치 안 프레임은 서로 거의 같다. 랜덤으로 나누면 train과 val에
사실상 같은 사진이 갈려 들어가 val이 부풀려진다(리그에서 겪은 오염 패턴).
구간 매핑 근거는 docs/ai/onboard-dataset-batches.md.

라벨 규약 위반도 함께 검사한다(docs/ai/onboard-hole-label-guide.md §4). 검사는
**막지 않고 경고만** 한다 — 규약을 어긴 프레임이 정말 예외인지는 사람이 봐야 한다.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
from collections import defaultdict
from pathlib import Path

CLASSES = ["box", "pallet", "hole"]

# 촬영 시각 공백으로 복원한 구간 (docs/ai/onboard-dataset-batches.md)
SEGMENTS = [
    (1, 54, "1 정면"),
    (55, 127, "2 회전+편심"),
    (128, 164, "3 오버행"),
    (165, 213, "4 정면"),
    (214, 292, "5 박스 파렛트밖"),
    (293, 298, "6 배경변형"),
    (299, 316, "7 배경변형"),
    (317, 358, "8 네거티브"),
]
VAL_SEGMENTS = {4, 7}      # 정면 + 배경변형 조합 — 대표성이 있고 서로 다른 날 배치다

FRAME_RE = re.compile(r"_(\d{4})\.jpg$", re.IGNORECASE)


def frame_no(file_name: str) -> int | None:
    m = FRAME_RE.search(file_name)
    return int(m.group(1)) if m else None


def segment_of(n: int) -> int | None:
    for i, (s, e, _) in enumerate(SEGMENTS, 1):
        if s <= n <= e:
            return i
    return None


def _iou_contains(inner, outer, slack: int = 8) -> bool:
    ix, iy, iw, ih = inner
    ox, oy, ow, oh = outer
    return (ix >= ox - slack and iy >= oy - slack
            and ix + iw <= ox + ow + slack and iy + ih <= oy + oh + slack)


def verify(images, anns_by_img, cat_name) -> list[str]:
    """라벨 가이드 §4의 sanity check. 막지 않고 경고만 모은다."""
    warn = []
    for im in images:
        a = anns_by_img.get(im["id"], [])
        by = defaultdict(list)
        for x in a:
            by[cat_name[x["category_id"]]].append(x["bbox"])
        holes, pallets = by["hole"], by["pallet"]

        if holes and not pallets:
            warn.append(f"{im['file_name']}: pallet 없이 hole만 {len(holes)}개")
        if len(holes) > 4:
            warn.append(f"{im['file_name']}: hole {len(holes)}개 (구조상 최대 4)")
        for hb in holes:
            if pallets and not any(_iou_contains(hb, pb) for pb in pallets):
                warn.append(f"{im['file_name']}: pallet 밖 hole {hb}")
            if hb[3] > 0 and hb[2] / hb[3] < 1.5:
                warn.append(f"{im['file_name']}: hole 종횡비 {hb[2]/hb[3]:.1f} "
                            f"(정상 약 3.7, 너무 정사각이면 오라벨 의심)")
            if hb[3] < 16:
                warn.append(f"{im['file_name']}: hole 세로 {hb[3]}px "
                            f"(가이드 하한 16px 미만 — 라벨 대상이 아니다)")
    return warn


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="온보드 라벨 반입·분할")
    ap.add_argument("--coco", type=Path, required=True, help="CVAT COCO export")
    ap.add_argument("--images", type=Path, required=True, help="원본 이미지 폴더")
    # 기본값은 리포 루트(ai/) 기준이다. src에서 실행하면 여기에 --out-dir을 명시하지
    # 않는 한 src/data/processed로 새므로, 실행 위치를 ai/로 두거나 경로를 명시할 것.
    ap.add_argument("--out-dir", type=Path, default=Path("data/processed"))
    ap.add_argument("--stage", default="staged_images_onboard")
    ap.add_argument("--no-copy", action="store_true", help="이미지 복사 생략(검증만)")
    a = ap.parse_args(argv)

    coco = json.loads(a.coco.read_text(encoding="utf-8"))
    cat_name = {c["id"]: c["name"] for c in coco["categories"]}

    got = [c["name"] for c in sorted(coco["categories"], key=lambda c: c["id"])]
    if got != CLASSES:
        print(f"⚠️ 클래스 순서가 다르다: {got} (기대: {CLASSES})")
        print("   category_id 1·2·3 = box·pallet·hole 이어야 config와 맞는다.")

    anns_by_img = defaultdict(list)
    for x in coco["annotations"]:
        anns_by_img[x["image_id"]].append(x)

    for w in verify(coco["images"], anns_by_img, cat_name):
        print("  ⚠️ " + w)

    split = {"train": {"images": [], "annotations": []},
             "val": {"images": [], "annotations": []}}
    per_seg = defaultdict(lambda: [0, 0])       # seg -> [장수, 어노테이션]
    unknown = 0

    for im in coco["images"]:
        n = frame_no(im["file_name"])
        seg = segment_of(n) if n else None
        if seg is None:
            unknown += 1
            continue
        key = "val" if seg in VAL_SEGMENTS else "train"
        split[key]["images"].append(im)
        split[key]["annotations"].extend(anns_by_img.get(im["id"], []))
        per_seg[seg][0] += 1
        per_seg[seg][1] += len(anns_by_img.get(im["id"], []))

    if unknown:
        print(f"⚠️ 구간을 알 수 없는 이미지 {unknown}장 — 파일명이 _NNNN.jpg 형식인지 확인")

    print("\n구간            | 장수 | 어노 | 용도")
    for i, (s, e, name) in enumerate(SEGMENTS, 1):
        c, an = per_seg[i]
        print(f"{name:15s} | {c:4d} | {an:4d} | {'val' if i in VAL_SEGMENTS else 'train'}")

    a.out_dir.mkdir(parents=True, exist_ok=True)
    for key in ("train", "val"):
        out = a.out_dir / f"onboard_coco_{key}.json"
        out.write_text(json.dumps({
            "images": split[key]["images"],
            "annotations": split[key]["annotations"],
            "categories": coco["categories"],
        }, ensure_ascii=False), encoding="utf-8")
        cnt = defaultdict(int)
        for x in split[key]["annotations"]:
            cnt[cat_name[x["category_id"]]] += 1
        print(f"\n{key}: 이미지 {len(split[key]['images'])}장 / "
              f"어노 {len(split[key]['annotations'])}개 → {out}")
        for c in CLASSES:
            print(f"   {c}: {cnt[c]}")
        if key == "train" and cnt["hole"] == 0:
            print("   ⚠️ hole이 0개다 — 온보드는 pallet·hole 2클래스이고, hole 이 "
                  "없으면 포크 정렬이 성립하지 않는다. 라벨을 확인할 것.")

    if not a.no_copy:
        dst = a.out_dir / a.stage
        dst.mkdir(parents=True, exist_ok=True)
        n = 0
        for key in ("train", "val"):
            for im in split[key]["images"]:
                src = a.images / im["file_name"]
                if src.exists():
                    shutil.copy2(src, dst / im["file_name"])
                    n += 1
        print(f"\n이미지 {n}장 스테이징 → {dst}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
