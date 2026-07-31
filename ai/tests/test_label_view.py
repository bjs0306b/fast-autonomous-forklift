"""라벨러 창 크기 계산 테스트 (S15P11A304-144).

창이 모니터보다 커지면 아래쪽 경고줄이 잘려 검수를 화면에 띄운 의미가 없어진다.
"""

from __future__ import annotations

from dataset.label_onboard import fit_scale

FHD = (1920, 1080)
HD = (1366, 768)


def test_요청_폭이_화면에_들어가면_그대로_쓴다() -> None:
    assert fit_scale(1280, 800, 1280, FHD) == 1.0


def test_요청_폭이_화면보다_크면_깎는다() -> None:
    """1080p에서 1920 요청 — 세로(800×1.5=1200)가 화면을 넘는다."""
    s = fit_scale(1280, 800, 1920, FHD)

    assert s < 1.5
    assert 800 * s <= FHD[1] - 130


def test_작은_화면에서는_1배도_안_넘게_줄인다() -> None:
    s = fit_scale(1280, 800, 1280, HD)

    assert s < 1.0
    assert 1280 * s <= HD[0] - 40
    assert 800 * s <= HD[1] - 130


def test_배율은_항상_양수다() -> None:
    assert fit_scale(1280, 800, 640, FHD) > 0
    assert fit_scale(1280, 800, 3840, (800, 600)) > 0
