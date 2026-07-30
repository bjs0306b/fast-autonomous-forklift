"""온보드 라벨러 — `pallet`·`hole`을 빈 화면부터 그린다 (S15P11A304-144).

`fixlabel`은 파렛트 하나만 고치는 도구였다. 여기는 **한 프레임에 pallet 1개 +
hole 최대 4개**를 그려야 하고 208장을 연속으로 처리하므로, 인스턴스 추가·되돌리기·
자동 저장·이어하기가 필요하다. `box`는 그리지 않는다 — exp8 프리라벨이 채운다
(`onboard-finetune-runbook.md` §1).

    python -m dataset.label_onboard \
        --images data/processed/cvat_onboard_pass1 \
        --out data/labels/onboard_cvat_pallet_hole.json

조작:
    드래그        현재 클래스로 박스 추가 (마우스 떼면 바로 확정)
    1 / 2         클래스 전환 — 1=pallet, 2=hole
    U             마지막 박스 취소        C  이 프레임 전부 지우기
    SPACE / ENTER 저장하고 다음          B  이전
    G             프레임 번호로 이동
    S             지금 저장              Q / ESC  저장하고 종료

구멍이 작아 경계가 안 보이면 ``--view-width``를 올린다(예: 1920). 화면 전체가
확대되므로 별도 돋보기 창 없이 같은 효과가 난다.

**진행 상황은 `<out>.progress.json`에 따로 남긴다.** "아직 안 본 프레임"과
"보고 나서 라벨이 없다고 판정한 프레임"은 다르다 — 네거티브(구간 8) 7장이 후자이고,
이걸 구분 못 하면 다시 열었을 때 어디까지 했는지 알 수 없다.

화면 문구는 전부 ASCII다. `cv2.putText`는 한글을 못 그려서 물음표가 된다.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402

from dataset.coco import frame_number  # noqa: E402

CLASSES = ["box", "pallet", "hole"]
PALLET_ID, HOLE_ID = 2, 3
COLOR = {PALLET_ID: (60, 140, 255), HOLE_ID: (60, 60, 240)}
NAME = {PALLET_ID: "pallet", HOLE_ID: "hole"}
DRAW_COLOR = (60, 240, 240)
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}

MIN_HOLE_H = 16        # 가이드 §3-4: 원본 세로 16px 미만은 라벨 대상이 아니다
MIN_HOLE_ASPECT = 1.5  # 가이드 §4: 정상 약 3.7:1
MAX_HOLES = 4          # 가이드 §4: 구조상 최대 4


def check_frame(anns: list[dict]) -> list[str]:
    """가이드 §4 sanity check — 그리는 중에 바로 보여준다(막지는 않는다)."""
    warn = []
    holes = [a["bbox"] for a in anns if a["category_id"] == HOLE_ID]
    pallets = [a["bbox"] for a in anns if a["category_id"] == PALLET_ID]

    if len(pallets) > 1:
        warn.append(f"pallet {len(pallets)} (expect 1)")
    if holes and not pallets:
        warn.append("hole without pallet")
    if len(holes) > MAX_HOLES:
        warn.append(f"hole {len(holes)} > {MAX_HOLES}")
    for hb in holes:
        if pallets and not any(_inside(hb, pb) for pb in pallets):
            warn.append("hole outside pallet")
        if hb[3] and hb[2] / hb[3] < MIN_HOLE_ASPECT:
            warn.append(f"hole aspect {hb[2] / hb[3]:.1f} (normal ~3.7)")
        if hb[3] < MIN_HOLE_H:
            warn.append(f"hole height {hb[3]:.0f}px < {MIN_HOLE_H}")
    return warn


def _inside(inner, outer, slack: int = 8) -> bool:
    ix, iy, iw, ih = inner
    ox, oy, ow, oh = outer
    return (ix >= ox - slack and iy >= oy - slack
            and ix + iw <= ox + ow + slack and iy + ih <= oy + oh + slack)


def build_coco(files: list[str], sizes: dict[str, tuple[int, int]],
               anns_by_file: dict[str, list[dict]]) -> dict:
    """파일명 → 어노테이션 목록을 COCO로 굳힌다. id는 1부터 다시 매긴다."""
    images, annotations = [], []
    for i, name in enumerate(files, start=1):
        w, h = sizes.get(name, (0, 0))
        images.append({"id": i, "file_name": name, "width": w, "height": h})
        for a in anns_by_file.get(name, []):
            annotations.append({
                "id": len(annotations) + 1, "image_id": i,
                "category_id": a["category_id"], "bbox": a["bbox"],
                "area": round(a["bbox"][2] * a["bbox"][3], 1), "iscrowd": 0,
                "source": "manual",
            })
    return {"images": images, "annotations": annotations,
            "categories": [{"id": i + 1, "name": n} for i, n in enumerate(CLASSES)]}


def load_existing(out: Path) -> dict[str, list[dict]]:
    """이어하기 — 이미 저장된 라벨을 파일명 기준으로 되읽는다."""
    if not out.exists():
        return {}
    coco = json.loads(out.read_text(encoding="utf-8"))
    name_of = {im["id"]: im["file_name"] for im in coco["images"]}
    got: dict[str, list[dict]] = {}
    for a in coco["annotations"]:
        got.setdefault(name_of[a["image_id"]], []).append(
            {"category_id": a["category_id"], "bbox": a["bbox"]})
    return got


class Labeler:
    def __init__(self, files: list[Path], out: Path, view_w: int):
        self.files = files
        self.out = out
        self.progress_path = out.with_suffix(out.suffix + ".progress.json")
        self.view_w = view_w
        self.anns = load_existing(out)
        # 크기는 **처음에 전부 읽는다.** 본 프레임만 채우면 중간에 종료했을 때
        # 안 본 프레임의 width/height가 0으로 저장되고, 그 json을 받는
        # merge_coco·split_onboard가 조용히 망가진다. 읽으면서 깨진 파일도 걸러진다.
        self.sizes: dict[str, tuple[int, int]] = {}
        self.unreadable: list[str] = []
        for p in files:
            img = cv2.imread(str(p))
            if img is None:
                self.unreadable.append(p.name)
                continue
            self.sizes[p.name] = (img.shape[1], img.shape[0])
        self.done: set[str] = set()
        if self.progress_path.exists():
            self.done = set(json.loads(
                self.progress_path.read_text(encoding="utf-8"))["done"])
        self.cls = PALLET_ID
        self.cursor = self._first_todo()
        self.drag_start: tuple[int, int] | None = None
        self.rect: tuple[int, int, int, int] | None = None

    def _first_todo(self) -> int:
        for i, p in enumerate(self.files):
            if p.name not in self.done:
                return i
        return 0

    def on_mouse(self, event, x, y, _flags, _param):
        if event == cv2.EVENT_LBUTTONDOWN:
            self.drag_start = (x, y)
            self.rect = None
        elif event == cv2.EVENT_MOUSEMOVE and self.drag_start:
            self.rect = (*self.drag_start, x, y)
        elif event == cv2.EVENT_LBUTTONUP and self.drag_start:
            self.rect = (*self.drag_start, x, y)
            self.drag_start = None
            self.commit()

    def commit(self) -> None:
        """마우스를 떼면 바로 확정한다 — 208장 × 최대 5개라 확인키를 두면 손이 두 배다."""
        if not self.rect:
            return
        x1, y1, x2, y2 = self.rect
        self.rect = None
        scale = self.scale
        x, y = min(x1, x2) / scale, min(y1, y2) / scale
        w, h = abs(x2 - x1) / scale, abs(y2 - y1) / scale
        if w < 3 or h < 3:            # 실수로 클릭만 한 경우
            return
        name = self.files[self.cursor].name
        self.anns.setdefault(name, []).append({
            "category_id": self.cls,
            "bbox": [round(x, 1), round(y, 1), round(w, 1), round(h, 1)],
        })

    def save(self) -> None:
        names = [p.name for p in self.files]
        self.out.parent.mkdir(parents=True, exist_ok=True)
        self.out.write_text(json.dumps(
            build_coco(names, self.sizes, self.anns), ensure_ascii=False),
            encoding="utf-8")
        self.progress_path.write_text(json.dumps(
            {"done": sorted(self.done)}, ensure_ascii=False), encoding="utf-8")

    def run(self) -> None:
        win = "label_onboard"
        cv2.namedWindow(win)
        cv2.setMouseCallback(win, self.on_mouse)

        while True:
            if not (0 <= self.cursor < len(self.files)):
                break
            path = self.files[self.cursor]
            frame = cv2.imread(str(path))
            if frame is None:
                self.cursor += 1
                continue
            self.sizes[path.name] = (frame.shape[1], frame.shape[0])
            self.scale = self.view_w / frame.shape[1]
            base = cv2.resize(frame, (self.view_w,
                                      int(frame.shape[0] * self.scale)))

            advance = 0
            while advance == 0:
                view = base.copy()
                anns = self.anns.get(path.name, [])
                for a in anns:
                    x, y, w, h = (v * self.scale for v in a["bbox"])
                    cv2.rectangle(view, (int(x), int(y)), (int(x + w), int(y + h)),
                                  COLOR[a["category_id"]], 2)
                if self.rect:
                    x1, y1, x2, y2 = self.rect
                    cv2.rectangle(view, (x1, y1), (x2, y2), DRAW_COLOR, 2)

                n_p = sum(1 for a in anns if a["category_id"] == PALLET_ID)
                n_h = sum(1 for a in anns if a["category_id"] == HOLE_ID)
                seg = frame_number(path.name)
                head = (f"[{self.cursor + 1}/{len(self.files)}] {path.name}  "
                        f"frame {seg}  |  DRAW: {NAME[self.cls].upper()}  |  "
                        f"pallet {n_p}  hole {n_h}  |  done {len(self.done)}")
                cv2.rectangle(view, (0, 0), (view.shape[1], 38), (30, 30, 30), -1)
                cv2.putText(view, head, (8, 25), cv2.FONT_HERSHEY_SIMPLEX, 0.52,
                            (240, 240, 240), 1)
                warns = check_frame([{"category_id": a["category_id"],
                                      "bbox": a["bbox"]} for a in anns])
                for i, w_ in enumerate(warns[:3]):
                    cv2.putText(view, "! " + w_, (8, view.shape[0] - 12 - i * 22),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.55, (60, 60, 255), 2)
                cv2.imshow(win, view)

                key = cv2.waitKey(20) & 0xFF
                if key == 255:
                    continue
                if key in (ord("q"), 27):
                    # 현재 프레임은 완료로 표시하지 않는다 — 다음에 여기서 이어야 한다.
                    self.save()
                    cv2.destroyAllWindows()
                    return
                if key == ord("1"):
                    self.cls = PALLET_ID
                elif key == ord("2"):
                    self.cls = HOLE_ID
                elif key == ord("u"):
                    if anns:
                        anns.pop()
                elif key == ord("c"):
                    self.anns[path.name] = []
                elif key == ord("s"):
                    self.save()
                elif key == ord("b"):
                    advance = -1
                elif key in (32, 13, 10):            # SPACE / ENTER
                    self.done.add(path.name)
                    self.save()                      # 프레임마다 저장 — 몇 시간짜리 작업이다
                    advance = 1
                elif key == ord("g"):
                    n = input("frame number: ").strip()
                    if n.isdigit():
                        tgt = next((i for i, p in enumerate(self.files)
                                    if frame_number(p.name) == int(n)), None)
                        if tgt is not None:
                            self.cursor = tgt
                            advance = 99
                        else:
                            print(f"  프레임 {n}은 이 태스크에 없습니다")

            if advance in (1, -1):
                self.cursor += advance
                self.cursor = max(self.cursor, 0)
        self.save()
        cv2.destroyAllWindows()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="온보드 pallet·hole 라벨러")
    ap.add_argument("--images", type=Path, required=True, help="라벨할 이미지 폴더")
    ap.add_argument("--out", type=Path,
                    default=Path("data/labels/onboard_cvat_pallet_hole.json"))
    ap.add_argument("--view-width", type=int, default=1280)
    a = ap.parse_args(argv)

    files = sorted(p for p in a.images.iterdir()
                   if p.suffix.lower() in IMAGE_SUFFIXES)
    if not files:
        print(f"이미지가 없습니다: {a.images}", file=sys.stderr)
        return 1

    print(f"이미지 {len(files)}장 크기 확인 중...")
    lab = Labeler(files, a.out, a.view_width)
    if lab.unreadable:
        print(f"⚠️ 읽을 수 없는 파일 {len(lab.unreadable)}장: {lab.unreadable[:3]}")
    print(f"{len(files)}장 / 완료 {len(lab.done)}장 — {lab.cursor + 1}번째부터 시작")
    print("드래그=박스 추가  1=pallet 2=hole  U=취소  C=전부지우기  "
          "SPACE=다음  B=이전  G=이동  S=저장  Q=종료")
    lab.run()

    labeled = sum(1 for v in lab.anns.values() if v)
    n_p = sum(1 for v in lab.anns.values() for x in v if x["category_id"] == PALLET_ID)
    n_h = sum(1 for v in lab.anns.values() for x in v if x["category_id"] == HOLE_ID)
    print(f"\n저장 → {a.out}")
    print(f"  완료 {len(lab.done)}/{len(files)}장 · 라벨 있는 프레임 {labeled}장")
    print(f"  pallet {n_p} · hole {n_h}")
    todo = len(files) - len(lab.done)
    if todo:
        print(f"  남은 {todo}장 — 같은 명령으로 이어서 하면 된다")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
