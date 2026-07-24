"""라벨 수정기 — 자동 라벨이 못 한 프레임만 손으로 그린다.

자동 라벨(``dataset.pallet_autolabel``)이 248/301을 처리했고, 남은 것은 파렛트를
사람이 그려야 하는 소수다. 34장 때문에 이미지 205MB를 CVAT에 올리는 대신 여기서 끝낸다.

    python -m dataset.fixlabel --coco data/processed/rig_labeled.json \
        --images data/raw/rig/20260724 --ids 27,28,36,62,64,65,66,67

조작:
    드래그        파렛트 박스 그리기 (기존 파렛트 라벨은 대체된다)
    ENTER         저장하고 다음        N  저장 없이 다음      P  이전
    R             현재 그림 취소       D  이 프레임 폐기(이미지·라벨 삭제 표시)
    Q / ESC       종료 (지금까지 수정분 저장)

원본은 ``<파일명>.bak``으로 백업한다. 박스(초록)는 건드리지 않고 파렛트(주황)만 다룬다.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402

PALLET_ID = 2
BOX_COLOR = (80, 200, 80)
PALLET_COLOR = (60, 140, 255)
DRAW_COLOR = (60, 240, 240)


class Editor:
    def __init__(self, coco: dict, images_dir: Path, ids: list[int], view_w: int):
        self.coco = coco
        self.images_dir = images_dir
        self.ids = ids
        self.view_w = view_w
        self.by_id = {img["id"]: img for img in coco["images"]}
        self.cursor = 0
        self.drag_start: tuple[int, int] | None = None
        self.rect: tuple[int, int, int, int] | None = None   # 화면 좌표
        self.junk: set[int] = set()
        self.edited = 0

    # --- 좌표 변환 (화면 → 원본) ---
    def to_original(self, rect, scale):
        x1, y1, x2, y2 = rect
        x, y = min(x1, x2) / scale, min(y1, y2) / scale
        w, h = abs(x2 - x1) / scale, abs(y2 - y1) / scale
        return [round(x, 1), round(y, 1), round(w, 1), round(h, 1)]

    def on_mouse(self, event, x, y, flags, _param):
        if event == cv2.EVENT_LBUTTONDOWN:
            self.drag_start = (x, y)
            self.rect = None
        elif event == cv2.EVENT_MOUSEMOVE and self.drag_start:
            self.rect = (*self.drag_start, x, y)
        elif event == cv2.EVENT_LBUTTONUP and self.drag_start:
            self.rect = (*self.drag_start, x, y)
            self.drag_start = None

    def annotations_for(self, image_id: int) -> list[dict]:
        return [a for a in self.coco["annotations"] if a["image_id"] == image_id]

    def save_rect(self, image_id: int, scale: float) -> bool:
        if not self.rect:
            return False
        bbox = self.to_original(self.rect, scale)
        if bbox[2] < 10 or bbox[3] < 10:      # 실수로 클릭만 한 경우
            return False
        self.coco["annotations"] = [
            a for a in self.coco["annotations"]
            if not (a["image_id"] == image_id and a["category_id"] == PALLET_ID)
        ]
        next_id = max((a["id"] for a in self.coco["annotations"]), default=0) + 1
        self.coco["annotations"].append({
            "id": next_id, "image_id": image_id, "category_id": PALLET_ID,
            "bbox": bbox, "area": round(bbox[2] * bbox[3], 1), "iscrowd": 0,
            "source": "manual",
        })
        self.edited += 1
        return True

    def run(self) -> None:
        cv2.namedWindow("fixlabel")
        cv2.setMouseCallback("fixlabel", self.on_mouse)

        while 0 <= self.cursor < len(self.ids):
            image_id = self.ids[self.cursor]
            info = self.by_id[image_id]
            frame = cv2.imread(str(self.images_dir / info["file_name"]))
            if frame is None:
                self.cursor += 1
                continue
            scale = self.view_w / frame.shape[1]
            view0 = cv2.resize(frame, (self.view_w,
                                       int(frame.shape[0] * scale)))
            for ann in self.annotations_for(image_id):
                x, y, w, h = (v * scale for v in ann["bbox"])
                color = PALLET_COLOR if ann["category_id"] == PALLET_ID else BOX_COLOR
                cv2.rectangle(view0, (int(x), int(y)), (int(x + w), int(y + h)), color, 2)

            while True:
                view = view0.copy()
                if self.rect:
                    x1, y1, x2, y2 = self.rect
                    cv2.rectangle(view, (x1, y1), (x2, y2), DRAW_COLOR, 2)
                status = (f"[{self.cursor + 1}/{len(self.ids)}] {info['file_name']}"
                          f"{'  (폐기 표시)' if image_id in self.junk else ''}")
                cv2.rectangle(view, (0, 0), (view.shape[1], 34), (30, 30, 30), -1)
                cv2.putText(view, status, (8, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.6,
                            (240, 240, 240), 1)
                cv2.imshow("fixlabel", view)

                key = cv2.waitKey(20) & 0xFF
                if key in (ord("q"), 27):
                    cv2.destroyAllWindows()
                    return
                if key in (13, 10):                      # ENTER
                    self.save_rect(image_id, scale)
                    self.rect = None
                    self.cursor += 1
                    break
                if key == ord("n"):
                    self.rect = None
                    self.cursor += 1
                    break
                if key == ord("p"):
                    self.rect = None
                    self.cursor = max(self.cursor - 1, 0)
                    break
                if key == ord("r"):
                    self.rect = None
                if key == ord("d"):
                    self.junk.add(image_id)
                    self.rect = None
                    self.cursor += 1
                    break
                if self.rect and self.drag_start is None:
                    continue     # 드래그 완료 — ENTER 대기
        cv2.destroyAllWindows()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="파렛트 라벨 수동 수정")
    parser.add_argument("--coco", type=Path, required=True)
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--ids", help="쉼표로 구분한 image_id. 없으면 파렛트 없는 전부")
    parser.add_argument("--view-width", type=int, default=1280)
    args = parser.parse_args(argv)

    coco = json.loads(args.coco.read_text(encoding="utf-8"))
    if args.ids:
        ids = [int(v) for v in args.ids.replace(" ", "").split(",") if v]
    else:
        has_pallet = {a["image_id"] for a in coco["annotations"]
                      if a["category_id"] == PALLET_ID}
        ids = [img["id"] for img in coco["images"] if img["id"] not in has_pallet]
    if not ids:
        print("수정할 프레임이 없습니다.")
        return 0

    print(f"{len(ids)}장 — 드래그로 파렛트를 그리고 ENTER. 도움말은 모듈 도크스트링.")
    editor = Editor(coco, args.images, ids, args.view_width)
    editor.run()

    if editor.junk:
        junk = editor.junk
        coco["images"] = [i for i in coco["images"] if i["id"] not in junk]
        coco["annotations"] = [a for a in coco["annotations"]
                               if a["image_id"] not in junk]
        print(f"폐기 {len(junk)}장 (json에서 제외 — 이미지 파일은 그대로 둔다)")

    backup = args.coco.with_suffix(args.coco.suffix + ".bak")
    if not backup.exists():
        shutil.copy2(args.coco, backup)
        print(f"원본 백업 → {backup}")
    args.coco.write_text(json.dumps(coco, ensure_ascii=False), encoding="utf-8")

    pallets = sum(1 for a in coco["annotations"] if a["category_id"] == PALLET_ID)
    print(f"수정 {editor.edited}장 저장 → {args.coco}")
    print(f"현재 파렛트 라벨 {pallets} / 이미지 {len(coco['images'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
