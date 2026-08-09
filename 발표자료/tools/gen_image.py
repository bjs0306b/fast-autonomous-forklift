"""발표 덱 일러스트·아이콘 생성 (Google AI Studio / Gemini).

사용 (레포 루트에서, ai_env):
    conda run -n ai_env python 발표자료/tools/gen_image.py --id D-2 --n 4
    conda run -n ai_env python 발표자료/tools/gen_image.py --id C-1 --n 3 --tag v3
    conda run -n ai_env python 발표자료/tools/gen_image.py --list

프롬프트는 `prompts.py`가 정본이다. 이 파일은 호출·저장만 한다.

산출: `발표자료/images/<분류>/<ID>_<tag>_<a..>.png`
  - **기존 파일을 덮어쓰지 않는다.** 뽑아놓고 비교해서 고르는 흐름이라,
    덮어쓰면 방금 마음에 들었던 판본이 사라진다.
  - 채택한 것은 손으로 `<ID>.png` 로 복사해 쓴다(원본은 남긴다).

⚠️ API 키는 레포 루트 `.env` 의 `GEMINI_API_KEY` 에서 읽는다. `.env` 는
   `.gitignore` 에 있다. **키를 코드·로그·커밋에 남기지 않는다** — 이 스크립트도
   키 길이만 찍고 값은 출력하지 않는다.
"""

from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import prompts as P  # noqa: E402

_ROOT = Path(__file__).resolve().parents[2]
_IMAGES = _ROOT / "발표자료" / "images"

# 지시 이행이 이 작업의 전부다 — 화질보다 "시킨 대로 그렸는가"가 판정 기준이라
# pro 를 기본으로 둔다. 첫 판들이 실패한 이유가 전부 지시 무시였다
# (색 규칙 뒤집힘·화살표 방향 반대·좌측 여백 무시).
# 미세 반복은 `--model gemini-3.1-flash-image` 로 싸게 돌린다.
DEFAULT_MODEL = "gemini-3-pro-image"


def load_key() -> str:
    env = _ROOT / ".env"
    if not env.exists():
        sys.exit(f".env 가 없습니다: {env}")
    for line in io.open(env, encoding="utf-8"):
        line = line.strip()
        if line.startswith("GEMINI_API_KEY="):
            key = line.split("=", 1)[1].strip().strip('"').strip("'")
            if key:
                return key
    sys.exit(".env 에 GEMINI_API_KEY 가 없습니다 (값은 채팅에 붙이지 마세요)")


def generate(image_id: str, n: int, tag: str, model: str) -> list[Path]:
    from google import genai
    from google.genai import types

    spec = P.PROMPTS[image_id]
    prompt = P.build(image_id)
    out_dir = _IMAGES / spec["dir"]
    out_dir.mkdir(parents=True, exist_ok=True)

    client = genai.Client(api_key=load_key())
    saved: list[Path] = []

    for i in range(n):
        suffix = chr(ord("a") + i)
        path = out_dir / f"{image_id}_{tag}_{suffix}.png"
        if path.exists():
            print(f"  건너뜀 (이미 있음): {path.name}")
            continue

        # 같은 프롬프트를 n 번 돌려 서로 다른 판본을 얻는다. 시드를 안 고정하는
        # 것이 의도다 — 고르기 위해 뽑는 것이라 다양성이 필요하다.
        resp = client.models.generate_content(
            model=model,
            contents=prompt,
            config=types.GenerateContentConfig(
                response_modalities=["IMAGE"],
                image_config=types.ImageConfig(aspect_ratio=spec["aspect"]),
            ),
        )

        data = None
        for cand in resp.candidates or []:
            for part in cand.content.parts or []:
                if getattr(part, "inline_data", None) and part.inline_data.data:
                    data = part.inline_data.data
                    break
            if data:
                break

        if not data:
            # 빈 응답을 조용히 넘기면 "몇 장 생성됨" 로그만 보고 다 된 줄 안다.
            print(f"  ❌ {suffix}: 이미지가 안 왔다 "
                  f"(안전 필터·쿼터 의심 — finish_reason 확인)", file=sys.stderr)
            for cand in resp.candidates or []:
                print(f"     finish_reason={cand.finish_reason}", file=sys.stderr)
            continue

        path.write_bytes(data)
        print(f"  ✅ {path.relative_to(_ROOT)}  ({len(data) // 1024} KB)")
        saved.append(path)

    return saved


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--id", help="prompts.py 의 이미지 코드 (C-1 · D-1 · D-2)")
    ap.add_argument("--n", type=int, default=3, help="뽑을 장수 (기본 3 — 골라 쓴다)")
    ap.add_argument("--tag", default="v1", help="판본 태그. 파일명에 들어간다")
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--list", action="store_true", help="등록된 이미지 코드 보기")
    args = ap.parse_args(argv)

    if args.list or not args.id:
        print("등록된 이미지:")
        for k, v in P.PROMPTS.items():
            print(f"  {k:5s} [{v['aspect']:>4s}] {v['dir']:12s} — 슬라이드 {v['slide']}")
        return 0

    if args.id not in P.PROMPTS:
        sys.exit(f"모르는 코드: {args.id} (--list 로 확인)")

    print(f"[{args.id}] {args.model} · {args.n}장 · 태그 {args.tag}")
    saved = generate(args.id, args.n, args.tag, args.model)
    print(f"\n{len(saved)}장 생성. 골라서 `{args.id}.png` 로 복사해 쓰세요.")
    return 0 if saved else 1


if __name__ == "__main__":
    sys.exit(main())
