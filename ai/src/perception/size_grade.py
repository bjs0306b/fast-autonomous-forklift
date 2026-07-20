"""박스 크기 등급 판정·미니어처 매핑 (FR-102).

박스 단일 클래스로 검출하므로 종류는 구분하지 않고 **크기 등급**만 나눈다.
판정된 등급은 대응하는 미니어처 화물로 매핑되어 디지털 트윈(Isaac Sim)에
적재된다 (명세 2.1 데모 시나리오: 실제 화물 3개 ↔ 미니어처 화물 3개).

**어느 면이 보이는지 모른다는 점이 핵심이다.** 34×25×21 박스를 정면에서 보면
34×25 일 수도, 34×21 일 수도, 25×21 일 수도 있다. 그래서 등급마다 세 면을
모두 후보로 두고 관측값과 대조한다.
"""

from __future__ import annotations

import itertools
import sys
from dataclasses import dataclass
from pathlib import Path

import yaml

from .geometry import ObservedFace


@dataclass(frozen=True)
class CargoGrade:
    """화물 등급 정의. 치수는 cm."""

    name: str
    label: str
    dimensions_cm: tuple[float, float, float]
    miniature: str

    @property
    def faces(self) -> list[tuple[float, float]]:
        """세 쌍의 면 치수를 (짧은 변, 긴 변)으로 정규화해 돌려준다."""
        return [
            (min(a, b), max(a, b))
            for a, b in itertools.combinations(self.dimensions_cm, 2)
        ]

    @property
    def volume_cm3(self) -> float:
        w, d, h = self.dimensions_cm
        return w * d * h


@dataclass(frozen=True)
class GradeMatch:
    """판정 결과. ``grade``가 None이면 어느 등급에도 맞지 않은 것이다."""

    grade: CargoGrade | None
    error: float          # 최대 상대 오차 (0.12 = 12%)
    matched_face: tuple[float, float] | None

    @property
    def is_known(self) -> bool:
        return self.grade is not None


def load_grades(path: Path) -> tuple[list[CargoGrade], float]:
    """등급 정의 YAML을 읽는다. (등급 목록, 허용 오차)를 돌려준다."""
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    grades = [
        CargoGrade(
            name=g["name"],
            label=g["label"],
            dimensions_cm=tuple(float(v) for v in g["dimensions_cm"]),
            miniature=g["miniature"],
        )
        for g in raw["grades"]
    ]
    if not grades:
        raise ValueError(f"{path}에 등급이 하나도 없습니다")

    _warn_if_ambiguous(grades, float(raw.get("tolerance", 0.2)))
    return grades, float(raw.get("tolerance", 0.2))


def classify(
    face: ObservedFace, grades: list[CargoGrade], tolerance: float
) -> GradeMatch:
    """관측된 정면 치수로 등급을 판정한다.

    등급의 세 면 중 가장 잘 맞는 것을 찾고, 그 오차가 ``tolerance``를 넘으면
    미지(unknown)로 본다. 측정 오차가 큰 상황에서 억지로 등급을 붙이는 것보다
    '모르겠다'가 안전하다 — 잘못된 미니어처를 적재하면 트윈이 어긋난다.
    """
    observed = (min(face.width_cm, face.height_cm), max(face.width_cm, face.height_cm))

    best: GradeMatch = GradeMatch(grade=None, error=float("inf"), matched_face=None)
    for grade in grades:
        for candidate in grade.faces:
            error = _face_error(observed, candidate)
            if error < best.error:
                best = GradeMatch(grade=grade, error=error, matched_face=candidate)

    if best.error > tolerance:
        return GradeMatch(grade=None, error=best.error, matched_face=None)
    return best


def _face_error(observed: tuple[float, float], candidate: tuple[float, float]) -> float:
    """두 변의 상대 오차 중 큰 값. 한 변만 맞는 경우를 걸러내기 위함이다."""
    return max(
        abs(o - c) / c if c > 0 else float("inf")
        for o, c in zip(observed, candidate)
    )


def separation_margin(a: CargoGrade, b: CargoGrade) -> tuple[float, tuple, tuple]:
    """두 등급이 얼마나 헷갈리기 쉬운지. (최소 오차, a의 면, b의 면).

    값이 작을수록 헷갈린다. 이 값보다 큰 측정 오차가 나면 오판이 시작된다.
    """
    best = (float("inf"), (), ())
    for fa in a.faces:
        for fb in b.faces:
            error = _face_error(fa, fb)
            if error < best[0]:
                best = (error, fa, fb)
    return best


def _warn_if_ambiguous(grades: list[CargoGrade], tolerance: float) -> None:
    """등급끼리 면 치수가 너무 비슷하면 판정이 갈릴 수 없다.

    설정을 바꿀 때 조용히 구분 불가능해지는 것을 막기 위해 미리 알린다.
    """
    import warnings

    for a, b in itertools.combinations(grades, 2):
        margin, fa, fb = separation_margin(a, b)
        if margin <= tolerance:
            warnings.warn(
                f"등급 '{a.name}'의 면 {fa}과 '{b.name}'의 면 {fb}이 "
                f"허용 오차({tolerance:.0%}) 안에서 겹칩니다(간격 {margin:.1%}). "
                f"두 등급을 안정적으로 구분할 수 없습니다.",
                stacklevel=3,
            )


def main(argv: list[str] | None = None) -> int:
    """등급 설정이 실제로 구분 가능한지 검사한다.

    화물 3종을 고를 때 이 검사를 통과하는 조합인지 먼저 확인한다.
    """
    import argparse

    parser = argparse.ArgumentParser(description="화물 등급 설정 검증 (FR-102)")
    parser.add_argument("--config", type=Path, default=Path("configs/cargo.yaml"))
    args = parser.parse_args(argv)

    import warnings

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        grades, tolerance = load_grades(args.config)

    print(f"설정: {args.config}")
    print(f"허용 오차: {tolerance:.0%}\n")
    for g in grades:
        w, d, h = g.dimensions_cm
        print(f"  {g.name:<3} {g.label:<4} {w:g}x{d:g}x{h:g}cm  "
              f"부피 {g.volume_cm3:,.0f}cm3  → {g.miniature}")

    print("\n등급 간 구분 여유 (측정 오차가 이 값을 넘으면 오판 시작):")
    ok = True
    for a, b in itertools.combinations(grades, 2):
        margin, fa, fb = separation_margin(a, b)
        verdict = "OK" if margin > tolerance else "위험"
        if margin <= tolerance:
            ok = False
        print(f"  {a.name} vs {b.name}: {margin:>6.1%}  [{verdict}]"
              f"   {fa[0]:g}x{fa[1]:g} vs {fb[0]:g}x{fb[1]:g}")

    if ok:
        print("\n[통과] 모든 등급 쌍이 허용 오차보다 멀리 떨어져 있습니다.")
        return 0

    print("\n[실패] 허용 오차 안에서 겹치는 등급 쌍이 있습니다.", file=sys.stderr)
    print("  다음 중 하나로 해결합니다:", file=sys.stderr)
    print("   - 크기 차이가 더 뚜렷한 화물로 교체 (권장)", file=sys.stderr)
    print("   - tolerance를 겹침 간격보다 작게 조정 "
          "(측정 정밀도가 그만큼 받쳐줘야 한다)", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
