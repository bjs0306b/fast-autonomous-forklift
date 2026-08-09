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


def near(x, y, r=3.0, z_max=1.0, skip=("Forklift", "Cargo")):
    """그 자리 근처의 프림을 찾는다 — 바닥 표시 등을 찾을 때.

        near(17, 5)            # 옛 입고 바이 근처
        near(16.5, 5, r=2)     # 새 바이 근처

    z_max 로 바닥 근처만 본다 (기본 1.0 = 실물 100 mm).
    """
    hits = []
    for p in _st.Traverse():
        if not p.IsA(UsdGeom.Gprim):
            continue
        path = str(p.GetPath())
        if any(k.lower() in path.lower() for k in skip):
            continue
        try:
            mn, mx = _bbox(p)
        except Exception:
            continue
        w = float(mx[0] - mn[0])
        if w <= 0 or w > 100:
            continue
        cx = (float(mn[0]) + float(mx[0])) / 2
        cy = (float(mn[1]) + float(mx[1])) / 2
        if float(mn[2]) > z_max:
            continue
        d = math.hypot(cx - x, cy - y)
        if d <= r:
            hits.append((d, path, cx, cy, float(mn[2]), float(mx[2])))
    hits.sort()
    print(f"({x}, {y}) 반경 {r} 안, 높이 {z_max} 이하 — {len(hits)}개\n")
    print(f"{'거리':>6} {'중심 x':>8} {'중심 y':>8} {'z':>13}  프림")
    for d, path, cx, cy, z0, z1 in hits[:25]:
        print(f"{d:6.2f} {cx:8.2f} {cy:8.2f} {z0:6.2f}~{z1:5.2f}  {path[-44:]}")
    return hits


def move_prim(path, dx=0.0, dy=0.0, dz=0.0):
    """프림을 상대적으로 옮긴다.

        move_prim("/World/warehouse/SM_FloorMark_1", dx=-0.5)

    바이를 17.0 -> 16.5 로 옮겼으므로, 바닥 표시도 dx=-0.5 하면 된다.
    """
    prim = _st.GetPrimAtPath(path)
    if not prim.IsValid():
        print(f"{path} 없음")
        return False
    api = UsdGeom.XformCommonAPI(prim)
    t = api.GetXformVectors(Usd.TimeCode.Default())[0]
    new_t = (float(t[0]) + dx, float(t[1]) + dy, float(t[2]) + dz)
    api.SetTranslate(new_t)
    print(f"{path.split('/')[-1]}  ({float(t[0]):.2f},{float(t[1]):.2f}) "
          f"-> ({new_t[0]:.2f},{new_t[1]:.2f})")
    return True


def slice_bounds(z=2.0, skip=("Forklift", "Cargo", "Palette", "Bracket",
                               "Ceiling", "Roof", "Light")):
    """높이 z 에서 수평으로 자른 단면에 걸리는 것들을 찾는다.

    2D 라이다가 그 높이에서 무엇을 보는지 그대로 보여준다. 맵에 무엇이
    그려져야 하는지 정할 때 쓴다.

        slice_bounds(2.0)     # 시뮬 2.0 = 실물 200 mm (라이다 높이)
        slice_bounds(0.7)     # 실물 70 mm
        slice_bounds(2.0, skip=())   # 아무것도 빼지 않고

    기본으로 지게차·화물·천장 구조물은 뺀다 (맵에 들어가면 안 되거나
    라이다 높이와 무관한 것들).
    """
    hits = []
    for p in _st.Traverse():
        if not p.IsA(UsdGeom.Gprim):
            continue
        path = str(p.GetPath())
        if any(k.lower() in path.lower() for k in skip):
            continue
        try:
            mn, mx = _bbox(p)
        except Exception:
            continue
        if float(mx[0] - mn[0]) > 100 or float(mx[0]) <= float(mn[0]):
            continue
        if not (float(mn[2]) <= z <= float(mx[2])):
            continue                     # 그 높이를 지나지 않는다
        hits.append((path, mn, mx))

    if not hits:
        print(f"높이 {z} 에 걸리는 것이 없습니다 (실물 {z*100:.0f} mm)")
        return []

    x0 = min(float(a[0]) for _, a, b in hits)
    x1 = max(float(b[0]) for _, a, b in hits)
    y0 = min(float(a[1]) for _, a, b in hits)
    y1 = max(float(b[1]) for _, a, b in hits)
    print(f"높이 z={z}  (실물 {z*100:.0f} mm) 단면 — {len(hits)}개")
    print(f"  전체 범위  x {x0:6.2f} ~ {x1:6.2f}   y {y0:6.2f} ~ {y1:6.2f}")
    print(f"  실물 mm    x {x0*100:5.0f} ~ {x1*100:5.0f}   "
          f"y {y0*100:5.0f} ~ {y1*100:5.0f}")
    print()
    print(f"  {'x 범위':>15s} {'y 범위':>17s}  프림")
    for path, mn, mx in sorted(hits, key=lambda h: float(h[1][0]))[:40]:
        print(f"  {float(mn[0]):6.2f}~{float(mx[0]):6.2f} "
              f"{float(mn[1]):7.2f}~{float(mx[1]):7.2f}  {path[-44:]}")
    if len(hits) > 40:
        print(f"  ... 외 {len(hits)-40}개")
    return hits


def world_bounds(path="/World/warehouse", z_max=3.0):
    """창고 구조물의 실제 범위를 잰다 — 맵의 (0,0) 이 맞는지 확인용.

        world_bounds()                 # 창고 전체
        world_bounds(z_max=2.0)        # 라이다 높이(실물 200mm) 이하만

    맵은 이미지 좌하단을 (0,0) 으로 놓는다. 씬의 벽이 실제로 거기서
    시작하는지 이걸로 확인한다.
    """
    prim = _st.GetPrimAtPath(path)
    if not prim.IsValid():
        print(f"{path} 가 없습니다. Stage 에서 경로를 확인하세요")
        return None
    mn, mx = _bbox(prim)
    print(f"{path}")
    print(f"  x {float(mn[0]):7.2f} ~ {float(mx[0]):7.2f}   "
          f"(폭 {float(mx[0]-mn[0]):6.2f})")
    print(f"  y {float(mn[1]):7.2f} ~ {float(mx[1]):7.2f}   "
          f"(길이 {float(mx[1]-mn[1]):6.2f})")
    print(f"  z {float(mn[2]):7.2f} ~ {float(mx[2]):7.2f}")
    print()
    print("  모서리 (시뮬 / 실물 mm)")
    for name, x, y in (("좌하단", mn[0], mn[1]), ("우하단", mx[0], mn[1]),
                       ("좌상단", mn[0], mx[1]), ("우상단", mx[0], mx[1])):
        print(f"    {name}  ({float(x):6.2f}, {float(y):6.2f})   "
              f"({float(x)*100:6.0f}, {float(y)*100:6.0f}) mm")
    print()
    print(f"  맵 기준(0,0)~(20,30) 과의 차이:")
    print(f"    좌하단  x {float(mn[0])*100:+.0f} mm,  y {float(mn[1])*100:+.0f} mm")
    print(f"    우상단  x {(float(mx[0])-20)*100:+.0f} mm,  "
          f"y {(float(mx[1])-30)*100:+.0f} mm")
    return {"x": (float(mn[0]), float(mx[0])), "y": (float(mn[1]), float(mx[1]))}


def rack_list(pat="SM_Rack", z_max=None, sort="x"):
    """랙 프림을 하나씩 나열한다. 묶지 않고 그대로 보여준다.

    기본 패턴이 "SM_Rack" 인 이유: "Bracket" 에 "rack" 이 들어 있어서
    "Rack" 으로 찾으면 천장 브래킷·빔까지 딸려 온다.

        rack_list()                  # 이름에 Rack 이 든 것 전부
        rack_list("RackShelf")       # 선반만
        rack_list("Rack", z_max=0.3) # 라이다 높이(실물 30mm)에 걸치는 것만
        rack_list(sort="y")          # y 순으로

    z_max 를 주면 **바닥에서 그 높이까지 걸치는** 프림만 본다.
    2D 라이다가 무엇을 보는지 확인할 때 쓴다.
    """
    matched = [p for p in _st.Traverse() if pat.lower() in p.GetName().lower()]
    paths = {str(p.GetPath()) for p in matched}
    rows = []
    for p in matched:
        sp = str(p.GetPath())
        if any(q != sp and q.startswith(sp + "/") for q in paths):
            continue                       # 부모 그룹은 건너뛴다
        try:
            mn, mx = _bbox(p)
        except Exception:
            continue
        w = float(mx[0] - mn[0])
        if w <= 0 or w > 100:
            continue
        if z_max is not None and float(mn[2]) > z_max:
            continue                       # 그 높이보다 위에만 있는 것
        rows.append((str(p.GetPath()), mn, mx))

    key = 0 if sort == "x" else 1
    rows.sort(key=lambda r: float(r[1][key]))
    print(f"{'x 범위':>15s} {'y 범위':>17s} {'z 범위':>14s}  프림")
    for path, mn, mx in rows:
        print(f"{float(mn[0]):6.2f}~{float(mx[0]):6.2f} "
              f"{float(mn[1]):7.2f}~{float(mx[1]):7.2f} "
              f"{float(mn[2]):6.2f}~{float(mx[2]):6.2f}  {path[-46:]}")
    print(f"{len(rows)}개")
    return rows


def rack_bounds(names=("SM_Rack",), gap=0.8):
    """씬의 랙 프림을 찾아 xy 점유 범위를 잰다.

    기본 패턴이 "SM_Rack" 인 이유: "B(rack)et" 때문에 "Rack" 으로 찾으면
    천장 브래킷이 섞여 랙이 창고 전체 길이로 보인다.

    슬롯 좌표(approach/dock/place)가 아니라 **랙 구조물 자체**가
    차지하는 영역이다. costmap 이나 세트장 시공에 쓴다.

        rack_bounds()               # 전부
        rack_bounds(("Rack",))      # 이름에 Rack 이 든 것만
    """
    # 이름이 맞는 프림을 모으되, **부모는 뺀다**.
    # 랙들을 감싸는 그룹 Xform 이 같이 잡히면 그 bbox 가 창고 전체를
    # 덮어버려 랙 하나가 30 m 짜리로 보인다.
    matched = [p for p in _st.Traverse()
               if any(k.lower() in p.GetName().lower() for k in names)]
    paths = {str(p.GetPath()) for p in matched}
    leaves = [p for p in matched
              if not any(q != str(p.GetPath())
                         and q.startswith(str(p.GetPath()) + "/")
                         for q in paths)]

    hits = []
    for p in leaves:
        try:
            mn, mx = _bbox(p)
        except Exception:
            continue
        w, d = float(mx[0] - mn[0]), float(mx[1] - mn[1])
        if w <= 0 or w > 100 or d <= 0:
            continue                     # 무한대·빈 bbox 제외
        hits.append((str(p.GetPath()), mn, mx))

    if not hits:
        print("랙 프림을 못 찾았습니다. Stage 에서 이름을 확인하고")
        print("  rack_bounds(('원하는이름',)) 로 다시 시도하세요")
        return []

    # x 중심으로 묶는다 (A랙 / B랙)
    groups = {}
    for path, mn, mx in hits:
        cx = (float(mn[0]) + float(mx[0])) / 2
        key = None
        for k in groups:
            if abs(k - cx) < gap:         # 이 거리 안이면 같은 랙
                key = k
                break
        if key is None:
            groups[cx] = []
            key = cx
        groups[key].append((mn, mx))

    print(f"{'랙':6s} {'x 범위':>16s} {'폭':>7s} "
          f"{'y 범위':>18s} {'길이':>8s} {'프림':>5s}")
    out = []
    for i, (cx, items) in enumerate(sorted(groups.items())):
        x0 = min(float(a[0]) for a, b in items)
        x1 = max(float(b[0]) for a, b in items)
        y0 = min(float(a[1]) for a, b in items)
        y1 = max(float(b[1]) for a, b in items)
        z1 = max(float(b[2]) for a, b in items)
        name = chr(ord("A") + i)
        print(f"{name:6s} {x0:7.2f}~{x1:6.2f} {x1-x0:7.2f} "
              f"{y0:8.2f}~{y1:7.2f} {y1-y0:8.2f} {len(items):5d}")
        print(f"       실물 mm: x {x0*100:.0f}~{x1*100:.0f} "
              f"(폭 {(x1-x0)*100:.0f})  y {y0*100:.0f}~{y1*100:.0f}  "
              f"높이 {z1*100:.0f}")
        out.append({"name": name, "x": (x0, x1), "y": (y0, y1), "top_z": z1})
    return out


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
