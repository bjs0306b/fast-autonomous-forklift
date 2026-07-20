"""크기 등급 판정 테스트 (FR-102)."""

from __future__ import annotations

import warnings
from pathlib import Path

import pytest

from perception.geometry import ObservedFace, PinholeCamera, measure_face
from perception.size_grade import (
    CargoGrade,
    classify,
    load_grades,
    separation_margin,
)

CONFIG = Path(__file__).resolve().parents[1] / "configs" / "cargo.yaml"


@pytest.fixture
def grades() -> list[CargoGrade]:
    return [
        CargoGrade("S", "소형", (27, 18, 15), "mini_s"),
        CargoGrade("M", "중형", (35, 25, 10), "mini_m"),
        CargoGrade("L", "대형", (48, 38, 34), "mini_l"),
    ]


# --- 기하 ---

def test_거리가_멀수록_같은_픽셀이_더_큰_실치수가_된다() -> None:
    cam = PinholeCamera(fx=1000, fy=1000, width=1280, height=720)

    near = measure_face((0, 0, 200, 100), distance_cm=50, camera=cam)
    far = measure_face((0, 0, 200, 100), distance_cm=100, camera=cam)

    assert near.width_cm == pytest.approx(10.0)
    assert far.width_cm == pytest.approx(20.0)


def test_화각으로_초점거리를_만든다() -> None:
    cam = PinholeCamera.from_fov(width=1280, height=720, hfov_deg=90)
    # 90도면 fx = (w/2) / tan(45) = w/2
    assert cam.fx == pytest.approx(640.0)


def test_비정상_입력은_거부한다() -> None:
    cam = PinholeCamera(fx=1000, fy=1000, width=1280, height=720)

    with pytest.raises(ValueError, match="bbox"):
        measure_face((0, 0, 0, 100), distance_cm=50, camera=cam)
    with pytest.raises(ValueError, match="거리"):
        measure_face((0, 0, 200, 100), distance_cm=0, camera=cam)
    with pytest.raises(ValueError, match="초점거리"):
        PinholeCamera(fx=0, fy=1000, width=1280, height=720)


# --- 등급 판정 ---

def test_어느_면이_보여도_같은_등급으로_판정한다(grades: list[CargoGrade]) -> None:
    """박스는 세 면 중 무엇이 보일지 모른다. 27x18x15의 세 면 모두 S여야 한다."""
    for face in [(27, 18), (27, 15), (18, 15)]:
        match = classify(ObservedFace(*face), grades, tolerance=0.2)
        assert match.grade is not None and match.grade.name == "S", f"{face} 실패"


def test_뒤집혀_보여도_같은_등급이다(grades: list[CargoGrade]) -> None:
    """가로/세로가 바뀌어 관측돼도 결과가 같아야 한다."""
    upright = classify(ObservedFace(27, 18), grades, tolerance=0.2)
    rotated = classify(ObservedFace(18, 27), grades, tolerance=0.2)

    assert upright.grade == rotated.grade
    assert upright.error == pytest.approx(rotated.error)


def test_어느_등급과도_멀면_미지로_둔다(grades: list[CargoGrade]) -> None:
    """억지로 등급을 붙이면 잘못된 미니어처가 적재돼 트윈이 어긋난다."""
    match = classify(ObservedFace(200, 150), grades, tolerance=0.2)

    assert match.grade is None
    assert not match.is_known


def test_허용_오차_안의_측정_흔들림은_흡수한다(grades: list[CargoGrade]) -> None:
    # 27x18 을 10% 크게 관측
    match = classify(ObservedFace(29.7, 19.8), grades, tolerance=0.2)

    assert match.grade is not None and match.grade.name == "S"
    assert match.error == pytest.approx(0.1, abs=0.01)


def test_등급은_미니어처로_매핑된다(grades: list[CargoGrade]) -> None:
    match = classify(ObservedFace(48, 38), grades, tolerance=0.2)

    assert match.grade is not None
    assert match.grade.miniature == "mini_l"


# --- 설정 검증 ---

def test_실제_설정은_구분_가능하다() -> None:
    """cargo.yaml의 조합이 허용 오차보다 멀리 떨어져 있어야 한다.

    1/2/3호처럼 인접한 호수를 고르면 12%까지 좁아져 오판이 난다.
    설정을 바꿀 때 이 테스트가 막아 준다.
    """
    with warnings.catch_warnings():
        warnings.simplefilter("error")  # 모호성 경고가 나면 실패시킨다
        loaded, tolerance = load_grades(CONFIG)

    for i, a in enumerate(loaded):
        for b in loaded[i + 1:]:
            margin, fa, fb = separation_margin(a, b)
            assert margin > tolerance, (
                f"{a.name}({fa})와 {b.name}({fb})의 간격 {margin:.1%}이 "
                f"허용 오차 {tolerance:.0%} 이하입니다"
            )


def test_인접한_택배호수는_모호성_경고를_낸다() -> None:
    """1/2/3호 조합은 구분 여유가 12%뿐이라 20% 허용 오차에서 겹친다."""
    path = CONFIG.parent / "_tmp_ambiguous.yaml"
    path.write_text(
        "tolerance: 0.2\n"
        "grades:\n"
        "  - {name: S, label: 소, dimensions_cm: [22, 19, 9], miniature: a}\n"
        "  - {name: M, label: 중, dimensions_cm: [27, 18, 15], miniature: b}\n"
        "  - {name: L, label: 대, dimensions_cm: [34, 25, 21], miniature: c}\n",
        encoding="utf-8",
    )
    try:
        with pytest.warns(UserWarning, match="안정적으로 구분할 수 없습니다"):
            load_grades(path)
    finally:
        path.unlink()


def test_빈_등급_설정은_거부한다() -> None:
    path = CONFIG.parent / "_tmp_empty.yaml"
    path.write_text("tolerance: 0.2\ngrades: []\n", encoding="utf-8")
    try:
        with pytest.raises(ValueError, match="등급이 하나도 없습니다"):
            load_grades(path)
    finally:
        path.unlink()
