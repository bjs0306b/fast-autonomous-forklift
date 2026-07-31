"""포크 정렬 타깃 선택·오차 산출 (S15P11A304-143 → 152가 소비).

`trt_detector.detect()`가 준 `pallet`·`hole` 검출을 받아 **어느 파렛트의 어느 면으로
들어갈지** 고르고, 그 면에 대한 **정렬 오차**를 낸다. 152의 제어 루프는 이 오차만
보고 `cmd_vel`을 만든다.

    from perception.fork_align import TargetTracker

    tracker = TargetTracker(image_width=1280)
    target = tracker.update(det.detect(frame))     # 프레임마다
    if target:
        err = target.error(image_width=1280)
        # err.lateral_ratio, err.yaw_signal, err.approach_px

`load_balance`·`tipping`과 같은 방침으로 **bbox만 받는 순수 기하 모듈**이다 —
하드웨어·추론 없이 테스트된다.

## 왜 선택 규칙이 필요한가

파렛트가 한 개라는 보장이 없다. 2026-07-31 젯슨 라이브에서 **창밖 흰 건물이
`pallet 0.50`** 으로 같이 잡혔고(지금은 임계 0.7로 걸린다), 실제 창고에서는 진짜
파렛트가 여럿 보인다. "어느 것으로 갈지"를 정하지 않으면 프레임마다 다른 파렛트를
쫓아 제어가 발산한다.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from perception.load_balance import BBox, Detection
from perception.trt_detector import DEFAULT_SLACK, hole_inside_pallet

LABEL_PALLET = "pallet"
LABEL_HOLE = "hole"

# 미니 파렛트 실측(라벨 가이드 §2): 개구 35×9.5mm, 통로 중심 간격 48~49mm.
HOLE_SPACING_MM = 48.5

# 타깃 교체 히스테리시스. 새 후보가 현재 타깃의 이 배수를 넘어야 갈아탄다.
#
# 근거: 진입면 점수의 1등/2등 여유를 실측하니 **45° 부근에서 1.00~1.2배까지 붙는다**
# (train 다면 프레임 40장 중 7장). 매 프레임 argmax를 그대로 따르면 22fps에서 타깃이
# 떨린다. 1.3배는 그 붙는 구간(≤1.2)보다 위, 실제로 갈아탈 만한 차이(eval 중앙값
# 1.50배)보다 아래라 둘을 가른다.
DEFAULT_SWITCH_RATIO = 1.3

# 타깃을 놓쳐도 이만큼은 락을 유지한다(프레임). 22fps 기준 5프레임 ≈ 0.23초.
# 검출은 프레임 단위로 깜빡인다 — 한 프레임 빠졌다고 상태기계를 되돌리면 진입이 끊긴다.
DEFAULT_GRACE_FRAMES = 5

# 같은 파렛트로 볼 IoU 하한. 22fps에서 프레임 간 이동은 작아 넉넉하다.
DEFAULT_TRACK_IOU = 0.3


def _iou(a: BBox, b: BBox) -> float:
    ix = max(0.0, min(a.x + a.w, b.x + b.w) - max(a.x, b.x))
    iy = max(0.0, min(a.y + a.h, b.y + b.h) - max(a.y, b.y))
    inter = ix * iy
    union = a.w * a.h + b.w * b.h - inter
    return inter / union if union > 0 else 0.0


@dataclass(frozen=True)
class AlignError:
    """정렬 오차 — 전부 **화면 단위**다. 카메라 내부파라미터 없이 계산된다.

    152의 P 제어는 이 세 값만 있으면 닫힌다. 절대 단위(mm·도)가 필요한 것은
    수렴 판정 임계뿐이고, 그건 `focal_px`가 들어오면 `yaw_deg`로 나온다.
    """

    lateral_ratio: float
    """좌우 오차. (진입면 중심 x − 포크 중심선 x) / (진입면 폭의 절반).

    `load_balance`가 편하중을 절대 거리가 아니라 비율로 판정한 것과 같은 이유다 —
    픽셀→mm 캘리브레이션 없이도 동작하고, 거리가 변해도 같은 임계를 쓸 수 있다.
    +면 파렛트가 오른쪽에 있다(오른쪽으로 조향해야 한다)."""

    yaw_signal: float
    """요(yaw) 신호. (왼쪽 구멍 폭 − 오른쪽 구멍 폭) / (두 폭의 합).

    라벨 규약 §3-①의 근거를 그대로 쓴다 — 비스듬히 보면 **먼 쪽 구멍이 좁게 보인다.**
    두 구멍의 실제 폭은 같으므로(35mm) 화면 폭 비가 곧 깊이 비다: w₁/w₂ = d₂/d₁.
    0이면 정면. +면 오른쪽 구멍이 좁다 = 파렛트 오른쪽 끝이 더 멀다."""

    approach_px: float
    """진입면 폭(두 구멍 중심 간 화면 거리, px). 거리의 역수에 비례하는 접근 지표다.
    커질수록 가깝다. 절대 거리는 `distance_mm`."""

    yaw_deg: float | None = None
    """`focal_px`를 주면 채워지는 절대 요각(도). 없으면 None."""

    distance_mm: float | None = None
    """`focal_px`를 주면 채워지는 진입면까지의 거리(mm). 없으면 None."""


@dataclass(frozen=True)
class EntryFace:
    """진입면 — 포크 2개가 들어갈 구멍 2개."""

    left: Detection
    right: Detection
    score: float

    @property
    def center_x(self) -> float:
        return (self.left.box.center_x + self.right.box.center_x) / 2

    @property
    def span(self) -> float:
        """두 구멍 중심 간 화면 거리(px)."""
        return abs(self.right.box.center_x - self.left.box.center_x)

    @property
    def min_width(self) -> float:
        return min(self.left.box.w, self.right.box.w)


@dataclass(frozen=True)
class AlignTarget:
    """정렬 대상 — 파렛트 하나와 그 파렛트의 진입면 하나."""

    pallet: Detection
    face: EntryFace

    @property
    def score(self) -> float:
        return self.face.score

    def error(self, image_width: float, fork_center_x: float | None = None,
              focal_px: float | None = None) -> AlignError:
        """정렬 오차를 낸다.

        `fork_center_x`: 포크 중심선의 화면 x. 기본은 이미지 중앙이지만, 카메라를
        차체 중심에서 벗어나게 달았다면 그 값을 넣는다 — **장착 오프셋을 여기서
        흡수하지 않으면 제어가 일정한 편차를 안고 수렴한다.**
        """
        ref = image_width / 2 if fork_center_x is None else fork_center_x
        half = self.face.span / 2
        lateral = (self.face.center_x - ref) / half if half > 0 else 0.0

        wl, wr = self.face.left.box.w, self.face.right.box.w
        yaw_signal = (wl - wr) / (wl + wr) if (wl + wr) > 0 else 0.0

        yaw_deg = distance_mm = None
        if focal_px:
            # 유도: 두 구멍의 실제 폭이 같으므로 w₁/w₂ = d₂/d₁ →
            #   (d₂−d₁)/(d₂+d₁) = (w₁−w₂)/(w₁+w₂) = yaw_signal
            # 깊이차 Δd = S·sinθ, 평균 깊이 d̄ = f·S·cosθ / span 이므로
            #   tanθ = 2f/span · yaw_signal
            # S(=중심 간격)가 약분돼 사라진다 — 간격 실측 오차에 둔감하다.
            tan = 2 * focal_px / self.face.span * yaw_signal if self.face.span > 0 else 0.0
            yaw_deg = math.degrees(math.atan(tan))
            distance_mm = (focal_px * HOLE_SPACING_MM
                           * math.cos(math.radians(yaw_deg)) / self.face.span)

        return AlignError(lateral_ratio=lateral, yaw_signal=yaw_signal,
                          approach_px=self.face.span,
                          yaw_deg=yaw_deg, distance_mm=distance_mm)


def choose_entry_face(holes: list[Detection]) -> EntryFace | None:
    """구멍들에서 진입면(구멍 2개)을 고른다 — 라벨 가이드 §3-⑥의 런타임 구현.

    규약이 남긴 문장은 "**두 구멍의 중심을 이은 선분이 더 길고 개구가 넓은 쪽**"이다.
    이걸 그대로 코드로 옮기면 틀린다. train·eval 다면 프레임 47장(구멍 3~4개)으로
    후보를 실측한 결과:

        후보                          train 40장   eval 7장
        전체 쌍 · 거리                    0/40        0/7
        전체 쌍 · 거리×평균폭              1/40        0/7
        **인접 쌍** · 거리×평균폭          33/40        7/7
        **인접 쌍** · 거리×**최소폭**      33/40        7/7   ← 채택

    두 가지가 갈랐다.

    ① **인접 쌍으로 제한**해야 한다(0/40 → 33/40). 앞면 구멍과 옆면 구멍을 하나씩
       집으면 선분이 가장 길어져 "가장 긴 쪽"이 반드시 오답이 된다. 파렛트는 볼록하고
       두 면은 모서리를 공유하므로 **각 면의 구멍은 화면 x에서 겹치지 않는 구간**을
       차지한다 — x로 정렬하면 같은 면끼리 이웃한다. 그래서 인접 쌍만 본다.

    ② 폭은 **평균이 아니라 최소**를 쓴다. 정답률은 같고 1등/2등 여유가 벌어진다
       (중앙값: train 1.62 → **1.90**배, eval 1.09 → **1.50**배). 물리적으로도 최소가
       맞다 — **포크는 둘 다 들어가야 하므로 좁은 쪽 개구가 제약**이다.

    남은 불일치 7장은 전부 **45° 부근**으로 두 면의 개구가 112~124px 대 126~142px이다.
    어느 쪽으로 들어가도 되는 프레임이라 오답으로 치지 않았다 — 그 구간에서 갈리는 건
    규칙이 아니라 임의의 타이브레이크다.

    ⚠️ 바로 그 구간에서 **최소폭 쪽 마진 최솟값이 train 1.14 → 1.00배로 되레 붙는다.**
    두 면이 대등해지면 어느 쪽이든 무방하지만, **매 프레임 argmax를 그대로 따르면
    면이 프레임마다 뒤집힌다**는 뜻이다 — `TargetTracker`의 히스테리시스가 그래서 있다.

    재현: `python scripts/eval_entry_face.py --labels data/labels/onboard_cvat_pallet_hole.json
    data/labels/onboard_eval.json`
    """
    if len(holes) < 2:
        return None
    ordered = sorted(holes, key=lambda d: d.box.center_x)
    best: EntryFace | None = None
    for left, right in zip(ordered, ordered[1:]):
        span = right.box.center_x - left.box.center_x
        score = span * min(left.box.w, right.box.w)
        if best is None or score > best.score:
            best = EntryFace(left=left, right=right, score=score)
    return best


def eligible_targets(detections: list[Detection],
                     slack: float = DEFAULT_SLACK) -> list[AlignTarget]:
    """정렬 대상이 될 수 있는 파렛트만 추려 점수 순으로 돌려준다.

    **자격(있냐 없냐)과 순위(누가 먼저냐)를 나눈다.**

    자격 — 진입면이 있어야 한다. 구멍이 2개 미만인 파렛트는 **점수가 아무리 높아도
    버린다.** 꽂을 구멍이 안 보이면 정렬할 대상이 아니기 때문이고, 덤으로 오탐이
    한 겹 더 걸린다: 창밖 건물·커튼 같은 도메인 밖 오탐은 그 안에 `hole`이 없다
    (`filter_by_geometry`가 이미 파렛트 밖 hole을 버린 뒤라 서로 맞물린다).

    순위 — **진입면 점수(선분 길이 × 최소 개구폭)를 그대로 쓴다.** 따로 "가까운 것"
    이나 "화면 중앙" 기준을 만들지 않았다. 그 점수가 이미 둘을 담고 있기 때문이다:
    가까울수록 구멍 간격이 크게 잡히고(길이↑), 정면일수록 개구가 넓다(폭↑). 옆으로
    비껴 있는 파렛트는 비스듬히 보여 폭이 먼저 깎인다.

    ⚠️ 이 순위는 **실측으로 검증되지 않았다.** eval 150장은 전 프레임 파렛트가
    정확히 1개라(105/105) 여러 개 중 고르는 상황이 데이터에 없다. 다중 파렛트
    프레임을 찍어 확인할 것 — 그때까지는 설계 근거만 있는 규칙이다.
    """
    pallets = [d for d in detections if d.label == LABEL_PALLET]
    holes = [d for d in detections if d.label == LABEL_HOLE]
    if not pallets:
        return []

    # 구멍을 파렛트에 배정한다. 파렛트가 겹치면 **작은 쪽**에 준다 — 큰 bbox가 작은
    # 것을 감싸는 경우 구멍은 실제로 안쪽 파렛트의 것이다.
    assigned: dict[int, list[Detection]] = {id(p): [] for p in pallets}
    for h in holes:
        owners = [p for p in pallets if hole_inside_pallet(h.box, p.box, slack)]
        if not owners:
            continue
        owner = min(owners, key=lambda p: p.box.w * p.box.h)
        assigned[id(owner)].append(h)

    targets = []
    for p in pallets:
        face = choose_entry_face(assigned[id(p)])
        if face is not None:
            targets.append(AlignTarget(pallet=p, face=face))
    return sorted(targets, key=lambda t: -t.score)


class TargetTracker:
    """프레임 간 타깃을 **고정**한다 — 한 번 고른 파렛트를 계속 쫓는다.

    매 프레임 독립으로 argmax를 뽑으면 안 된다. 점수가 붙어 있는 상황(45° 부근에서
    두 면이 1.0~1.2배, 파렛트 둘이 비슷한 거리)에서 타깃이 프레임마다 바뀌고, 22fps로
    도는 제어 루프는 그걸 그대로 조향에 넣어 좌우로 떤다. 그래서 두 겹으로 묶는다:

    - **히스테리시스**: 새 후보가 현재 타깃의 `switch_ratio`배를 넘어야 갈아탄다.
    - **유예(grace)**: 검출이 몇 프레임 빠져도 락을 유지한다. 놓친 프레임마다
      상태기계를 되돌리면 진입이 끊긴다.

    락 자체를 푸는 것은 호출자(152 상태기계)의 몫이다 — 진입 완료·중단 시 `reset()`.
    """

    def __init__(self, switch_ratio: float = DEFAULT_SWITCH_RATIO,
                 grace_frames: int = DEFAULT_GRACE_FRAMES,
                 track_iou: float = DEFAULT_TRACK_IOU,
                 slack: float = DEFAULT_SLACK) -> None:
        self.switch_ratio = switch_ratio
        self.grace_frames = grace_frames
        self.track_iou = track_iou
        self.slack = slack
        self.locked: AlignTarget | None = None
        self.misses = 0

    def reset(self) -> None:
        self.locked = None
        self.misses = 0

    def update(self, detections: list[Detection]) -> AlignTarget | None:
        """이번 프레임의 검출로 타깃을 갱신한다. 유예 중이면 직전 타깃을 돌려준다."""
        targets = eligible_targets(detections, self.slack)

        if self.locked is not None:
            # 직전 타깃과 같은 파렛트를 찾는다(위치 연속성 — score는 흔들린다).
            same = max(
                (t for t in targets
                 if _iou(t.pallet.box, self.locked.pallet.box) >= self.track_iou),
                key=lambda t: _iou(t.pallet.box, self.locked.pallet.box),
                default=None)
            if same is not None:
                self.misses = 0
                best = targets[0]
                if best is not same and best.score > same.score * self.switch_ratio:
                    self.locked = best
                else:
                    self.locked = same
                return self.locked
            # 놓쳤다 — 유예 안이면 직전 타깃을 그대로 유지한다.
            self.misses += 1
            if self.misses <= self.grace_frames:
                return self.locked
            self.reset()

        self.locked = targets[0] if targets else None
        self.misses = 0
        return self.locked
