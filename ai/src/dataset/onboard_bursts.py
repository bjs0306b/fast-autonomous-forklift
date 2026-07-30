"""온보드 촬영본 버스트 분석 — 라벨링할 프레임을 고르고, 그린 라벨을 나머지에 전파한다.

`shoot.py --burst 6` 으로 찍었으므로 358장은 **61개 버스트**(같은 초에 저장된 6~7장
묶음)로 되어 있다. 버스트 안에서 피사체가 안 움직였다면 6장을 따로 그리는 건 같은
그림을 여섯 번 그리는 것이다. 그런 버스트는 **키프레임 1장만 그리고 복사**한다.

    # 1) 어느 프레임을 그려야 하는지 정하고 CVAT 업로드용으로 모은다
    python -m dataset.onboard_bursts plan \
        --images data/raw/onboard/train_20260729/b01_upright \
        --out data/labels/onboard_burst_plan.json \
        --keyframe-dir data/processed/cvat_onboard_pass1

    # 2) CVAT에서 내보낸 키프레임 라벨을 358장 전체로 펼친다
    python -m dataset.onboard_bursts expand \
        --plan data/labels/onboard_burst_plan.json \
        --coco data/labels/onboard_cvat_pass1.json \
        --out data/labels/onboard_cvat.json

이후는 `dataset.split_onboard` 가 구간 단위로 train/val을 나눈다.

**판정은 밝기가 아니라 이동량으로 한다.** 처음엔 프레임 간 평균절대차를 썼는데
자동 노출 때문에 부풀려졌다 — 프레임 7↔12는 파렛트가 22px 움직였는데도 차이의
대부분이 밝기 변동이었고, 반대로 밝기가 튀기만 한 정지 버스트도 '움직임'으로
잡혔다. 그래서 하단-중앙(파렛트가 들어오는 곳)을 밝기·콘트라스트 정규화한 뒤
phaseCorrelate로 **몇 px 밀렸는지**를 잰다.

임계 4px의 근거: 전파가 깨지는 건 `hole` 이다. 개구부는 1280×800에서 세로 약
25~60px이므로(가이드 §3-④ 하한이 16px) 4px는 그 1/6 이하이고, 사람이 그은 bbox
자체의 흔들림과 같은 수준이다. `pallet`·`box` 는 훨씬 크므로 더 관대하다.
"""

from __future__ import annotations

import argparse
import csv
import datetime
import json
import shutil
import sys
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from dataset.coco import frame_number  # noqa: E402

CLASSES = ["box", "pallet", "hole"]

# 파렛트가 들어오는 영역 (1280×800 기준). 전신 프레임을 쓰면 배경에서
# 움직이는 사람·의자가 이동량을 지배해 버려 파렛트 판정이 흐려진다.
DEFAULT_ROI = (320, 1088, 300, 640)      # x0 x1 y0 y1

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


frame_no = frame_number      # 이름 호환 — 구간 로직은 전부 이 번호를 쓴다

# 구간별 box 기대치 (docs/ai/onboard-dataset-batches.md 구간 내용에서 온 값).
# 프리라벨이 이보다 2배 이상이면 과검출로 보고 box를 버릴지 판단한다 —
# 착수 전에 정해둔 기준이다(사후에 "좋아졌나" 묻지 않는다).
BOX_EXPECTED = {
    1: (1, 1), 2: (3, 4), 3: (1, 2), 4: (1, 1),
    5: (1, 1), 6: (1, 2), 7: (1, 2), 8: (0, 0),
}


def segment_of(n: int | None) -> int | None:
    if n is None:
        return None
    for i, (s, e, _) in enumerate(SEGMENTS, 1):
        if s <= n <= e:
            return i
    return None


def group_bursts(session_csv: Path, gap_s: float) -> list[list[str]]:
    """저장 시각 공백으로 버스트를 묶는다. 버스트 내부는 같은 초에 저장된다."""
    rows = list(csv.DictReader(session_csv.open(encoding="utf-8")))
    bursts: list[list[str]] = []
    cur: list[str] = []
    prev: datetime.datetime | None = None
    for r in rows:
        t = datetime.datetime.fromisoformat(r["saved_at"])
        if prev is not None and (t - prev).total_seconds() >= gap_s:
            bursts.append(cur)
            cur = []
        cur.append(r["file"])
        prev = t
    if cur:
        bursts.append(cur)
    return bursts


def _roi_norm(path: Path, roi: tuple[int, int, int, int]) -> np.ndarray | None:
    """ROI를 잘라 밝기·콘트라스트를 정규화 — 자동 노출 변동을 상쇄한다."""
    g = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if g is None:
        return None
    x0, x1, y0, y1 = roi
    crop = g[y0:y1, x0:x1].astype(np.float32)
    if crop.size == 0:
        return None
    return (crop - crop.mean()) / (crop.std() + 1e-6)


def max_shift(images_dir: Path, files: list[str],
              roi: tuple[int, int, int, int]) -> float:
    """키프레임(첫 장) 대비 버스트 내 최대 이동량(px)."""
    ref = _roi_norm(images_dir / files[0], roi)
    if ref is None:
        return float("inf")
    x0, x1, y0, y1 = roi
    window = cv2.createHanningWindow((x1 - x0, y1 - y0), cv2.CV_32F)
    worst = 0.0
    for name in files[1:]:
        cur = _roi_norm(images_dir / name, roi)
        if cur is None:
            return float("inf")
        (dx, dy), _ = cv2.phaseCorrelate(ref, cur, window)
        worst = max(worst, float(np.hypot(dx, dy)))
    return worst


def cmd_plan(a) -> int:
    session = a.images / "session.csv"
    if not session.exists():
        print(f"session.csv가 없습니다: {session}", file=sys.stderr)
        return 1

    roi = tuple(a.roi)
    bursts = group_bursts(session, a.burst_gap)
    print(f"버스트 {len(bursts)}개 / 프레임 {sum(len(b) for b in bursts)}장")
    print(f"ROI x{roi[0]}~{roi[1]} y{roi[2]}~{roi[3]} · 전파 임계 {a.shift_thr}px\n")
    print("프레임      | 장수 | 구간 | 최대이동 | 판정")

    plan_bursts, to_draw = [], []
    for files in bursts:
        shift = max_shift(a.images, files, roi)
        propagate = shift < a.shift_thr
        n0, n1 = frame_no(files[0]), frame_no(files[-1])
        seg = segment_of(n0)
        plan_bursts.append({
            "keyframe": files[0],
            "frames": files,
            "segment": seg,
            "max_shift_px": round(shift, 2),
            "propagate": propagate,
        })
        to_draw.extend([files[0]] if propagate else files)
        print(f"{n0:3d}~{n1:3d} | {len(files):2d}장 | 구간{seg} | "
              f"{shift:7.2f} | {'전파 가능' if propagate else '개별 라벨'}")

    total = sum(len(b) for b in bursts)
    prop = [b for b in plan_bursts if b["propagate"]]
    print(f"\n전파 가능 버스트 {len(prop)}개 "
          f"({sum(len(b['frames']) for b in prop)}장) / "
          f"개별 {len(plan_bursts) - len(prop)}개")
    print(f"그릴 프레임 {len(to_draw)}장 / {total}장 "
          f"({len(to_draw) / total * 100:.0f}%) · 절감 {total - len(to_draw)}장")

    # 구간 8은 네거티브라 그릴 것이 없다 — 훑어보고 비워두면 된다.
    neg = [f for f in to_draw if segment_of(frame_no(f)) == 8]
    if neg:
        print(f"  이 중 {len(neg)}장은 구간 8(네거티브) — 라벨 없음 확인만 하면 된다")

    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps({
        "images_dir": str(a.images),
        "roi": list(roi),
        "burst_gap_s": a.burst_gap,
        "shift_thr_px": a.shift_thr,
        "classes": CLASSES,
        "bursts": plan_bursts,
        "to_draw": to_draw,
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n계획 → {a.out}")

    if a.keyframe_dir:
        a.keyframe_dir.mkdir(parents=True, exist_ok=True)
        n = 0
        for name in to_draw:
            src = a.images / name
            if src.exists():
                shutil.copy2(src, a.keyframe_dir / name)
                n += 1
        print(f"CVAT 업로드용 {n}장 → {a.keyframe_dir}")
    return 0


def expand(plan: dict, coco: dict) -> tuple[dict, dict, list[str]]:
    """키프레임만 그려진 COCO를 버스트 전체로 펼친다.

    반환: (COCO dict, 통계, 경고 목록). 경고는 **막지 않는다** — 라벨러가
    프레임을 빼먹었는지는 사람이 판단해야 한다.
    """
    warn: list[str] = []
    got = [c["name"] for c in sorted(coco["categories"], key=lambda c: c["id"])]
    if got != CLASSES:
        warn.append(f"클래스 순서가 다르다: {got} (기대: {CLASSES}) — "
                    "category_id 1·2·3 = box·pallet·hole 이어야 config와 맞는다")

    by_name = {im["file_name"]: im for im in coco["images"]}
    anns_by_id = defaultdict(list)
    for x in coco["annotations"]:
        anns_by_id[x["image_id"]].append(x)

    missing = [f for f in plan["to_draw"] if f not in by_name]
    if missing:
        warn.append(f"그려야 할 프레임 {len(missing)}장이 export에 없다 "
                    f"(예: {missing[:3]}) — CVAT 태스크가 전부 올라갔는지 확인")

    out_images: list[dict] = []
    out_anns: list[dict] = []
    next_img = next_ann = 1
    copied = 0

    for burst in plan["bursts"]:
        key = burst["keyframe"]
        key_im = by_name.get(key)
        if burst["propagate"] and key_im is None:
            warn.append(f"키프레임이 export에 없어 버스트를 건너뛴다: {key}")
            continue
        for name in burst["frames"]:
            # 전파 버스트는 키프레임 라벨을 그대로 쓴다. 이동량이 임계 미만이라
            # bbox를 옮기지 않아도 어긋나지 않는다.
            donor = key_im if burst["propagate"] else by_name.get(name)
            if donor is None:
                warn.append(f"개별 라벨 프레임이 export에 없다: {name}")
                continue
            out_images.append({
                "id": next_img,
                "file_name": name,
                "width": donor["width"],
                "height": donor["height"],
            })
            for x in anns_by_id.get(donor["id"], []):
                y = dict(x)
                y["id"] = next_ann
                y["image_id"] = next_img
                out_anns.append(y)
                next_ann += 1
            if burst["propagate"] and name != key:
                copied += 1
            next_img += 1

    cat_name = {c["id"]: c["name"] for c in coco["categories"]}
    per_class: dict[str, int] = defaultdict(int)
    for x in out_anns:
        per_class[cat_name[x["category_id"]]] += 1
    with_ann = {y["image_id"] for y in out_anns}

    out = {"images": out_images, "annotations": out_anns,
           "categories": coco["categories"]}
    stats = {
        "images": len(out_images),
        "annotations": len(out_anns),
        "propagated": copied,
        "empty": sum(1 for im in out_images if im["id"] not in with_ann),
        "per_class": dict(per_class),
    }
    return out, stats, warn


def cmd_expand(a) -> int:
    plan = json.loads(a.plan.read_text(encoding="utf-8"))
    coco = json.loads(a.coco.read_text(encoding="utf-8"))
    out, stats, warn = expand(plan, coco)

    for w in warn:
        print("⚠️ " + w)

    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")

    print(f"이미지 {stats['images']}장 / 어노 {stats['annotations']}개 → {a.out}")
    print(f"  전파로 채운 프레임 {stats['propagated']}장")
    for c in CLASSES:
        print(f"  {c}: {stats['per_class'].get(c, 0)}")
    print(f"  라벨 없는 프레임 {stats['empty']}장 "
          "(네거티브 42장이 여기 포함돼야 정상)")
    if not stats["per_class"].get("hole"):
        print("⚠️ hole이 0개다 — 3클래스 학습의 의미가 없다. 라벨을 확인할 것.")
    return 0


def audit(coco: dict) -> tuple[list[dict], list[str]]:
    """구간별 클래스 분포를 세고, box 기대치와 대조한다.

    미리 정한 판정 기준을 실제로 재는 자리다 — box는 게이트가 없어서
    "감으로 괜찮아 보인다"로 넘어가기 쉽다.
    """
    cat_name = {c["id"]: c["name"] for c in coco["categories"]}
    per_img = defaultdict(lambda: defaultdict(int))
    for x in coco["annotations"]:
        per_img[x["image_id"]][cat_name.get(x["category_id"], "?")] += 1

    seg_imgs: dict[int, list[int]] = defaultdict(list)
    for im in coco["images"]:
        seg = segment_of(frame_number(im["file_name"]))
        if seg is not None:
            seg_imgs[seg].append(im["id"])

    rows, warn = [], []
    for i, (s, e, name) in enumerate(SEGMENTS, 1):
        ids = seg_imgs.get(i, [])
        if not ids:
            continue
        counts = {c: sum(per_img[j][c] for j in ids) for c in CLASSES}
        box_per = counts["box"] / len(ids)
        lo, hi = BOX_EXPECTED[i]
        verdict = "ok"
        if hi == 0 and counts["box"]:
            verdict = "네거티브에 box"
            warn.append(f"구간 {i}({name}): 네거티브인데 box {counts['box']}개 — "
                        "프리라벨을 구간 8에 돌렸는지 확인")
        elif box_per > hi * 2:
            verdict = "과검출 의심"
            warn.append(f"구간 {i}({name}): box 장당 {box_per:.2f}개, 기대 {lo}~{hi} — "
                        "2배 초과. box를 버리고 2클래스로 갈지 판단할 것")
        rows.append({"segment": i, "name": name, "images": len(ids),
                     "box": counts["box"], "box_per_img": round(box_per, 2),
                     "expected": f"{lo}~{hi}", "pallet": counts["pallet"],
                     "hole": counts["hole"], "verdict": verdict})
    return rows, warn


def cmd_audit(a) -> int:
    coco = json.loads(a.coco.read_text(encoding="utf-8"))
    rows, warn = audit(coco)

    print("구간            | 장수 | box | 장당 | 기대  | pallet | hole | 판정")
    for r in rows:
        print(f"{r['name']:15s} | {r['images']:4d} | {r['box']:3d} | "
              f"{r['box_per_img']:4.2f} | {r['expected']:5s} | {r['pallet']:6d} | "
              f"{r['hole']:4d} | {r['verdict']}")

    # hole은 파렛트가 보이는 구간에서 장당 2~4개가 정상이다(§3-⑥ 최대 4).
    for r in rows:
        if r["segment"] != 8 and r["pallet"] and not r["hole"]:
            print(f"⚠️ 구간 {r['segment']}: pallet은 있는데 hole이 0개다")

    for w in warn:
        print("⚠️ " + w)
    if not warn:
        print("\n기대치 이탈 없음.")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="온보드 버스트 라벨 계획·전파")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("plan", help="그릴 프레임 선정 + CVAT 업로드용 수집")
    p.add_argument("--images", type=Path, required=True, help="촬영 폴더(session.csv 포함)")
    p.add_argument("--out", type=Path, default=Path("data/labels/onboard_burst_plan.json"))
    p.add_argument("--keyframe-dir", type=Path, help="그릴 프레임만 복사할 폴더")
    p.add_argument("--burst-gap", type=float, default=3.0, help="버스트 경계 초 (기본 3)")
    p.add_argument("--shift-thr", type=float, default=4.0, help="전파 허용 이동량 px (기본 4)")
    p.add_argument("--roi", type=int, nargs=4, default=list(DEFAULT_ROI),
                   metavar=("X0", "X1", "Y0", "Y1"))
    p.set_defaults(func=cmd_plan)

    e = sub.add_parser("expand", help="키프레임 라벨을 버스트 전체로 펼치기")
    e.add_argument("--plan", type=Path, required=True)
    e.add_argument("--coco", type=Path, required=True, help="CVAT COCO export")
    e.add_argument("--out", type=Path, default=Path("data/labels/onboard_cvat.json"))
    e.set_defaults(func=cmd_expand)

    d = sub.add_parser("audit", help="구간별 클래스 분포 + box 기대치 대조")
    d.add_argument("--coco", type=Path, required=True)
    d.set_defaults(func=cmd_audit)

    a = ap.parse_args(argv)
    return a.func(a)


if __name__ == "__main__":
    raise SystemExit(main())
