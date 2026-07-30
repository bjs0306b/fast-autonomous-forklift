"""온보드 버스트 라벨 계획·전파 테스트 (S15P11A304-144)."""

from __future__ import annotations

from dataset.onboard_bursts import audit, expand, frame_no, group_bursts, segment_of


def make_plan(bursts: list[tuple[list[str], bool]]) -> dict:
    """(프레임 목록, 전파여부) 로 계획 dict를 만든다."""
    out = []
    to_draw: list[str] = []
    for frames, propagate in bursts:
        out.append({"keyframe": frames[0], "frames": frames,
                    "segment": 1, "max_shift_px": 0.0, "propagate": propagate})
        to_draw.extend([frames[0]] if propagate else frames)
    return {"bursts": out, "to_draw": to_draw, "classes": ["box", "pallet", "hole"]}


def make_coco(annotated: dict[str, list[int]]) -> dict:
    """{파일명: [category_id, ...]} 로 CVAT export 모양의 COCO를 만든다."""
    images, anns = [], []
    for i, (name, cats) in enumerate(annotated.items(), start=1):
        images.append({"id": i, "file_name": name, "width": 1280, "height": 800})
        for cat in cats:
            anns.append({"id": len(anns) + 1, "image_id": i, "category_id": cat,
                         "bbox": [10, 20, 30, 8], "area": 240, "iscrowd": 0})
    return {"images": images, "annotations": anns,
            "categories": [{"id": 1, "name": "box"}, {"id": 2, "name": "pallet"},
                           {"id": 3, "name": "hole"}]}


# --- 전파 ---

def test_정지_버스트는_키프레임_라벨을_전체로_복사한다() -> None:
    plan = make_plan([(["a_0001.jpg", "a_0002.jpg", "a_0003.jpg"], True)])
    coco = make_coco({"a_0001.jpg": [2, 3, 3]})

    out, stats, warn = expand(plan, coco)

    assert stats["images"] == 3
    assert stats["annotations"] == 9          # 3장 × 3개
    assert stats["propagated"] == 2
    assert not warn


def test_움직임_버스트는_프레임별_라벨을_그대로_쓴다() -> None:
    plan = make_plan([(["b_0001.jpg", "b_0002.jpg"], False)])
    coco = make_coco({"b_0001.jpg": [2], "b_0002.jpg": [2, 3]})

    out, stats, warn = expand(plan, coco)

    assert stats["images"] == 2
    assert stats["annotations"] == 3          # 복사가 아니라 각자의 라벨
    assert stats["propagated"] == 0
    assert not warn


def test_네거티브는_라벨_없이_이미지만_남는다() -> None:
    """filter_empty_gt=False로 학습하므로 빈 프레임이 사라지면 안 된다."""
    plan = make_plan([(["n_0317.jpg", "n_0318.jpg"], True)])
    coco = make_coco({"n_0317.jpg": []})

    out, stats, warn = expand(plan, coco)

    assert stats["images"] == 2
    assert stats["annotations"] == 0
    assert stats["empty"] == 2


def test_그려야_할_프레임이_빠지면_경고한다() -> None:
    plan = make_plan([(["c_0001.jpg", "c_0002.jpg"], False)])
    coco = make_coco({"c_0001.jpg": [2]})     # 0002를 라벨러가 빼먹었다

    out, stats, warn = expand(plan, coco)

    assert stats["images"] == 1
    assert any("export에 없다" in w for w in warn)


def test_클래스_순서가_다르면_경고한다() -> None:
    plan = make_plan([(["d_0001.jpg"], True)])
    coco = make_coco({"d_0001.jpg": [1]})
    coco["categories"] = [{"id": 1, "name": "pallet"}, {"id": 2, "name": "box"},
                          {"id": 3, "name": "hole"}]

    _, _, warn = expand(plan, coco)

    assert any("클래스 순서" in w for w in warn)


def test_출력_id가_1부터_연속이다() -> None:
    plan = make_plan([(["e_0001.jpg", "e_0002.jpg"], True),
                      (["e_0003.jpg"], False)])
    coco = make_coco({"e_0001.jpg": [2, 3], "e_0003.jpg": [2]})

    out, _, _ = expand(plan, coco)

    assert [im["id"] for im in out["images"]] == [1, 2, 3]
    assert [x["id"] for x in out["annotations"]] == [1, 2, 3, 4, 5]


# --- 프레임 번호·구간 ---

def test_파일명에서_프레임_번호를_읽는다() -> None:
    assert frame_no("onb_20260729-163115_0358.jpg") == 358
    assert frame_no("onb_20260729-163115.jpg") is None


def test_구간_경계() -> None:
    assert segment_of(54) == 1
    assert segment_of(55) == 2
    assert segment_of(317) == 8          # 네거티브 시작
    assert segment_of(359) is None


# --- 버스트 묶기 ---

## --- 구간 감사 (box 기대치 대조) ---

def audit_coco(frames: dict[str, list[int]]) -> dict:
    """{파일명: [category_id, ...]} → COCO (box=1·pallet=2·hole=3)."""
    images, anns = [], []
    for i, (name, cats) in enumerate(frames.items(), start=1):
        images.append({"id": i, "file_name": name, "width": 1280, "height": 800})
        for cat in cats:
            anns.append({"id": len(anns) + 1, "image_id": i, "category_id": cat,
                         "bbox": [1, 2, 30, 8], "area": 240, "iscrowd": 0})
    return {"images": images, "annotations": anns,
            "categories": [{"id": 1, "name": "box"}, {"id": 2, "name": "pallet"},
                           {"id": 3, "name": "hole"}]}


def test_기대치_안이면_경고가_없다() -> None:
    # 구간 1 기대 box 1개/장
    coco = audit_coco({"o_0001.jpg": [1, 2, 3, 3], "o_0002.jpg": [1, 2, 3, 3]})

    rows, warn = audit(coco)

    assert rows[0]["segment"] == 1
    assert rows[0]["box_per_img"] == 1.0
    assert not warn


def test_box_과검출을_2배_기준으로_잡는다() -> None:
    """구간 1 기대 1개 — 장당 3개는 2배 초과."""
    coco = audit_coco({"o_0001.jpg": [1, 1, 1, 2], "o_0002.jpg": [1, 1, 1, 2]})

    rows, warn = audit(coco)

    assert rows[0]["verdict"] == "과검출 의심"
    assert any("2배 초과" in w for w in warn)


def test_네거티브_구간의_box는_즉시_경고한다() -> None:
    """구간 8은 프리라벨을 돌리지 않기로 했다 — 있으면 사고다."""
    coco = audit_coco({"o_0317.jpg": [1], "o_0318.jpg": []})

    rows, warn = audit(coco)

    seg8 = [r for r in rows if r["segment"] == 8][0]
    assert seg8["verdict"] == "네거티브에 box"
    assert any("네거티브인데 box" in w for w in warn)


def test_구간별로_따로_집계한다() -> None:
    coco = audit_coco({"o_0001.jpg": [2, 3], "o_0060.jpg": [2, 3, 3]})

    rows, _ = audit(coco)

    assert [r["segment"] for r in rows] == [1, 2]
    assert rows[0]["hole"] == 1 and rows[1]["hole"] == 2


def test_저장_시각_공백으로_버스트를_나눈다(tmp_path) -> None:
    csv_path = tmp_path / "session.csv"
    csv_path.write_text(
        "index,file,saved_at,mode,distance_cm,distance_std\n"
        "1,a_0001.jpg,2026-07-29T16:33:32,burst,,\n"
        "2,a_0002.jpg,2026-07-29T16:33:32,burst,,\n"
        "3,a_0003.jpg,2026-07-29T16:33:40,burst,,\n"   # 8초 → 새 버스트
        "4,a_0004.jpg,2026-07-29T16:33:40,burst,,\n",
        encoding="utf-8",
    )

    bursts = group_bursts(csv_path, gap_s=3.0)

    assert bursts == [["a_0001.jpg", "a_0002.jpg"], ["a_0003.jpg", "a_0004.jpg"]]
