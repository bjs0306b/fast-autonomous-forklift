"""랙 좌표 뽑기 — 적재에 필요한 세 좌표를 씬에서 직접 잰다 (Script Editor 용).

    approach   통로 위 지게차 위치 (Nav2 목표)
    dock       포크를 랙에 넣었을 때 지게차 중심
    place      화물이 놓일 선반 좌표 (x, y, 높이)

    approach ──(직진)──▶ dock ──(포크 앞 1.2 m)──▶ place

사용:
    exec(open('/home/ubuntu/forklift_ws/nav2/scripts/rack_coords.py').read())

    pick_prim()                  # Stage 에서 선택한 프림의 좌표·크기
    scan_shelves(x_max=3.0)      # 왼쪽 랙(x<3) 에서 선반 후보 찾기
    scan_shelves(9.0, 12.5)      # 가운데 랙에서 찾기
    make_slot(shelf_x=1.7, y=9.4, shelf_z=1.35, side="left")
                                 # 세 좌표를 계산해 RACK_SLOTS 형식으로 출력
"""
import math

import omni.usd
from pxr import Usd, UsdGeom

_st = omni.usd.get_context().get_stage()
_cache = UsdGeom.BBoxCache(Usd.TimeCode.Default(), [UsdGeom.Tokens.default_])

# 차량 제원 — cargo_demo / kinematic_vehicle 과 같아야 한다
FORK_OFFSET_X = 1.2      # base_link 에서 포크 끝(화물 중심)까지
DOCK_BACK = 0.0          # dock 을 더 뒤로 뺄 여유 (지게차가 깊이 들어가면 키운다)
APPROACH_GAP = 2.1       # dock 에서 approach 까지 (Nav2 가 갈 안전 거리)


def _bbox(prim):
    r = _cache.ComputeWorldBound(prim).ComputeAlignedRange()
    return r.GetMin(), r.GetMax()


def pick_prim():
    """Stage 패널에서 선택한 프림의 위치·크기를 보여준다.

    랙 선반을 클릭한 뒤 이걸 실행하면 place 에 쓸 값이 바로 나온다.
    """
    sel = omni.usd.get_context().get_selection().get_selected_prim_paths()
    if not sel:
        print("Stage 패널에서 프림을 먼저 선택하세요")
        return None
    out = []
    for path in sel:
        p = _st.GetPrimAtPath(path)
        if not p.IsValid():
            continue
        mn, mx = _bbox(p)
        print(f"\n{path}")
        print(f"  타입   : {p.GetTypeName()}")
        print(f"  x 범위 : {mn[0]:7.3f} ~ {mx[0]:7.3f}   (폭 {mx[0]-mn[0]:.3f})")
        print(f"  y 범위 : {mn[1]:7.3f} ~ {mx[1]:7.3f}   (깊이 {mx[1]-mn[1]:.3f})")
        print(f"  z 범위 : {mn[2]:7.3f} ~ {mx[2]:7.3f}   (높이 {mx[2]-mn[2]:.3f})")
        print(f"  중심   : ({(mn[0]+mx[0])/2:.3f}, {(mn[1]+mx[1])/2:.3f})")
        print(f"  윗면 z : {mx[2]:.3f}   ← 화물이 얹힐 높이(shelf_z) 후보")
        out.append((path, mn, mx))
    return out


def scan_shelves(x_min=None, x_max=None, z_max=2.5, min_w=0.6):
    """지정 구간에서 '선반처럼 생긴' 면을 찾는다.

    선반 = 얇고(높이 작음) 넓은(가로·세로 큼) 판.
    포크가 닿는 높이(z_max)까지만 본다. 기본 2.5 m.
    """
    found = []
    for p in _st.Traverse():
        if not p.IsA(UsdGeom.Gprim):
            continue
        path = str(p.GetPath())
        if "Forklift" in path or "/World/Cargo" in path:
            continue
        try:
            mn, mx = _bbox(p)
        except Exception:
            continue
        w, d, h = mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2]
        cx = (mn[0] + mx[0]) / 2
        if x_min is not None and cx < x_min:
            continue
        if x_max is not None and cx > x_max:
            continue
        if mx[2] > z_max or mx[2] < 0.2:      # 포크가 닿는 높이만
            continue
        if h > 0.5:                            # 너무 두꺼우면 선반이 아니다
            continue
        if w < min_w and d < min_w:            # 너무 작으면 부속품
            continue
        found.append((mx[2], path, mn, mx))

    found.sort()
    print(f"선반 후보 {len(found)}개  (x {x_min}~{x_max}, z ≤ {z_max})")
    print(f"{'윗면z':>7s} {'x 범위':>16s} {'y 범위':>16s}  경로")
    for z, path, mn, mx in found[:25]:
        print(f"{z:7.3f} {mn[0]:7.2f}~{mx[0]:6.2f} {mn[1]:7.2f}~{mx[1]:6.2f}  "
              f"{path[-40:]}")
    if not found:
        print("  없음 — z_max 를 키우거나 min_w 를 줄여 보세요")
        print("  또는 Stage 에서 선반을 직접 클릭하고 pick_prim() 을 쓰세요")
    return found


def make_slot(shelf_x, y, shelf_z, side="left", name="A1"):
    """선반 좌표에서 approach / dock / place 를 계산해 준다.

    shelf_x : 화물을 놓을 선반의 x (파레트 중심이 올 자리)
    y       : 슬롯의 y
    shelf_z : 선반 윗면 높이 (포크를 올릴 높이)
    side    : "left"  랙이 왼쪽 → 지게차는 서쪽(-x)을 보고 접근
              "right" 랙이 오른쪽(가운데 랙의 오른면 등) → 동일하게 서쪽 접근
              "east"  랙이 동쪽에 있어 동쪽(+x)을 보고 접근
    """
    if side == "east":
        yaw = 0.0
        dock_x = shelf_x - FORK_OFFSET_X - DOCK_BACK
        appr_x = dock_x - APPROACH_GAP
    else:
        yaw = math.pi
        dock_x = shelf_x + FORK_OFFSET_X + DOCK_BACK
        appr_x = dock_x + APPROACH_GAP

    print(f'    "{name}": {{"approach": ({appr_x:.2f}, {y:.2f}, {yaw:.4f}),')
    print(f'           "dock":     ({dock_x:.2f}, {y:.2f}, {yaw:.4f}),')
    print(f'           "place":    ({shelf_x:.2f}, {y:.2f}, {shelf_z:.2f})}},')
    print()
    print(f"  포크 높이(shelfHeight) = {shelf_z:.2f} m")
    print(f"  도킹 직진 거리          = {APPROACH_GAP:.2f} m")
    print(f"  실물 기준(÷10)          approach ({appr_x/10:.3f}, {y/10:.3f}) "
          f"dock ({dock_x/10:.3f}, {y/10:.3f}) 높이 {shelf_z/10:.3f}")
    return {"approach": (round(appr_x, 2), round(y, 2), round(yaw, 4)),
            "dock": (round(dock_x, 2), round(y, 2), round(yaw, 4)),
            "place": (round(shelf_x, 2), round(y, 2), round(shelf_z, 2))}


def check_reachable(x, y):
    """그 자리에 3.8 x 1.9 차체가 설 수 있는지 맵으로 확인한다."""
    try:
        import numpy as np
        from PIL import Image
    except ImportError:
        print("numpy/PIL 없음 — 확인 생략")
        return None
    img = np.array(Image.open(
        "/home/ubuntu/forklift_ws/nav2/maps/sim_warehouse.pgm"))
    h, w = img.shape
    res, hl, hw, m = 0.05, 1.9, 0.95, 0.3
    x0, x1 = int((x - hl - m) / res), int((x + hl + m) / res)
    y0, y1 = int(h - (y + hw + m) / res), int(h - (y - hw - m) / res)
    if x0 < 0 or y0 < 0 or x1 >= w or y1 >= h:
        print(f"({x}, {y}) 맵 밖")
        return False
    ok = bool((img[y0:y1, x0:x1] > 250).all())
    print(f"({x}, {y}) : {'주행 가능' if ok else '충돌 — Nav2 가 못 감'}")
    return ok


print("rack_coords 로드됨")
print("  1) Stage 에서 랙 선반을 클릭 → pick_prim()")
print("  2) 또는 scan_shelves(x_max=3.0) 로 왼쪽 랙 훑기")
print("  3) make_slot(shelf_x=1.7, y=9.4, shelf_z=1.35) 로 세 좌표 생성")
print("  4) check_reachable(5.0, 9.4) 로 approach 주행 가능 확인")
