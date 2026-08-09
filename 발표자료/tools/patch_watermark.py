"""생성 이미지 우하단 워터마크 제거.

Gemini 웹 UI가 생성물 우하단에 반짝이 마크를 찍는다. **최종 발표물에 생성 도구
로고가 박혀 있으면 안 된다.**

방법은 "지우기"가 아니라 **같은 이미지의 깨끗한 부분으로 덮기**다. 배경이 완전
평면이 아니라(격자선·비네트) 단색으로 칠하면 그 자리만 티가 난다.

사용:
    python 발표자료/tools/patch_watermark.py IN.png OUT.png
    python 발표자료/tools/patch_watermark.py IN.png OUT.png --box 2463 1210 100 100 --from-dy 140

`--box` 를 안 주면 우하단에서 자동으로 잡는다(이미지 크기 비례).
**결과는 반드시 눈으로 확인한다** — 덮은 자리가 배경과 다르면 좌표를 손으로 준다.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image


def patch(src: Path, dst: Path, box: tuple[int, int, int, int] | None,
          from_dy: int, from_dx: int) -> None:
    im = Image.open(src).convert("RGB")
    w, h = im.size

    if box is None:
        # 우하단 마크는 대체로 짧은 변의 6~8% 안쪽에 있다. 넉넉히 잡는다 —
        # 조금 더 덮는 것은 무해하고, 덜 덮으면 마크 꼭지가 남는다.
        side = int(min(w, h) * 0.085)
        x = w - int(min(w, h) * 0.13)
        y = h - int(min(w, h) * 0.13)
        box = (x, y, side, side)

    x, y, bw, bh = box
    sx, sy = x + from_dx, y + from_dy

    if not (0 <= sx and sx + bw <= w and 0 <= sy and sy + bh <= h):
        sys.exit(f"복사 원본이 이미지 밖입니다: ({sx},{sy})+{bw}x{bh} vs {w}x{h}. "
                 f"--from-dy / --from-dx 를 조정하세요.")

    patch_img = im.crop((sx, sy, sx + bw, sy + bh))
    im.paste(patch_img, (x, y))
    im.save(dst)
    print(f"{src.name} → {dst.name}")
    print(f"  덮은 곳 ({x},{y}) {bw}x{bh}  ←  가져온 곳 ({sx},{sy})")
    print("  ⚠️ 결과를 눈으로 확인하세요. 자국이 보이면 --box / --from-dy 를 직접 주세요.")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", type=Path)
    ap.add_argument("dst", type=Path)
    ap.add_argument("--box", nargs=4, type=int, metavar=("X", "Y", "W", "H"))
    ap.add_argument("--from-dy", type=int, default=0, help="복사 원본의 y 오프셋")
    ap.add_argument("--from-dx", type=int, default=0, help="복사 원본의 x 오프셋")
    a = ap.parse_args()
    patch(a.src, a.dst, tuple(a.box) if a.box else None, a.from_dy, a.from_dx)
    return 0


if __name__ == "__main__":
    sys.exit(main())
