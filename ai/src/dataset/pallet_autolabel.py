"""검은 파렛트 자동 라벨링 — 고전 CV로 pallet bbox를 만든다.

모델이 검은 플라스틱 파렛트를 전혀 못 잡으므로(2026-07-24 실측: 임계 0.02에서도 0건)
학습 데이터를 만들려면 라벨이 필요한데, 이 리그에서는 **명도만으로 분리된다** —
검은 파렛트 vs 회색 카펫. 그래서 사람이 301장을 그리는 대신 CV로 뽑고 검수한다.

    python -m dataset.pallet_autolabel --images data/raw/rig/20260724 --sheets out/ --limit 12
    python -m dataset.pallet_autolabel --images data/raw/rig/20260724 \
        --prelabel data/processed/rig_prelabel.json --out data/processed/rig_labeled.json

파렛트는 격자 구조라 어두운 픽셀이 잘게 끊긴다 → **닫힘 연산으로 메운 뒤** 외곽을 잡는다.
사람 다리·의자·가방도 어둡지만 **가로로 길지 않다** → 종횡비로 걸러진다.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402
import numpy as np  # noqa: E402

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}
PALLET_CATEGORY_ID = 2      # configs/datasets.yaml classes 순서 (box=1, pallet=2)


@dataclass
class Candidate:
    x: int
    y: int
    w: int
    h: int
    fill: float          # bbox 대비 어두운 픽셀 비율 — 격자라 1.0은 안 나온다

    @property
    def aspect(self) -> float:
        return self.w / max(self.h, 1)


def _trim(mask: np.ndarray, x: int, y: int, w: int, h: int,
          row_cov: float = 0.5, col_cov: float = 0.25) -> tuple[int, int, int, int]:
    """덩어리에 세로로 붙은 것(파렛트 뒤에 선 사람의 검은 옷 등)을 잘라낸다.

    파렛트는 **가로로 넓은 행**이 연속으로 쌓인 모양이다. 반면 뒤에 선 사람은 좁아서
    그 행의 어두운 픽셀 커버리지가 낮다. 그래서 커버리지가 기준 미만인 행을 위에서부터
    깎고(파렛트는 바닥에 있으므로 아래를 기준으로 잡는다), 같은 방식으로 좌우도 깎는다.
    """
    region = (mask[y:y + h, x:x + w] > 0)
    rows = region.mean(axis=1)
    keep = np.flatnonzero(rows >= row_cov)
    if keep.size:
        # 아래쪽(파렛트)에서 위로 연속인 구간만 남긴다 — 중간에 끊기면 거기서 자른다
        bottom = keep[-1]
        top_idx = bottom
        while top_idx - 1 >= 0 and rows[top_idx - 1] >= row_cov:
            top_idx -= 1
        y, h = y + top_idx, bottom - top_idx + 1
        region = region[top_idx:bottom + 1]

    cols = region.mean(axis=0)
    keep = np.flatnonzero(cols >= col_cov)
    if keep.size:
        x, w = x + keep[0], keep[-1] - keep[0] + 1
    # numpy 정수가 섞이면 json 직렬화에서 터진다 (int64 is not JSON serializable)
    return int(x), int(y), int(w), int(h)


def find_pallet(frame: np.ndarray, thresh: int, roi_ratio: float) -> Candidate | None:
    """가장 그럴듯한 파렛트 후보 1개. 못 찾으면 None.

    실패 모드(1차 실측): 책상 밑 그늘·벽 밑동 같은 **배경의 어두운 띠**가 같은 높이에
    있어서, 가로로 크게 닫으면 파렛트와 이어붙어 bbox가 화면 폭까지 번진다. 그래서
    (a) 닫힘 커널을 작게, (b) **좌우 화면 가장자리에 닿는 덩어리는 배제**한다 —
    파렛트는 리그 안에 있고 배경 띠는 프레임 밖까지 이어지기 때문이다.
    """
    h, w = frame.shape[:2]
    top = int(h * roi_ratio)          # 바닥 쪽만 본다 (카메라가 낮아 파렛트는 하단)
    gray = cv2.cvtColor(frame[top:], cv2.COLOR_BGR2GRAY)

    mask = (gray < thresh).astype(np.uint8) * 255
    # 격자 구멍만 메울 정도로 닫는다 (크게 닫으면 배경과 이어붙는다)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE,
                            cv2.getStructuringElement(cv2.MORPH_RECT, (25, 15)))
    # **세로로 얇은 것 제거** — 벽 밑동·걸레받이 그늘은 높이 수십 px의 띠라 여기서
    # 사라지고, 파렛트(높이 250~300px)는 남는다. 붙어 있던 연결도 이때 끊긴다.
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN,
                            cv2.getStructuringElement(cv2.MORPH_RECT, (15, 41)))

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    best: Candidate | None = None
    for contour in contours:
        x, y, cw, ch = cv2.boundingRect(contour)
        if cw < w * 0.25 or cw > w * 0.92 or ch < h * 0.04:
            continue
        if x <= 2 or x + cw >= w - 2:           # 프레임 좌우에 닿으면 배경 띠
            continue
        x, y, cw, ch = _trim(mask, x, y, cw, ch)
        if cw < w * 0.25 or ch < h * 0.04:
            continue
        region = mask[y:y + ch, x:x + cw]
        cand = Candidate(x=x, y=y + top, w=cw, h=ch,
                         fill=float((region > 0).mean()))
        if not (2.0 <= cand.aspect <= 8):       # 사람·의자는 세로로 길다
            continue
        if cand.fill < 0.45:                    # 성긴 덩어리 = 그림자·잡동사니
            continue
        if best is None or cand.w * cand.h > best.w * best.h:
            best = cand
    return best


def draw(frame: np.ndarray, cand: Candidate | None, caption: str) -> np.ndarray:
    img = frame.copy()
    if cand:
        cv2.rectangle(img, (cand.x, cand.y), (cand.x + cand.w, cand.y + cand.h),
                      (60, 140, 255), 6)
    color = (60, 220, 60) if cand else (60, 60, 240)
    cv2.rectangle(img, (0, 0), (img.shape[1], 70), color, -1)
    cv2.putText(img, caption, (12, 50), cv2.FONT_HERSHEY_SIMPLEX, 1.6,
                (255, 255, 255), 3)
    return img


def contact_sheet(tiles: list[np.ndarray], cols: int, tile_w: int) -> np.ndarray:
    resized = []
    for tile in tiles:
        scale = tile_w / tile.shape[1]
        resized.append(cv2.resize(tile, (tile_w, int(tile.shape[0] * scale))))
    tile_h = max(t.shape[0] for t in resized)
    rows = (len(resized) + cols - 1) // cols
    sheet = np.full((rows * tile_h, cols * tile_w, 3), 20, dtype=np.uint8)
    for i, tile in enumerate(resized):
        r, c = divmod(i, cols)
        sheet[r * tile_h:r * tile_h + tile.shape[0],
              c * tile_w:(c + 1) * tile_w] = tile
    return sheet


def write_image(path: Path, img: np.ndarray) -> None:
    ok, buf = cv2.imencode(path.suffix or ".jpg", img,
                           [cv2.IMWRITE_JPEG_QUALITY, 88])
    if ok:
        buf.tofile(str(path))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="검은 파렛트 자동 라벨링 + 검수 시트")
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--prelabel", type=Path,
                        help="박스 프리라벨 COCO — 지정하면 파렛트를 합쳐서 낸다")
    parser.add_argument("--out", type=Path, help="합친 COCO json 저장 경로")
    parser.add_argument("--sheets", type=Path, help="검수 시트 저장 폴더")
    parser.add_argument("--per-sheet", type=int, default=24)
    parser.add_argument("--cols", type=int, default=4)
    parser.add_argument("--tile-width", type=int, default=480)
    parser.add_argument("--thresh", type=int, default=80, help="어두움 임계 (0-255)")
    parser.add_argument("--roi", type=float, default=0.45,
                        help="이 비율 아래쪽만 탐색 (0=전체)")
    parser.add_argument("--limit", type=int, help="앞에서 N장만 (튜닝용)")
    parser.add_argument("--stride", type=int, default=1, help="N장마다 하나씩 (튜닝용)")
    args = parser.parse_args(argv)

    files = sorted(p for p in args.images.iterdir()
                   if p.suffix.lower() in IMAGE_SUFFIXES)[::args.stride]
    if args.limit:
        files = files[:args.limit]
    if not files:
        print(f"이미지가 없습니다: {args.images}", file=sys.stderr)
        return 1

    found: dict[str, Candidate] = {}
    tiles: list[np.ndarray] = []
    sheet_index = 1
    if args.sheets:
        args.sheets.mkdir(parents=True, exist_ok=True)

    for i, path in enumerate(files, start=1):
        frame = cv2.imread(str(path))
        if frame is None:
            continue
        cand = find_pallet(frame, args.thresh, args.roi)
        if cand:
            found[path.name] = cand
        if args.sheets:
            num = path.stem.split("_")[-1]
            caption = (f"{num}  {cand.w}x{cand.h} fill{cand.fill:.2f} ar{cand.aspect:.1f}"
                       if cand else f"{num}  MISS")
            tiles.append(draw(frame, cand, caption))
            if len(tiles) == args.per_sheet or i == len(files):
                write_image(args.sheets / f"sheet_{sheet_index:02d}.jpg",
                            contact_sheet(tiles, args.cols, args.tile_width))
                tiles, sheet_index = [], sheet_index + 1
        if i % 50 == 0:
            print(f"  {i}/{len(files)}")

    print(f"\n파렛트 검출 {len(found)}/{len(files)}장 "
          f"(미검출 {len(files) - len(found)}장)")
    if found:
        widths = [c.w for c in found.values()]
        fills = [c.fill for c in found.values()]
        print(f"  폭 중앙값 {int(np.median(widths))}px "
              f"(min {min(widths)} / max {max(widths)})")
        print(f"  fill 중앙값 {np.median(fills):.2f} (min {min(fills):.2f})")

    if args.out:
        if not args.prelabel:
            print("--out에는 --prelabel이 필요합니다", file=sys.stderr)
            return 1
        coco = json.loads(args.prelabel.read_text(encoding="utf-8"))
        by_name = {img["file_name"]: img["id"] for img in coco["images"]}
        next_id = max((a["id"] for a in coco["annotations"]), default=0) + 1
        added = 0
        for name, cand in found.items():
            if name not in by_name:
                continue
            coco["annotations"].append({
                "id": next_id, "image_id": by_name[name],
                "category_id": PALLET_CATEGORY_ID,
                "bbox": [cand.x, cand.y, cand.w, cand.h],
                "area": cand.w * cand.h, "iscrowd": 0,
                "source": "autolabel",   # 검수 때 사람 수정본과 구분
            })
            next_id += 1
            added += 1
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(coco, ensure_ascii=False), encoding="utf-8")
        print(f"  파렛트 {added}개 추가 → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
