"""온보드 라벨러의 순수 로직 테스트 (S15P11A304-144).

UI 루프는 빼고, 검수 규칙·COCO 조립·이어하기만 본다.
"""

from __future__ import annotations

import json

from dataset.label_onboard import HOLE_ID, PALLET_ID, build_coco, check_frame, load_existing


def ann(cat: int, bbox: list[float]) -> dict:
    return {"category_id": cat, "bbox": bbox}


PALLET = ann(PALLET_ID, [300, 400, 700, 120])


# --- 검수 규칙 (가이드 §4) ---

def test_정상_프레임은_경고가_없다() -> None:
    anns = [PALLET, ann(HOLE_ID, [420, 440, 90, 25]), ann(HOLE_ID, [700, 440, 90, 25])]
    assert check_frame(anns) == []


def test_pallet_없이_hole만_있으면_경고() -> None:
    assert any("without pallet" in w
               for w in check_frame([ann(HOLE_ID, [420, 440, 90, 25])]))


def test_hole이_pallet_밖이면_경고() -> None:
    anns = [PALLET, ann(HOLE_ID, [50, 100, 90, 25])]
    assert any("outside pallet" in w for w in check_frame(anns))


def test_hole이_5개면_경고() -> None:
    anns = [PALLET] + [ann(HOLE_ID, [320 + i * 60, 440, 50, 25]) for i in range(5)]
    assert any("> 4" in w for w in check_frame(anns))


def test_종횡비가_정사각에_가까우면_경고() -> None:
    anns = [PALLET, ann(HOLE_ID, [420, 430, 30, 25])]      # 1.2:1
    assert any("aspect" in w for w in check_frame(anns))


def test_세로_16px_미만이면_경고() -> None:
    anns = [PALLET, ann(HOLE_ID, [420, 440, 90, 12])]
    assert any("height" in w for w in check_frame(anns))


def test_pallet이_두_개면_경고() -> None:
    assert any("expect 1" in w for w in check_frame([PALLET, PALLET]))


# --- COCO 조립 ---

def test_라벨_없는_프레임도_이미지로_남는다() -> None:
    """네거티브는 '라벨 없음'이 정답이다 — 빠지면 안 된다."""
    coco = build_coco(["a_0001.jpg", "n_0317.jpg"],
                      {"a_0001.jpg": (1280, 800), "n_0317.jpg": (1280, 800)},
                      {"a_0001.jpg": [PALLET]})

    assert [im["file_name"] for im in coco["images"]] == ["a_0001.jpg", "n_0317.jpg"]
    assert len(coco["annotations"]) == 1


def test_category는_box_pallet_hole_순서다() -> None:
    coco = build_coco(["a_0001.jpg"], {"a_0001.jpg": (1280, 800)}, {})
    assert coco["categories"] == [{"id": 1, "name": "box"},
                                  {"id": 2, "name": "pallet"},
                                  {"id": 3, "name": "hole"}]


def test_id가_1부터_연속이고_area가_채워진다() -> None:
    coco = build_coco(["a_0001.jpg"], {"a_0001.jpg": (1280, 800)},
                      {"a_0001.jpg": [PALLET, ann(HOLE_ID, [420, 440, 90, 25])]})

    assert [x["id"] for x in coco["annotations"]] == [1, 2]
    assert coco["annotations"][1]["area"] == 2250
    assert all(x["image_id"] == 1 for x in coco["annotations"])


# --- 이어하기 ---

def test_저장된_라벨을_파일명으로_되읽는다(tmp_path) -> None:
    out = tmp_path / "labels.json"
    coco = build_coco(["a_0001.jpg", "a_0002.jpg"],
                      {"a_0001.jpg": (1280, 800), "a_0002.jpg": (1280, 800)},
                      {"a_0002.jpg": [PALLET, ann(HOLE_ID, [420, 440, 90, 25])]})
    out.write_text(json.dumps(coco), encoding="utf-8")

    got = load_existing(out)

    assert set(got) == {"a_0002.jpg"}
    assert [a["category_id"] for a in got["a_0002.jpg"]] == [PALLET_ID, HOLE_ID]


def test_파일이_없으면_빈_상태로_시작한다(tmp_path) -> None:
    assert load_existing(tmp_path / "none.json") == {}
