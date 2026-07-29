"""전복 판정 테스트 — 기하만 쓰므로 하드웨어·모델 없이 돈다."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from perception.load_balance import BBox  # noqa: E402
from station.tipping import assess_tipping  # noqa: E402

# 파렛트: x 0~1000 (중심 500, 반폭 500)
PALLET = BBox(x=0, y=900, w=1000, h=120)


def box_at(cx: float, w: float = 200, h: float = 200) -> BBox:
    return BBox(x=cx - w / 2, y=900 - h, w=w, h=h)


def test_중앙이면_안정():
    r = assess_tipping([box_at(500)], PALLET, height_cm=30, width_cm=50)
    assert r["level"] == "safe"
    assert r["support_offset"] == 0.0
    assert r["margin"] == 1.0


def test_이탈률은_파렛트_반폭_기준():
    # 중심에서 250px = 반폭 500의 50%
    r = assess_tipping([box_at(750)], PALLET, height_cm=30, width_cm=50)
    assert r["support_offset"] == 0.5
    assert r["level"] == "safe"           # 0.6 미만
    assert r["direction"] == "right"


def test_경고_임계():
    r = assess_tipping([box_at(500 + 0.7 * 500)], PALLET, height_cm=30, width_cm=50)
    assert r["level"] == "warning"
    assert "전복 주의" in r["message"]


def test_위험_임계():
    r = assess_tipping([box_at(500 + 0.9 * 500)], PALLET, height_cm=30, width_cm=50)
    assert r["level"] == "danger"


def test_높고_좁으면_한단계_격상():
    """같은 이탈률이라도 종횡비가 크면 위험도가 올라간다."""
    center = 500 + 0.7 * 500
    낮음 = assess_tipping([box_at(center)], PALLET, height_cm=30, width_cm=50)
    높음 = assess_tipping([box_at(center)], PALLET, height_cm=90, width_cm=50)
    assert 낮음["level"] == "warning"
    assert 높음["level"] == "danger"       # 종횡비 1.8 → 격상
    assert 높음["aspect_ratio"] == 1.8


def test_안정_상태에서도_종횡비_격상():
    r = assess_tipping([box_at(500)], PALLET, height_cm=100, width_cm=50)
    assert r["level"] == "warning"        # safe였지만 종횡비 2.0으로 격상


def test_파렛트_밖_돌출이면_격상():
    # 파렛트 오른쪽 끝(1000) 밖으로 나가는 박스
    r = assess_tipping([box_at(980, w=200)], PALLET, height_cm=30, width_cm=50)
    assert r["overhang"] > 0
    assert r["level"] != "safe"
    assert "돌출" in r["message"]


def test_여러_박스는_중심_평균():
    """무게중심은 박스 중심들의 단순 평균 (load_balance와 같은 가정)."""
    r = assess_tipping([box_at(300), box_at(700)], PALLET, height_cm=30, width_cm=50)
    assert r["support_offset"] == 0.0     # 좌우 대칭이면 상쇄

def test_치수_없으면_종횡비_건너뜀():
    r = assess_tipping([box_at(500)], PALLET)
    assert r["assessable"] is True
    assert r["aspect_ratio"] is None
    assert r["level"] == "safe"


def test_화물_없으면_판정_불가():
    r = assess_tipping([], PALLET, height_cm=30, width_cm=50)
    assert r["assessable"] is False
