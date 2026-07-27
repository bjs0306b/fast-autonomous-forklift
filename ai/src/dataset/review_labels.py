"""평가셋 라벨 검수기 — 프레임을 순회하며 박스·파렛트를 추가·삭제한다.

평가셋은 채점표라 라벨이 곧 정답이다. 자동 필터만으로는 완벽하지 않다는 것이
실측으로 확인됐다 (2026-07-27, eval_20260727):

- 점수 0.65~0.67짜리 **진짜 박스**가 있고(0057 위쪽 박스, 0077 세워진 박스),
  같은 구간에 **배경 오탐**도 있다(0047 캐비닛 0.50). 임계 하나로는 못 가른다.
- 그래서 자동 정리(`dataset.clean_eval`)로 대부분 걸러낸 뒤, 여기서 사람이 마무리한다.

    python -m dataset.review_labels --coco data/processed/eval_20260727_clean.json \
        --images data/raw/rig/eval_20260727

조작:
    드래그          현재 클래스로 bbox 추가
    우클릭          그 지점의 bbox 삭제 (겹치면 가장 작은 것)
    1 / 2           클래스 전환 (1=box 초록, 2=pallet 주황)
    N / SPACE       다음 프레임        B  이전 프레임
    R               그리는 중인 사각형 취소
    S               지금까지 저장 (종료 안 함)
    Q / ESC         저장하고 종료

원본은 최초 저장 시 ``<파일명>.bak``으로 백업한다. 화면 좌표는 원본 해상도로 환산해
저장하므로 뷰 크기를 바꿔도 라벨은 정확하다.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402

BOX_ID, PALLET_ID = 1, 2
COLORS = {BOX_ID: (80, 200, 80), PALLET_ID: (60, 140, 255)}
NAMES = {BOX_ID: "box", PALLET_ID: "pallet"}
DRAW_COLOR = (60, 240, 240)
MIN_SIDE_PX = 10          # 이보다 작으면 실수로 클릭한 것으로 본다


class Reviewer:
    def __init__(self, coco: dict, images_dir: Path, ids: list[int], view_w: int):
        self.coco = coco
        self.images_dir = images_dir
        self.ids = ids
        self.view_w = view_w
        self.by_id = {img["id"]: img for img in coco["images"]}
        self.cursor = 0
        self.cls = BOX_ID
        self.drag_start: tuple[int, int] | None = None
        self.rect: tuple[int, int, int, int] | None = None
        self.pending_delete: tuple[int, int] | None = None
        self.changed = 0

    # --- 데이터 접근 ---
    def anns_of(self, image_id: int) -> list[dict]:
        return [a for a in self.coco["annotations"] if a["image_id"] == image_id]

    def next_ann_id(self) -> int:
        return max((a["id"] for a in self.coco["annotations"]), default=0) + 1

    def add(self, image_id: int, rect: tuple[int, int, int, int], scale: float) -> None:
        x1, y1, x2, y2 = rect
        x, y = min(x1, x2) / scale, min(y1, y2) / scale
        w, h = abs(x2 - x1) / scale, abs(y2 - y1) / scale
        if w < MIN_SIDE_PX or h < MIN_SIDE_PX:
            return
        self.coco["annotations"].append({
            "id": self.next_ann_id(), "image_id": image_id,
            "category_id": self.cls,
            "bbox": [round(x, 1), round(y, 1), round(w, 1), round(h, 1)],
            "area": round(w * h, 1), "iscrowd": 0, "source": "manual",
        })
        self.changed += 1

    def delete_at(self, image_id: int, pt: tuple[int, int], scale: float) -> None:
        """클릭 지점을 품은 bbox 중 **가장 작은 것**을 지운다 — 큰 박스 위에 겹친
        작은 박스를 지우려는 의도가 대부분이기 때문."""
        px, py = pt[0] / scale, pt[1] / scale
        hits = []
        for a in self.anns_of(image_id):
            x, y, w, h = a["bbox"]
            if x <= px <= x + w and y <= py <= y + h:
                hits.append((w * h, a))
        if not hits:
            return
        _, target = min(hits, key=lambda t: t[0])
        self.coco["annotations"].remove(target)
        self.changed += 1

    # --- 입력 ---
    def on_mouse(self, event, x, y, flags, _param) -> None:
        if event == cv2.EVENT_LBUTTONDOWN:
            self.drag_start = (x, y)
            self.rect = None
        elif event == cv2.EVENT_MOUSEMOVE and self.drag_start:
            self.rect = (*self.drag_start, x, y)
        elif event == cv2.EVENT_LBUTTONUP and self.drag_start:
            self.rect = (*self.drag_start, x, y)
            self.drag_start = None
        elif event == cv2.EVENT_RBUTTONDOWN:
            self.pending_delete = (x, y)

    def save(self, path: Path) -> None:
        backup = path.with_suffix(path.suffix + ".bak")
        if not backup.exists():
            shutil.copy2(path, backup)
        path.write_text(json.dumps(self.coco, ensure_ascii=False), encoding="utf-8")

    # --- 루프 ---
    def run(self, coco_path: Path) -> None:
        cv2.namedWindow("review")
        cv2.setMouseCallback("review", self.on_mouse)

        while 0 <= self.cursor < len(self.ids):
            image_id = self.ids[self.cursor]
            info = self.by_id[image_id]
            frame = cv2.imread(str(self.images_dir / info["file_name"]))
            if frame is None:
                self.cursor += 1
                continue
            scale = self.view_w / frame.shape[1]
            base = cv2.resize(frame, (self.view_w, int(frame.shape[0] * scale)))

            while True:
                if self.pending_delete:
                    self.delete_at(image_id, self.pending_delete, scale)
                    self.pending_delete = None
                if self.rect and self.drag_start is None:
                    self.add(image_id, self.rect, scale)
                    self.rect = None

                view = base.copy()
                anns = self.anns_of(image_id)
                for a in anns:
                    x, y, w, h = (v * scale for v in a["bbox"])
                    color = COLORS[a["category_id"]]
                    cv2.rectangle(view, (int(x), int(y)), (int(x + w), int(y + h)), color, 2)
                    tag = NAMES[a["category_id"]][:3]
                    if a.get("source") == "manual":
                        tag += "*"
                    cv2.putText(view, tag, (int(x) + 4, max(16, int(y) - 6)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1)
                if self.rect:
                    x1, y1, x2, y2 = self.rect
                    cv2.rectangle(view, (x1, y1), (x2, y2), DRAW_COLOR, 2)

                nb = sum(1 for a in anns if a["category_id"] == BOX_ID)
                npal = sum(1 for a in anns if a["category_id"] == PALLET_ID)
                header = (f"[{self.cursor + 1}/{len(self.ids)}] {info['file_name']}  "
                          f"box{nb} pal{npal}   그리기:{NAMES[self.cls]}   수정{self.changed}")
                cv2.rectangle(view, (0, 0), (view.shape[1], 30), (30, 30, 30), -1)
                cv2.putText(view, header, (8, 21), cv2.FONT_HERSHEY_SIMPLEX, 0.55,
                            COLORS[self.cls], 1)
                cv2.imshow("review", view)

                key = cv2.waitKey(20) & 0xFF
                if key in (ord("q"), 27):
                    self.save(coco_path)
                    cv2.destroyAllWindows()
                    return
                if key == ord("1"):
                    self.cls = BOX_ID
                elif key == ord("2"):
                    self.cls = PALLET_ID
                elif key == ord("r"):
                    self.rect = None
                elif key == ord("s"):
                    self.save(coco_path)
                elif key in (ord("n"), ord(" ")):
                    self.cursor += 1
                    break
                elif key == ord("b"):
                    self.cursor = max(self.cursor - 1, 0)
                    break
        self.save(coco_path)
        cv2.destroyAllWindows()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="평가셋 라벨 검수")
    parser.add_argument("--coco", type=Path, required=True)
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--ids", help="쉼표로 구분한 image_id (기본: 전체)")
    parser.add_argument("--start", help="이 파일번호부터 시작 (예: 0057)")
    parser.add_argument("--view-width", type=int, default=1400)
    args = parser.parse_args(argv)

    coco = json.loads(args.coco.read_text(encoding="utf-8"))
    if args.ids:
        ids = [int(v) for v in args.ids.replace(" ", "").split(",") if v]
    else:
        ids = [img["id"] for img in coco["images"]]
    if args.start:
        for i, iid in enumerate(ids):
            name = next(im["file_name"] for im in coco["images"] if im["id"] == iid)
            if name.rsplit("_", 1)[-1].split(".")[0] == args.start:
                ids = ids[i:]
                break

    print(f"{len(ids)}장 검수 — 드래그 추가 / 우클릭 삭제 / 1=box 2=pallet / "
          f"N 다음 B 이전 / S 저장 / Q 종료")
    Reviewer(coco, args.images, ids, args.view_width).run(args.coco)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
