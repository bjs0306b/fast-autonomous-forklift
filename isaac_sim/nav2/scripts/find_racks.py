"""씬에서 랙을 찾아 크기·위치·선반 높이를 잰다 (Isaac Script Editor 용).

랙에 화물을 놓으려면 세 가지가 필요하다.
    1. 지게차가 설 접근 위치   (통로 안이어야 한다 — 주행 가능해야 함)
    2. 선반 높이               (포크를 얼마나 올릴지)
    3. 화물을 놓을 좌표        (선반 위)

기존 cargo_demo.RACK_SLOTS 의 값은 차체를 3.2x1.5 로 잡았을 때 잰 것이라
실측 3.8x1.9 로는 접근 위치가 벽/랙 안에 들어가 주행이 불가능하다.
그래서 여기서 다시 잰다.

사용:
    exec(open('/home/ubuntu/forklift_ws/nav2/scripts/find_racks.py').read())

출력된 값을 알려주면 주행 가능한 접근 좌표를 계산해 RACK_SLOTS 를 갱신한다.
"""
import omni.usd
from pxr import Usd, UsdGeom

_st = omni.usd.get_context().get_stage()

# 이름에 이런 단어가 들어간 프림을 랙으로 본다
KEYS = ("rack", "shelf", "shelv", "pallet_rack", "SM_Rack", "Rack")


def _bbox(prim):
    cache = UsdGeom.BBoxCache(Usd.TimeCode.Default(), [UsdGeom.Tokens.default_])
    r = cache.ComputeWorldBound(prim).ComputeAlignedRange()
    return r.GetMin(), r.GetMax()


def find_racks(max_show=20):
    """랙으로 보이는 프림을 찾아 위치·크기를 보여준다."""
    found = []
    seen_parents = set()
    for p in _st.Traverse():
        path = str(p.GetPath())
        name = path.rsplit("/", 1)[-1]
        if not any(k.lower() in name.lower() for k in KEYS):
            continue
        # 자식까지 다 찍으면 지저분하므로 최상위 랙 프림만
        if any(path.startswith(sp + "/") for sp in seen_parents):
            continue
        seen_parents.add(path)
        try:
            mn, mx = _bbox(p)
        except Exception:
            continue
        if mx[0] - mn[0] < 0.3 and mx[1] - mn[1] < 0.3:
            continue                       # 너무 작은 건 랙이 아니다
        found.append((path, mn, mx))

    print(f"랙 후보 {len(found)}개")
    print(f"{'경로':50s} {'x 범위':>16s} {'y 범위':>16s} {'높이':>8s}")
    for path, mn, mx in found[:max_show]:
        print(f"{path[-50:]:50s} "
              f"{mn[0]:6.2f}~{mx[0]:6.2f}  {mn[1]:6.2f}~{mx[1]:6.2f}  "
              f"{mx[2]:7.2f}")
    if len(found) > max_show:
        print(f"  ... 외 {len(found) - max_show}개")
    return found


def shelf_heights(rack_path):
    """랙 하나의 선반(가로 판) 높이를 추정해 보여준다."""
    prim = _st.GetPrimAtPath(rack_path)
    if not prim.IsValid():
        print(f"프림 없음: {rack_path}")
        return []
    heights = []
    for c in Usd.PrimRange(prim):
        if not c.IsA(UsdGeom.Gprim):
            continue
        try:
            mn, mx = _bbox(c)
        except Exception:
            continue
        w, d, h = mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2]
        # 얇고 넓으면 선반 판으로 본다
        if h < 0.35 and w > 0.8 and d > 0.5:
            heights.append(round(float(mx[2]), 2))
    heights = sorted(set(heights))
    print(f"{rack_path}\n  선반 높이 후보: {heights}")
    return heights


racks = find_racks()
print()
print("다음: 특정 랙의 선반 높이를 보려면")
print("  shelf_heights('<위에서 고른 경로>')")
