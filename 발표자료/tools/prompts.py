"""발표 덱 생성 이미지 프롬프트.

**여기가 프롬프트의 정본이다.** 채팅으로 주고받지 않고 이 파일만 고친다 —
어떤 프롬프트로 어떤 파일이 나왔는지 추적이 돼야 재생성할 때 헤매지 않는다.

규칙 (`발표자료/원고.md` §이미지 명세):
  - A(실물)는 **절대 생성하지 않는다.** 실물이 있는 프로젝트라 생성 이미지를
    실물처럼 쓰면 질문 한 번에 발표 전체가 무너진다.
  - B(도표)도 생성하지 않는다. 정확한 기하 관계가 요점이라 이미지 모델이 못 맞춘다.
  - 여기 있는 것은 C(개념 일러스트)·D(아이콘)뿐이다.

⚠️ **한글을 그리게 하지 않는다.** 반드시 깨진다. 글자는 전부 HTML에서 얹는다.
"""

from __future__ import annotations

# 전 이미지 공통. **바꾸면 전부 다시 뽑아야 한다** — 화풍이 섞이면 아마추어로 보인다.
#
# 밝기를 한 번 올린 이력이 있다(2026-08-06): 첫 판은 배경 #15171a에 어두운
# 회색 선이라 모니터에서만 예뻤다. **프로젝터는 대비가 낮아 어두운 저대비
# 디테일을 통째로 뭉갠다** — 그래서 배경을 한 단계 밝히고 선 색을 지정했다.
STYLE = """Flat vector illustration, isometric perspective, minimal geometric shapes.
Palette: warm amber (#f0a92c) as the only accent, light slate blue-grey (#8fa3b8)
for structure lines, on a dark background (#1a1d21).
IMPORTANT: keep line work bright and high-contrast against the background —
this will be projected, and dark low-contrast detail disappears on a projector.
Clean uniform line weight, no gradients, no shadows, no glow.
NO text, NO letters, NO numbers, NO labels, NO watermark, no signature."""

# 채택된 이미지는 여기 코드 그대로 `images/<dir>/<id>.png` 로 들어간다.
PROMPTS: dict[str, dict] = {
    # ── C: 개념 일러스트 ────────────────────────────────────────────────
    "C-1": {
        "dir": "C-일러스트",
        "slide": "2 — 지게차는 위험해서, 늘릴 수가 없다",
        "aspect": "16:9",
        # 왼쪽 45%를 비우라고 대문자로 못 박은 이유: 첫 판은 프레임 전체가
        # 디테일로 차서 **주장 한 줄 + 불릿 4개를 얹을 자리가 없었다.**
        # 구도를 슬라이드 레이아웃에 맞춰 생성하는 편이, 슬라이드를 그림에
        # 맞춰 바꾸는 것보다 싸다.
        "body": """A logistics warehouse aisle seen from a slightly elevated angle.
COMPOSITION IS CRITICAL: the left 45% of the frame must be nearly empty —
open concrete floor and dark air, no racking, no objects, generous negative space.
All the racking is on the RIGHT half of the frame only, receding in perspective.
A single counterbalance forklift sits in the right-center, carrying one stacked
pallet raised high, with a clearly visible human operator seated in the cab —
draw the operator in a contrasting tone so a person is unmistakably driving it.
Keep it simple and uncluttered — a few tall rack bays, not a dense grid.
Leave the bottom-right corner visually quiet and empty.
Wide 16:9.""",
    },
    # ── D: 아이콘 ──────────────────────────────────────────────────────
    "D-1": {
        "dir": "D-아이콘",
        "slide": "3 — 상자 하나의 여정 (4단계). 크게 한 번만 쓴다",
        "aspect": "16:9",
        # ⚠️ 색 규칙을 맨 앞에 올린 이유: 첫 판에서 ②의 빈 슬롯과 ④의 포크가
        # **회색으로 나왔다.** 둘 다 그 아이콘의 주인공인데 물러나는 색이라
        # 작게 줄이면 얼룩처럼 보였다. 규칙을 뒤에 적으면 무시된다.
        #
        # ⚠️ 네 개를 **한 장에** 그리게 한다. 따로 4번 돌리면 선 굵기·시점·
        # 색이 전부 달라져 세트가 안 된다.
        "body": """A set of exactly four simple icons in a single horizontal row, evenly spaced,
identical style, identical line weight, identical size, ALL FOUR in the same
isometric three-quarter viewpoint (no front-flat views).

COLOR RULE — this is the most important instruction:
the subject of the action is amber (#f0a92c); surrounding context is muted
slate grey (#5a6570). Never make the focal element grey.

1) MEASURING: an amber cardboard box with an amber caliper closed around it.
2) CHOOSING A SLOT: a warehouse rack drawn entirely in muted grey, with exactly
   ONE empty shelf slot glowing solid amber. The amber slot must be the brightest
   thing in the icon.
3) DRIVING: an amber forklift, with a grey dashed path curving AHEAD of it in the
   direction it faces; the arrowhead points FORWARD, away from the forklift.
4) INSERTING: a grey pallet seen isometrically from the front, with its two front
   openings clearly visible as dark rectangular holes, and two amber forks pushed
   INTO those two holes. The penetration must be obvious.

Icons only, no background scene, no frame, no connecting line between them.
Leave the bottom-right corner completely empty.
Wide 16:9.""",
    },
    "D-2": {
        "dir": "D-아이콘",
        "slide": "전 슬라이드 — 진행 레일의 상자. 축의 주인공",
        "aspect": "1:1",
        # 이 아이콘은 발표 내내 화면에 있고, 슬라이드 6에서 작아졌다가
        # 15에서 다시 커진다. **작은 크기 가독성이 유일한 판정 기준이다** —
        # 크게 보고 고르면 레일에서 뭉개진다.
        "body": """A single simple cardboard box icon, front-facing, slightly three-quarter view,
sitting on a small wooden pallet. Very simple silhouette, instantly readable
even at 32 pixels — no fine interior detail, no thin lines, no small parts.
The box is solid amber; the pallet is muted slate grey.
Centered, isolated, generous empty margin around it, nothing else in frame.
Square 1:1.""",
    },
}


def build(image_id: str) -> str:
    """`STYLE`을 붙인 최종 프롬프트."""
    spec = PROMPTS[image_id]
    return f"{spec['body']}\n\n{STYLE}"
