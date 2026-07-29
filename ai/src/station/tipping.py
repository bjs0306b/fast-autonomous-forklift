"""전복 위험 판정 (FR-103 무게중심) — 화물이 넘어질 위험을 기하로 낸다.

**편하중과 무엇이 다른가**: `load_balance`는 화물이 한쪽으로 치우쳤는지를 **박스 크기
대비**로 본다(스케일 불변). 전복은 다른 질문이다 — *무게중심이 지지면(파렛트)을
벗어나는가*. 그래서 기준 분모가 **파렛트 반폭**이어야 하고, 별도 지표가 필요하다.

물리적으로 물체는 **무게중심의 연직선이 지지면 밖으로 나가면** 넘어진다. 정면 단일
카메라라 앞뒤(깊이)는 못 보므로 **좌우만** 판정한다 — 명세 §2.2대로 깊이는 측정 대상이
아니고, 파렛트 규격이 고정이라 앞뒤 여유는 설계로 보장된다.

세 가지를 본다:

1. **지지면 이탈률** = |화물 무게중심 − 파렛트 중심| / (파렛트 반폭)
   0이면 정중앙, 1.0이면 무게중심이 파렛트 끝에 걸린 상태(= 물리적 한계).
2. **종횡비** = 화물 높이 / 화물 폭. 높고 좁을수록 작은 외란에도 넘어간다.
3. **돌출** = 화물이 파렛트 좌우 밖으로 나갔는지. 나간 쪽은 지지가 없다.

무게는 못 재므로 **밀도 균일을 가정**하고 bbox 기하 중심을 무게중심으로 본다
(load_balance와 같은 가정, 2026-07-22 결정).
"""

from __future__ import annotations

from perception.load_balance import BBox

# 지지면 이탈률 임계. 1.0이 물리적 한계이므로 그 전에 경고한다.
WARNING_OFFSET = 0.60
DANGER_OFFSET = 0.85
# 이 종횡비를 넘으면 한 단계 격상 — 높고 좁은 적재물은 같은 이탈률에서도 더 위험하다.
TALL_ASPECT = 1.5
# 화물이 파렛트 밖으로 이만큼(파렛트 폭 대비) 넘어가면 돌출로 본다.
OVERHANG_RATIO = 0.02

LEVELS = ("safe", "warning", "danger")


def _escalate(level: str) -> str:
    i = LEVELS.index(level)
    return LEVELS[min(i + 1, len(LEVELS) - 1)]


def assess_tipping(
    boxes: list[BBox],
    pallet: BBox,
    height_cm: float | None = None,
    width_cm: float | None = None,
) -> dict:
    """화물(박스들)과 파렛트로 전복 위험을 판정한다.

    ``height_cm``/``width_cm``는 롤 보정까지 끝난 실측 치수다. 종횡비는 픽셀이 아니라
    이 실측값으로 계산한다 — 픽셀 종횡비는 렌즈·원근 때문에 실제와 다르다.
    없으면 종횡비 판정을 건너뛴다(이탈률·돌출만으로 판정).
    """
    if not boxes or pallet.w <= 0:
        return {"assessable": False, "reason": "화물 또는 파렛트 없음"}

    # 무게중심 = 박스 중심들의 단순 평균 (load_balance와 같은 가정)
    com_x = sum(b.center_x for b in boxes) / len(boxes)
    half = pallet.w / 2
    offset = abs(com_x - pallet.center_x) / half
    direction = "right" if com_x > pallet.center_x else "left"

    # 돌출 — 화물 외곽이 파렛트 밖으로 나간 정도
    load_left = min(b.x for b in boxes)
    load_right = max(b.x + b.w for b in boxes)
    over_l = (pallet.x - load_left) / pallet.w
    over_r = (load_right - (pallet.x + pallet.w)) / pallet.w
    overhang = max(over_l, over_r, 0.0)

    aspect = (height_cm / width_cm) if (height_cm and width_cm and width_cm > 0) else None

    # --- 등급 ---
    if offset >= DANGER_OFFSET:
        level = "danger"
    elif offset >= WARNING_OFFSET:
        level = "warning"
    else:
        level = "safe"

    reasons = []
    if level != "safe":
        reasons.append(f"무게중심이 파렛트 반폭의 {offset:.0%} 이탈")
    if aspect is not None and aspect >= TALL_ASPECT:
        level = _escalate(level)
        reasons.append(f"높고 좁음(종횡비 {aspect:.2f})")
    if overhang > OVERHANG_RATIO:
        level = _escalate(level)
        reasons.append(f"화물이 파렛트 밖으로 {overhang:.0%} 돌출")

    # **정지 안정과 운반 안전은 다른 질문이다.** 무게중심이 파렛트 안에 있으면 세워둔
    # 채로는 안 넘어지지만(static_stable), 지게차가 가속·회전·요철을 지나면 남은 여유가
    # 쉽게 사라진다. 실측(2026-07-29 tip_005): 이탈률 0.799에 무게중심이 파렛트 끝보다
    # 100px 안쪽이라 정지 시엔 멀쩡했으나 하단 박스의 42%가 파렛트 밖으로 나가 있었다.
    # 그래서 등급은 운반 기준으로 매기되, 정지 안정 여부를 따로 알려 혼선을 없앤다.
    static_stable = offset < 1.0

    if level == "safe":
        message = f"안정 (무게중심 이탈 {offset:.0%}, 한계까지 {1 - offset:.0%} 여유)"
    elif not static_stable:
        message = f"즉시 전복 위험 — 무게중심이 이미 파렛트 밖 ({offset:.0%})"
    else:
        head = "운반 부적합" if level == "danger" else "운반 주의"
        message = f"{head} — {', '.join(reasons)} (정지 시엔 안정)"

    return {
        "assessable": True,
        "level": level,
        # 정지 상태에서 넘어지는가 — 등급(운반 기준)과 구분해서 낸다
        "static_stable": static_stable,
        "support_offset": round(offset, 3),      # 1.0 = 물리적 한계
        "margin": round(max(0.0, 1 - offset), 3),
        "direction": direction if offset > 0.05 else None,
        "aspect_ratio": round(aspect, 2) if aspect is not None else None,
        "overhang": round(overhang, 3),
        "message": message,
    }
