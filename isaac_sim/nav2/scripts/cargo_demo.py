"""화물을 포크에 바로 얹고 랙까지 운반하는 데모 (Isaac Script Editor 용).

kinematic_vehicle.py 를 먼저 실행해 fleet 가 있어야 한다.

방식(A): 지게차가 입고 위치에 있을 때 카메라가 잰 높이를 받으면, 그 크기의
팔레트+박스를 지게차 포크 위치에 바로 생성해 포크에 얹는다. 바닥에 놓고 집는
과정은 없다 — 실물은 이미 포크에 화물을 꽂은 채 입고 구역에 오고, 카메라 측정으로
크기만 정해지기 때문이다.

흐름:
    지게차 입고 위치 정지
      -> 높이 받음 -> 포크에 화물 생성(바로 얹힘)
      -> 랙으로 운반 -> 랙에 내려놓기 -> 후진

사용:
    exec(open('/home/ubuntu/forklift_ws/nav2/scripts/kinematic_vehicle.py').read())
    exec(open('/home/ubuntu/forklift_ws/nav2/scripts/cargo_demo.py').read())

    load(height_real=0.15)          # 포크에 화물 생성 (지게차 현재 위치)
    deliver(rack="A1")              # 랙으로 운반 후 내려놓기
    # 또는 한 번에:
    demo(height_real=0.15, rack="A1")

    clear_cargo()                   # 남은 화물 프림 정리
"""
import math
import time

import omni.usd
from pxr import Usd, UsdGeom, UsdPhysics, Gf, Sdf

_stage = omni.usd.get_context().get_stage()

# ---------------------------------------------------------------------------
# 설정 — 씬에서 확인한 값
# ---------------------------------------------------------------------------
SCALE = 10.0                         # 실물 = 시뮬 / SCALE. 높이는 실물기준 입력.
RACK_YAW = 0.0                       # 화물을 놓을 때 정렬할 랙 방향(rad).
#                                      선반과 평행하게. 화물이 45도 틀어져 놓이면
#                                      1.5708(90도) 로 바꿔볼 것.
PALLET_W, PALLET_D, PALLET_H = 1.0, 1.21, 0.21   # 팔레트 크기(m, 시뮬)

# ── 상자 크기 ──────────────────────────────────────────────────────
# 높이는 카메라가 잰 값(실물 m)에 SCALE 을 곱해 정한다. 실물 0.15 m 면
# 시뮬 1.5 m 짜리 상자가 되어 파레트보다 훨씬 커 보인다. 보기 좋게
# 줄이려면 아래 값을 조절한다.
BOX_W_FRAC = 0.75            # 파레트 폭 대비 상자 폭
BOX_D_FRAC = 0.75            # 파레트 깊이 대비 상자 깊이
BOX_H_SCALE = 0.6            # 잰 높이에 곱할 배율 (1.0 이면 그대로)
BOX_H_MAX = 0.9              # 상자 높이 상한 (m, 시뮬)


def box_size(h):
    """잰 높이 h 로부터 실제로 만들 상자 크기를 정한다."""
    return (PALLET_W * BOX_W_FRAC, PALLET_D * BOX_D_FRAC,
            min(h * BOX_H_SCALE, BOX_H_MAX))


def set_box_size(w_frac=None, d_frac=None, h_scale=None, h_max=None):
    """상자 크기를 실행 중에 바꾼다 (다음 화물부터 적용).

        set_box_size(h_scale=0.4)          # 더 낮게
        set_box_size(w_frac=0.6, d_frac=0.6)  # 더 좁게
    """
    global BOX_W_FRAC, BOX_D_FRAC, BOX_H_SCALE, BOX_H_MAX
    if w_frac is not None:
        BOX_W_FRAC = float(w_frac)
    if d_frac is not None:
        BOX_D_FRAC = float(d_frac)
    if h_scale is not None:
        BOX_H_SCALE = float(h_scale)
    if h_max is not None:
        BOX_H_MAX = float(h_max)
    bw, bd, bh = box_size(0.15 * SCALE)
    print(f"상자 크기: 폭 {bw:.2f} 깊이 {bd:.2f}  "
          f"(실물 0.15 m 입력시 높이 {bh:.2f} m)")

# ── 파레트 에셋 ────────────────────────────────────────────────────
# 씬에 있는 진짜 파레트 프림을 복제해서 화물로 쓴다. 못 찾으면 나무색
# 네모 상자로 대신한다(예전 방식).
#   PALLET_SRC : 프림 이름(부분 일치) 또는 전체 경로
#   PALLET_FIT : 가로 폭을 PALLET_W 에 맞춰 균일 스케일 (10배 스케일 씬 대응)
PALLET_SRC = "SM_PaletteA_325"
# 복제한 에셋의 크기를 무엇에 맞출지.
#   "height"  파레트 두께를 PALLET_H(0.21 m)에 맞춘다  ← 상자가 뜨지 않는다
#   "width"   가로 폭을 PALLET_W 에 맞춘다
#   None      원본 크기 그대로
# 상자는 잰 파레트 윗면 위에 얹히므로, 파레트가 두꺼우면 상자가 높이 뜬다.
PALLET_FIT = "height"

_pallet_path = [None]        # 찾은 경로 캐시
_pallet_top = [PALLET_H]     # 마지막으로 만든 파레트의 윗면 높이
_pallet_how = [None]         # 복제 방식 (한 번만 알린다)
_pallet_size = [None]        # 원본 크기 (한 번만 알린다)
_bbox_cache = [None]


def find_pallets(pat="palette"):
    """씬에서 파레트처럼 보이는 프림을 찾아 보여준다."""
    pat = pat.lower()
    hits = [str(x.GetPath()) for x in _stage.Traverse()
            if pat in x.GetName().lower()]
    for h in hits[:40]:
        print("  " + h)
    print(f"{len(hits)}개")
    return hits


def use_pallet_prim(name_or_path):
    """쓸 파레트 프림을 바꾼다 (Isaac 재시작 없이)."""
    global PALLET_SRC
    PALLET_SRC = name_or_path
    _pallet_path[0] = None
    print(f"파레트 원본: {_pallet_src() or '못 찾음 — 상자로 대신함'}")


def set_pallet_fit(mode="height", show=True):
    """파레트 크기 기준을 바꾼다 — "height" / "width" / None.

    상자가 파레트 위에 붕 떠 보이면 "height" 로 두면 된다.
    파레트가 너무 작거나 크게 보이면 "width" 를 써 본다.
    """
    global PALLET_FIT, _pallet_size
    PALLET_FIT = mode
    _pallet_size[0] = None
    print(f"파레트 크기 기준: {mode}")
    if show:
        pallet_info()


def pallet_info():
    """지금 쓰는 파레트의 원본 크기와 적용 결과를 보여준다."""
    src = _pallet_src()
    print(f"  원본     : {src or '(못 찾음 — 상자로 대체)'}")
    print(f"  복제 방식: {_pallet_how[0]}")
    print(f"  원본 크기: {_pallet_size[0] or '(아직 안 잼)'}")
    print(f"  크기 기준: {PALLET_FIT}   목표 두께 {PALLET_H} m")
    print(f"  마지막 윗면 높이(상자가 얹히는 z): {_pallet_top[0]:.3f} m")


def _pallet_src():
    """PALLET_SRC 를 실제 프림 경로로 푼다."""
    if _pallet_path[0] is not None:
        return _pallet_path[0] or None
    src = None
    if PALLET_SRC.startswith("/"):
        if _stage.GetPrimAtPath(PALLET_SRC).IsValid():
            src = PALLET_SRC
    else:
        want = PALLET_SRC.lower()
        for x in _stage.Traverse():
            if x.GetName().lower() == want:
                src = str(x.GetPath())
                break
        if src is None:                       # 부분 일치로 한 번 더
            for x in _stage.Traverse():
                if want in x.GetName().lower():
                    src = str(x.GetPath())
                    break
    _pallet_path[0] = src or ""
    if src is None:
        print(f"[cargo] 파레트 프림 '{PALLET_SRC}' 못 찾음 — 상자로 대신합니다."
              f" find_pallets() 로 이름을 확인하세요")
    else:
        print(f"[cargo] 파레트 원본: {src}")
    return src


def _world_range(path):
    if _bbox_cache[0] is None:
        _bbox_cache[0] = UsdGeom.BBoxCache(
            Usd.TimeCode.Default(), [UsdGeom.Tokens.default_])
    _bbox_cache[0].Clear()
    r = _bbox_cache[0].ComputeWorldBound(
        _stage.GetPrimAtPath(path)).ComputeAlignedRange()
    return r.GetMin(), r.GetMax()


def _box_pallet(dst):
    """예전 방식 — 나무색 네모 상자. 에셋 복제가 안 될 때 쓴다."""
    pal = UsdGeom.Cube.Define(_stage, dst)
    pal.CreateSizeAttr(1.0)
    UsdGeom.XformCommonAPI(pal).SetScale((PALLET_W, PALLET_D, PALLET_H))
    UsdGeom.XformCommonAPI(pal).SetTranslate((0.0, 0.0, PALLET_H / 2))
    pal.CreateDisplayColorAttr([Gf.Vec3f(0.55, 0.4, 0.25)])
    _pallet_top[0] = PALLET_H
    return PALLET_H


def _copy_prim(src, dst):
    """참조된 에셋도 복제되도록 여러 방법을 순서대로 시도한다.

    씬의 파레트는 보통 참조(reference)로 들어와 있어 루트 레이어에 스펙이
    없다. 그래서 Sdf.CopySpec 만 쓰면 실패한다. Kit 의 CopyPrim 은 합성된
    결과를 복제하므로 이게 1순위다.
    """
    try:
        import omni.kit.commands
        omni.kit.commands.execute("CopyPrim", path_from=src, path_to=dst,
                                  exclusive_select=False)
        if _stage.GetPrimAtPath(dst).IsValid():
            return "CopyPrim"
    except Exception as e:
        print(f"[cargo] CopyPrim 실패: {e}")
    try:                                   # 내부 참조 — 복제 없이 같은 걸 가리킴
        pr = _stage.DefinePrim(dst, "Xform")
        pr.GetReferences().AddInternalReference(Sdf.Path(src))
        if pr.IsValid() and pr.GetChildren():
            return "InternalReference"
        _stage.RemovePrim(Sdf.Path(dst))
    except Exception as e:
        print(f"[cargo] 내부참조 실패: {e}")
    try:
        layer = _stage.GetRootLayer()
        if Sdf.CopySpec(layer, Sdf.Path(src), layer, Sdf.Path(dst)):
            return "CopySpec"
    except Exception as e:
        print(f"[cargo] CopySpec 실패: {e}")
    return None


def _make_pallet(root, origin):
    """{root}/pallet 을 만든다. 반환값은 파레트 윗면 높이(박스를 얹을 z).

    origin 은 그룹(root)의 world 위치. 복제한 에셋의 바닥 중심이 그룹
    원점에 오도록 옮겨 준다.

    어떤 이유로든 실패하면 상자로 대신한다. 파레트 모양 때문에 적재 자체가
    실패하면 차량이 빈 포크로 계속 순환로를 돌게 되므로, 여기서는 절대
    예외를 밖으로 던지지 않는다.
    """
    dst = f"{root}/pallet"
    src = _pallet_src()
    if src is None:
        return _box_pallet(dst)

    try:
        xf = UsdGeom.Xform.Define(_stage, dst)
        inner = f"{dst}/model"
        how = _copy_prim(src, inner)
        if how is None:
            print(f"[cargo] '{src}' 복제 실패 — 상자로 대신합니다")
            _stage.RemovePrim(Sdf.Path(dst))
            _pallet_path[0] = ""            # 다음부터는 바로 상자
            return _box_pallet(dst)
        if _pallet_how[0] != how:
            _pallet_how[0] = how
            print(f"[cargo] 파레트 복제 방식: {how}")

        # 복제본은 물리를 끈다. 켜져 있으면 PhysX 가 바닥으로 떨어뜨린다.
        for pr in Usd.PrimRange(_stage.GetPrimAtPath(inner)):
            if pr.HasAPI(UsdPhysics.RigidBodyAPI):
                UsdPhysics.RigidBodyAPI(pr).CreateRigidBodyEnabledAttr(False)
            if pr.HasAPI(UsdPhysics.CollisionAPI):
                UsdPhysics.CollisionAPI(pr).CreateCollisionEnabledAttr(False)

        # 크기를 맞춘다 (원본이 1/10 스케일이거나 통짜 크기일 수 있다)
        mn, mx = _world_range(inner)
        w = max(float(mx[0] - mn[0]), float(mx[1] - mn[1]))
        hgt = float(mx[2] - mn[2])
        if w < 1e-6 or hgt < 1e-6:
            raise RuntimeError("복제본의 크기를 잴 수 없음")
        if _pallet_size[0] is None:
            _pallet_size[0] = (w, hgt)
            print(f"[cargo] 파레트 원본 크기: 폭 {w:.3f} m, 두께 {hgt:.3f} m")
        k = 1.0
        if PALLET_FIT == "height":
            k = PALLET_H / hgt
        elif PALLET_FIT == "width":
            k = max(PALLET_W, PALLET_D) / w
        if abs(k - 1.0) > 0.02:
            UsdGeom.XformCommonAPI(xf).SetScale((k, k, k))
            mn, mx = _world_range(inner)

        # 바닥 중심을 그룹 원점에 맞춘다
        cx = (float(mn[0]) + float(mx[0])) / 2 - origin[0]
        cy = (float(mn[1]) + float(mx[1])) / 2 - origin[1]
        bz = float(mn[2]) - origin[2]
        t = UsdGeom.XformCommonAPI(xf).GetXformVectors(Usd.TimeCode.Default())[0]
        UsdGeom.XformCommonAPI(xf).SetTranslate(
            (float(t[0]) - cx, float(t[1]) - cy, float(t[2]) - bz))
        _pallet_top[0] = float(mx[2]) - float(mn[2])
        return _pallet_top[0]
    except Exception as e:
        print(f"[cargo] 파레트 에셋 처리 실패({e}) — 상자로 대신합니다")
        try:
            _stage.RemovePrim(Sdf.Path(dst))
        except Exception:
            pass
        _pallet_path[0] = ""
        return _box_pallet(dst)


CARGO_ROOT = "/World/Cargo"          # 화물 프림을 모아 두는 곳

# ── 랙 슬롯 좌표 ───────────────────────────────────────────────────
# 사용자가 잰 최종 적재 위치(파레트 중심):
#   A랙  (0.95, 9.3) ~ (0.95, 23.8)   왼쪽 벽
#   B랙  (10.20, 9.3) ~ (10.20, 23.8) 가운데
# 각 슬롯은 세 좌표를 갖는다.
#   approach  통로 위 Nav2 목표
#   dock      포크를 랙에 넣었을 때 지게차 중심 (여기부터는 Nav2 를 안 쓴다)
#   place     화물이 놓일 선반 좌표 (x, y, 높이)
_SLOT_Y = [9.3, 10.5, 11.8, 13.2, 14.5, 15.8, 17.2, 18.5, 19.7, 21.2, 22.5, 23.8]
_SHELF_Z = 1.325                     # 선반 윗면 높이 (포크를 올릴 높이)
_YAW_WEST = 3.1416                   # 서쪽(-x)을 보고 랙에 넣는다
# 차체 앞면이 랙 진입면을 넘어 들어가는 깊이. 0 이면 앞면이 랙 면에 딱 붙는다.
# 화물은 place 가 선반 절대좌표에 놓으므로, 이 값은 "보기" 만 결정한다.
_DOCK_DEPTH = 0.80                   # 랙 면 안쪽으로 넣을 깊이 (m)
_HALF_LEN = 1.9                      # 차체 중심에서 포크 끝까지
_RACKS = {
    "A": {"place_x": 0.95,  "face_x": 1.50,  "approach_x": 5.00},
    "B": {"place_x": 10.20, "face_x": 10.85, "approach_x": 14.50},
}
for _c in _RACKS.values():
    _c["dock_x"] = _c["face_x"] + _HALF_LEN - _DOCK_DEPTH

RACK_SLOTS = {}
for _g, _cfg in _RACKS.items():
    for _i, _y in enumerate(_SLOT_Y, start=1):
        RACK_SLOTS[f"{_g}{_i}"] = {
            "approach": (_cfg["approach_x"], _y, _YAW_WEST),
            "dock":     (_cfg["dock_x"],     _y, _YAW_WEST),
            "place":    (_cfg["place_x"],    _y, _SHELF_Z),
        }


def rack_info(rack=None):
    """랙 슬롯 좌표를 보여준다."""
    for k in ([rack] if rack else sorted(RACK_SLOTS,
                                         key=lambda x: (x[0], int(x[1:])))):
        v = RACK_SLOTS[k]
        print(f"  {k:4s} approach {v['approach'][0]:6.2f},{v['approach'][1]:6.2f}"
              f"   dock {v['dock'][0]:6.2f},{v['dock'][1]:6.2f}"
              f"   place {v['place'][0]:6.2f},{v['place'][1]:6.2f},"
              f"{v['place'][2]:.2f}")


def drop_here(vehicle="SIM_F02"):
    """지금 있는 자리 바로 앞 바닥에 화물을 내려놓는다."""
    v = fleet.vehicles.get(vehicle)          # noqa: F821
    if v is None:
        print(f"차량 '{vehicle}' 없음")
        return False
    if v.cargo is None and v.cargo_id is None:
        print(f"{vehicle} 들고 있는 화물이 없음")
        return False
    fx, fy, fz, fyaw = v.fork_tip_pose()
    v._steps = [
        ("fork", v.p["lift_min"], 12.0),     # 포크를 내리고
        ("settle", 2.0),                     # 완전히 멈춘 뒤
        ("place", fx, fy, 0.0, fyaw),        # 바닥에 놓는다
        ("reverse", 1.5),                    # 빠져나온다
    ]
    v._step_t = 0.0
    print(f"{vehicle} 화물을 ({fx:.2f}, {fy:.2f}) 바닥에 내려놓는다")
    return True


# ── 입고 바이 — 적재를 받으려면 정렬돼 있어야 한다 ──────────────────
# 실제로도 포크가 파레트 정면을 봐야 꽂힌다. 바이 근처에서 방향이 어긋나 있으면
# 정렬시킨 뒤 다시 시도한다. 바이에서 멀리(시험용) 떨어져 있으면 검사하지 않는다.
LOAD_SPOT = (17.0, 5.0)      # 입고 바이 위치
LOAD_YAW = 0.0               # 이 방향(동쪽 +x)을 보고 있어야 한다
LOAD_YAW_TOL = 0.52          # 허용 오차 (rad) ≈ 30도
LOAD_POS_TOL = 3.5           # 바이에서 이 거리 안이면 각도를 검사한다
LOAD_GATE = True             # False 로 두면 각도를 보지 않는다


def set_load_spot(x, y, yaw=0.0, tol_deg=30.0):
    """입고 바이 위치·방향을 실행 중에 바꾼다."""
    global LOAD_SPOT, LOAD_YAW, LOAD_YAW_TOL
    LOAD_SPOT = (float(x), float(y))
    LOAD_YAW = float(yaw)
    LOAD_YAW_TOL = math.radians(float(tol_deg))
    print(f"입고 바이: {LOAD_SPOT} 방향 {math.degrees(LOAD_YAW):.0f}도 "
          f"허용 ±{tol_deg:.0f}도")


def check_load_pose(v, vehicle):
    """바이에서 적재 가능한 자세인지. (가능, 사유) 를 돌려준다."""
    if not LOAD_GATE:
        return True, ""
    d = math.hypot(v.x - LOAD_SPOT[0], v.y - LOAD_SPOT[1])
    if d > LOAD_POS_TOL:
        return True, ""                      # 바이 밖 — 시험용, 검사 안 함
    err = math.atan2(math.sin(LOAD_YAW - v.yaw), math.cos(LOAD_YAW - v.yaw))
    if abs(err) <= LOAD_YAW_TOL:
        return True, ""
    return False, (f"{vehicle} 각도가 {math.degrees(err):+.0f}도 틀어져 적재 불가 "
                   f"(허용 ±{math.degrees(LOAD_YAW_TOL):.0f}도). "
                   f"align_bay({vehicle!r}) 로 정렬하세요")


def align_bay(vehicle="SIM_F02", timeout=10.0):
    """바이 정면으로 전후진 정렬한다."""
    v = fleet.vehicles.get(vehicle)          # noqa: F821
    if v is None:
        print(f"차량 '{vehicle}' 없음")
        return False
    if v._steps:
        # 아직 끝나지 않은 임무(랙 적재 등)를 덮어쓰면 화물을 못 내려놓는다.
        print(f"{vehicle} 임무 수행 중({v._steps[0][0]}) — 정렬 요청 무시")
        return False
    v._steps = [("align", LOAD_YAW, timeout)]
    v._step_t = 0.0
    try:
        v.set_work_state("LOADING")     # 관제 화면에 "받는 중" 으로 보이게
    except AttributeError:
        pass
    print(f"{vehicle} 바이 정면으로 정렬 시작")
    return True


# 화물을 얹는 방식. cargo_cmd(데모·MQTT)가 이걸 따른다.
#   "original" 부를 때마다 프림을 만들고 매 프레임 포크를 따라오게 (원래 방식)
#   "classic"  미리 만든 프림 재사용 + 매 프레임 추종
#   "parent"   미리 만든 프림을 포크의 자식으로 (추종 계산 없음)
CARGO_MODE = "original"

# 무제한 데모에서 화물 프림이 끝없이 쌓이지 않게 상한을 둔다.
# 넘으면 가장 오래된 것부터 지운다(랙에 쌓인 화물이 서서히 사라진다).
MAX_CARGO_PRIMS = 12

_cargo_counter = [0]


def clear_cargo():
    """화물을 정리한다.

    프림을 어떻게 처리하든(숨기기/삭제) 차량의 화물 상태를 반드시 함께
    지운다. 이걸 빼먹으면 프림은 사라졌는데 차량은 아직 들고 있다고 여겨
    다음 load() 가 "이미 화물을 들고 있음" 이라며 거부한다.
    """
    for v in fleet.vehicles.values():        # noqa: F821
        v.cargo = None
        v.cargo_id = None

    if _boxes and _boxes_valid():
        # 미리 만든 화물은 지우지 않고 치워서 숨긴다
        # (재생 중 프림 삭제는 스테이지 재파싱을 유발한다)
        for info in _boxes.values():
            UsdGeom.XformCommonAPI(info["carry"]).SetTranslate(CARRY_PARK)
            UsdGeom.XformCommonAPI(info["drop"]).SetTranslate(BOX_PARK)
            UsdGeom.Imageable(info["carry"]).MakeInvisible()
            UsdGeom.Imageable(info["drop"]).MakeInvisible()
        print(f"화물 {len(_boxes)}쌍 치움 (차량 상태도 비움)")
        return

    # load_original() 로 그때그때 만든 화물은 프림째 지운다
    n = 0
    for child in list(_stage.GetPrimAtPath(CARGO_ROOT).GetChildren()) \
            if _stage.GetPrimAtPath(CARGO_ROOT).IsValid() else []:
        _stage.RemovePrim(child.GetPath())
        n += 1
    print(f"화물 프림 {n}개 삭제 (차량 상태도 비움)")


# ---------------------------------------------------------------------------
# 화물 = 네모 상자. 차량마다 두 개를 미리 만들어 둔다.
#
#   1) 운반용  {lift}/cargo_box        포크(lift)의 자식.
#      부모가 움직이면 USD 가 알아서 따라오게 하므로, 매 프레임 좌표를 쓸 일이
#      없다. 화물을 매 프레임 옮기는 것이 Isaac 을 멈추게 하던 원인이었다.
#   2) 하역용  /World/Cargo/{vid}_dropped   월드의 자식.
#      내려놓을 때는 부모를 바꿔야 하는데(재생 중 스테이지 변경 = 멈춤),
#      그 대신 운반용을 치우고 하역용을 그 자리에 세워 같은 그림을 만든다.
#
# 둘 다 정지 상태(Play 전)에 만들어 두고, 실행 중에는 크기·위치만 바꾼다.
# ---------------------------------------------------------------------------
BOX_PARK = (-50.0, -50.0, 0.0)     # 월드에서 치워 두는 곳
CARRY_PARK = (0.0, 0.0, -100.0)    # 포크 아래로 치워 두는 로컬 좌표
_boxes = {}                         # 차량ID -> {"carry":…, "drop":…, "lift":…}


def _boxes_valid():
    """_boxes 가 가리키는 프림이 아직 살아 있는지.

    스크립트를 다시 실행하거나 씬을 다시 열면 이전에 저장해 둔 프림 참조가
    무효가 된다. 그 상태로 쓰면 "Accessed schema on invalid prim" 이 난다.
    """
    if not _boxes:
        return False
    for info in _boxes.values():
        for key in ("carry", "drop", "lift", "carry_box", "drop_box"):
            obj = info.get(key)
            prim = obj.GetPrim() if hasattr(obj, "GetPrim") else obj
            if prim is None or not prim.IsValid():
                return False
    return True


def _ensure_boxes():
    """무효해졌으면 조용히 다시 준비한다."""
    if not _boxes_valid():
        print("화물 프림 참조가 무효 — 다시 준비합니다")
        prepare_cargo_boxes()
    return bool(_boxes)


def _build_cargo_group(path):
    """파레트+박스 한 벌을 만든다 (기존 cargo_demo 와 같은 모양).

    그룹(Xform) 하나에 파레트와 박스를 자식으로 둔다. 나중에 그룹만 옮기면
    둘이 함께 움직이고, 박스의 scale 만 바꾸면 화물 높이가 바뀐다.
    """
    if _stage.GetPrimAtPath(path).IsValid():
        _stage.RemovePrim(path)
    grp = UsdGeom.Xform.Define(_stage, path)

    # 파레트는 씬의 실제 에셋(PALLET_SRC)을 복제해 쓴다.
    top = _make_pallet(path, (0.0, 0.0, 0.0))

    box = UsdGeom.Cube.Define(_stage, f"{path}/box")
    box.CreateSizeAttr(1.0)
    _bw, _bd, _ = box_size(1.0)
    UsdGeom.XformCommonAPI(box).SetScale((_bw, _bd, 1.0))
    UsdGeom.XformCommonAPI(box).SetTranslate((0.0, 0.0, top + 0.5))
    box.CreateDisplayColorAttr([Gf.Vec3f(0.8, 0.5, 0.2)])        # 갈색
    return grp, box


def _set_box_height(box, h):
    """박스 높이를 h 로. 파레트 위에 얹히도록 위치도 같이 맞춘다."""
    _bw, _bd, _bh = box_size(h)
    UsdGeom.XformCommonAPI(box).SetScale((_bw, _bd, _bh))
    UsdGeom.XformCommonAPI(box).SetTranslate(
        (0.0, 0.0, _pallet_top[0] + _bh / 2))


def reset_cargo_scene():
    """씬에 남아 있는 화물을 전부 지운다 — 시작할 때 한 번 부른다.

    이전 실행에서 만든 화물 프림이 스테이지에 남아 있으면, 차량이 아무것도
    안 했는데 포크에 파레트를 든 것처럼 보인다. 특히 포크의 자식으로 만든
    운반용 화물은 포크를 따라다니므로 더 그렇다.
    """
    n = 0
    root = _stage.GetPrimAtPath(CARGO_ROOT)
    if root.IsValid():
        for child in list(root.GetChildren()):
            _stage.RemovePrim(child.GetPath())
            n += 1
    # 포크 자식으로 붙은 것도 지운다
    for prim in list(_stage.Traverse()):
        path = str(prim.GetPath())
        if path.endswith("/lift/cargo") and "Forklift" in path:
            _stage.RemovePrim(prim.GetPath())
            n += 1
    try:
        for v in fleet.vehicles.values():         # noqa: F821
            v.cargo = None
            v.cargo_id = None
    except Exception:
        pass
    print(f"씬의 화물 프림 {n}개 정리 (차량 상태도 비움)")
    return n


def prepare_cargo_boxes():
    """차량마다 운반용(포크 자식)·하역용(월드) 화물을 미리 만든다."""
    global _boxes
    _boxes = {}
    if not _stage.GetPrimAtPath(CARGO_ROOT).IsValid():
        UsdGeom.Xform.Define(_stage, CARGO_ROOT)

    for vid in fleet.vehicles:                       # noqa: F821
        lift_path = f"/World/Forklift_{vid}/forklift_c/lift"
        if not _stage.GetPrimAtPath(lift_path).IsValid():
            print(f"  {vid}: lift 프림 없음 ({lift_path}) — 건너뜀")
            continue

        carry_path = f"{lift_path}/cargo"            # 포크의 자식
        drop_path = f"{CARGO_ROOT}/{vid}_dropped"    # 월드의 자식
        carry_grp, carry_box = _build_cargo_group(carry_path)
        drop_grp, drop_box = _build_cargo_group(drop_path)
        # 안 쓰는 동안은 창고 밖으로 치우고 숨긴다. 둘 다 하는 이유:
        # 숨기기만 하면 스테이지 트리에 남아 헷갈리고, 치우기만 하면 창고 밖에
        # 상자가 떠 있는 것으로 보인다.
        UsdGeom.XformCommonAPI(carry_grp).SetTranslate(CARRY_PARK)
        UsdGeom.XformCommonAPI(drop_grp).SetTranslate(BOX_PARK)
        UsdGeom.Imageable(carry_grp).MakeInvisible()
        UsdGeom.Imageable(drop_grp).MakeInvisible()

        _boxes[vid] = {
            "carry": _stage.GetPrimAtPath(carry_path),
            "carry_box": carry_box,
            "drop": _stage.GetPrimAtPath(drop_path),
            "drop_box": drop_box,
            "lift": _stage.GetPrimAtPath(lift_path),
            "carry_path": carry_path,
        }

    print(f"화물(파레트+박스) 준비됨: {', '.join(_boxes)} "
          f"— 운반용은 포크의 자식이라 따라다니는 계산이 필요 없다")
    return len(_boxes)


def _world_to_lift_local(lift_prim, world_xyz):
    """월드 좌표를 포크(lift) 로컬 좌표로 바꾼다."""
    from pxr import Usd, Gf
    m = UsdGeom.Xformable(lift_prim).ComputeLocalToWorldTransform(
        Usd.TimeCode.Default())
    return m.GetInverse().Transform(Gf.Vec3d(*world_xyz))


def _box_world_pos(prim):
    """프림의 현재 월드 위치."""
    from pxr import Usd
    m = UsdGeom.Xformable(prim).ComputeLocalToWorldTransform(
        Usd.TimeCode.Default())
    return m.ExtractTranslation()


def load(height_real=0.15, vehicle="SIM_F02"):
    """지게차 포크 위치에 팔레트+박스를 생성해 바로 얹는다.

    height_real: 실물 기준 화물 높이(m). 시뮬 박스 높이 = height_real * SCALE.
    """
    v = fleet.vehicles.get(vehicle)  # noqa: F821
    if v is None:
        print(f"차량 '{vehicle}' 없음. kinematic_vehicle.py 를 먼저 실행하세요.")
        return None
    if v.cargo is not None:
        print(f"{vehicle} 이미 화물({v.cargo_id})을 들고 있음. 먼저 내려놓으세요.")
        return None

    ok, why = check_load_pose(v, vehicle)
    if not ok:
        print(why)
        return None

    h = height_real * SCALE
    _cargo_counter[0] += 1
    cid = f"C{_cargo_counter[0]:04d}"

    # 포크 끝 world 위치 (화물이 얹힐 지점)
    fx, fy, fz, fyaw = v.fork_tip_pose()

    _ensure_boxes()
    # 미리 만들어 둔 운반용 상자를 포크 위로 올린다.
    # 이 상자는 포크(lift)의 자식이라 부모가 움직이면 USD 가 알아서 따라온다.
    # 매 프레임 좌표를 쓰지 않으므로 Isaac 이 멈추지 않는다.
    # 매 프레임 방식(load_classic)이면 추종만 끊으면 그 자리에 남는다.
    if v.cargo is not None:
        v.cargo = None
        v.cargo_id = None
        print(f"{vehicle} 화물 {cid} 하역(매프레임 방식)")
        return True

    if vehicle in _boxes:
        info = _boxes[vehicle]
        # 그룹(파레트 바닥)이 포크 끝에 오도록 로컬 좌표를 구한다.
        local = _world_to_lift_local(info["lift"], (fx, fy, fz))
        UsdGeom.XformCommonAPI(info["carry"]).SetTranslate(tuple(local))
        _set_box_height(info["carry_box"], h)   # 박스 높이만 측정값으로
        UsdGeom.Imageable(info["carry"]).MakeVisible()
        # 하역용은 아직 안 내려놨으니 치우고 숨긴다
        UsdGeom.XformCommonAPI(info["drop"]).SetTranslate(BOX_PARK)
        UsdGeom.Imageable(info["drop"]).MakeInvisible()
        # 차량은 화물 '식별자' 만 기억한다. 따라다니는 계산은 USD 부모-자식이
        # 대신하므로 v.cargo(매 프레임 갱신 대상)에는 넣지 않는다.
        v.cargo = None
        v.cargo_id = cid
        print(f"{vehicle} 포크에 상자 {cid} 얹음: "
              f"높이 {h:.2f}m (실물 {height_real}m)")
        return info["carry_path"]

    # 미리 만든 상자가 없으면 즉석 생성 (Play 중이면 몇십 초 멈출 수 있다)
    root = f"{CARGO_ROOT}/{cid}"
    if not _stage.GetPrimAtPath(CARGO_ROOT).IsValid():
        UsdGeom.Xform.Define(_stage, CARGO_ROOT)
    if _stage.GetPrimAtPath(root).IsValid():
        _stage.RemovePrim(root)

    # 화물 루트(root)를 포크 위치로 옮긴다. 자식(pallet/box)은 이 루트 기준
    # LOCAL 좌표(원점 근처)로만 배치한다. _update_cargo 는 root 하나만 옮기고
    # 자식이 따라오므로, 자식에 world 좌표를 넣으면 root 이동과 이중으로 더해진다.
    grp = UsdGeom.Xform.Define(_stage, root)
    UsdGeom.XformCommonAPI(grp).SetTranslate((fx, fy, fz))

    # 팔레트 — root 기준 로컬. 바닥이 root 원점에 오도록 절반만 위로.
    top = _make_pallet(root, (fx, fy, fz))

    # 박스 — 팔레트 위 (로컬)
    box = UsdGeom.Cube.Define(_stage, f"{root}/box")
    box.CreateSizeAttr(1.0)
    bw, bd, h = box_size(h)
    UsdGeom.XformCommonAPI(box).SetScale((bw, bd, h))
    UsdGeom.XformCommonAPI(box).SetTranslate((0.0, 0.0, top + h / 2))
    box.CreateDisplayColorAttr([Gf.Vec3f(0.8, 0.5, 0.2)])   # 갈색

    # 포크에 등록 -> 이후 _update_cargo 가 root 를 포크를 따라 옮긴다.
    v.pick(root, cargo_id=cid, force=True)
    # 관제가 "무엇을 싣고 있는지" 를 알 수 있도록 크기를 telemetry 에 싣는다.
    try:
        _bw, _bd, _bh = box_size(h)
        v.set_cargo_size(_bw, _bd, _pallet_top[0] + _bh)
    except Exception as _e:
        print(f"[cargo] 크기 기록 실패(무시): {_e}")
    print(f"{vehicle} 포크에 화물 {cid} 얹음: 높이 {h:.2f}m (실물 {height_real}m)")
    return root


def load_classic(height_real=0.15, vehicle="SIM_F02"):
    """기존 cargo_demo 방식 — 월드에 둔 화물을 매 프레임 포크로 따라오게 한다.

    load() 와 보이는 결과는 같다. 차이는 따라오는 방법이다.
        load()         포크(lift)의 자식으로 두어 USD 가 자동으로 따라오게 함
        load_classic() 매 프레임 kinematic_vehicle 이 좌표를 계산해 옮김 (원래 방식)

    프림을 새로 만들지는 않으므로(미리 만들어 둔 것을 재사용) 원래보다는 가볍다.
    어느 쪽이 안정적인지 직접 비교해 보고 쓰면 된다.
    """
    v = fleet.vehicles.get(vehicle)  # noqa: F821
    if v is None:
        print(f"차량 '{vehicle}' 없음")
        return None
    if v.cargo_id is not None:
        print(f"{vehicle} 이미 화물({v.cargo_id})을 들고 있음")
        return None
    _ensure_boxes()
    if vehicle not in _boxes:
        print("prepare_cargo_boxes() 를 먼저 실행하세요")
        return None

    ok, why = check_load_pose(v, vehicle)
    if not ok:
        print(why)
        return None

    h = height_real * SCALE
    _cargo_counter[0] += 1
    cid = f"C{_cargo_counter[0]:04d}"
    info = _boxes[vehicle]

    _set_box_height(info["drop_box"], h)
    fx, fy, fz, fyaw = v.fork_tip_pose()
    UsdGeom.XformCommonAPI(info["drop"]).SetTranslate((fx, fy, fz))
    UsdGeom.Imageable(info["drop"]).MakeVisible()
    # 포크 자식(운반용)은 이 방식에서 쓰지 않는다 — 치우고 숨긴다
    UsdGeom.XformCommonAPI(info["carry"]).SetTranslate(CARRY_PARK)
    UsdGeom.Imageable(info["carry"]).MakeInvisible()

    v.cargo = info["drop"]        # 매 프레임 _update_cargo 가 이걸 옮긴다
    v.cargo_id = cid
    print(f"{vehicle} 포크에 화물 {cid} 얹음(매프레임 방식): "
          f"높이 {h:.2f}m (실물 {height_real}m)")
    return f"{CARGO_ROOT}/{vehicle}_dropped"


def load_original(height_real=0.15, vehicle="SIM_F02"):
    """원래 cargo_demo 방식 그대로.

    미리 만들어 둔 프림을 쓰지 않고, 부를 때마다 파레트+박스를 새로 만들어
    포크 위치에 놓고 v.pick() 으로 등록한다. 이후 kinematic_vehicle 이 매
    프레임 화물을 포크로 옮긴다.

        load_original(0.15)     # 실물 15cm -> 시뮬 1.5m
        drop_here()             # 그 자리에 내려놓기
        clear_cargo()           # 정리
    """
    v = fleet.vehicles.get(vehicle)  # noqa: F821
    if v is None:
        print(f"차량 '{vehicle}' 없음. kinematic_vehicle.py 를 먼저 실행하세요.")
        return None
    if v.cargo is not None or v.cargo_id is not None:
        print(f"{vehicle} 이미 화물({v.cargo_id})을 들고 있음. 먼저 내려놓으세요.")
        return None

    ok, why = check_load_pose(v, vehicle)
    if not ok:
        print(why)
        return None

    h = height_real * SCALE
    _cargo_counter[0] += 1
    cid = f"C{_cargo_counter[0]:04d}"
    root = f"{CARGO_ROOT}/{cid}"

    if not _stage.GetPrimAtPath(CARGO_ROOT).IsValid():
        UsdGeom.Xform.Define(_stage, CARGO_ROOT)
    if _stage.GetPrimAtPath(root).IsValid():
        _stage.RemovePrim(root)

    # 상한을 넘으면 오래된 화물부터 지운다 (무제한 데모 대비)
    kids = [c for c in _stage.GetPrimAtPath(CARGO_ROOT).GetChildren()
            if c.GetName().startswith("C")]
    if len(kids) >= MAX_CARGO_PRIMS:
        for old_prim in sorted(kids, key=lambda c: c.GetName())[
                :len(kids) - MAX_CARGO_PRIMS + 1]:
            _stage.RemovePrim(old_prim.GetPath())

    # 포크 끝 world 위치 (화물이 얹힐 지점)
    fx, fy, fz, fyaw = v.fork_tip_pose()

    # 그룹은 포크 위치로, 자식(파레트/박스)은 그룹 기준 LOCAL 좌표로만 배치한다.
    # 자식에 world 좌표를 넣으면 그룹 이동과 이중으로 더해진다.
    grp = UsdGeom.Xform.Define(_stage, root)
    UsdGeom.XformCommonAPI(grp).SetTranslate((fx, fy, fz))

    top = _make_pallet(root, (fx, fy, fz))

    box = UsdGeom.Cube.Define(_stage, f"{root}/box")
    box.CreateSizeAttr(1.0)
    bw, bd, h = box_size(h)
    UsdGeom.XformCommonAPI(box).SetScale((bw, bd, h))
    UsdGeom.XformCommonAPI(box).SetTranslate((0.0, 0.0, top + h / 2))
    box.CreateDisplayColorAttr([Gf.Vec3f(0.8, 0.5, 0.2)])        # 갈색

    # 포크에 등록 -> 이후 _update_cargo 가 매 프레임 그룹을 포크로 옮긴다.
    v.pick(root, cargo_id=cid, force=True)
    # 관제가 "무엇을 싣고 있는지" 를 알 수 있도록 크기를 telemetry 에 싣는다.
    try:
        _bw, _bd, _bh = box_size(h)
        v.set_cargo_size(_bw, _bd, _pallet_top[0] + _bh)
    except Exception as _e:
        print(f"[cargo] 크기 기록 실패(무시): {_e}")
    print(f"{vehicle} 포크에 화물 {cid} 얹음(원래 방식): "
          f"높이 {h:.2f}m (실물 {height_real}m)")
    return root


def set_dock_depth(depth):
    """랙에 넣는 깊이를 실행 중에 바꾼다 (Isaac 재시작 없이).

        set_dock_depth(0.5)    # 덜 들어감
        set_dock_depth(1.0)    # 더 들어감

    깊이 = 랙 진입면에서 포크 끝까지. 모든 슬롯에 한 번에 적용된다.
    """
    global _DOCK_DEPTH
    _DOCK_DEPTH = float(depth)
    for _g, _c in _RACKS.items():
        _c["dock_x"] = _c["face_x"] + _HALF_LEN - _DOCK_DEPTH
        for _i in range(1, len(_SLOT_Y) + 1):
            k = f"{_g}{_i}"
            _y = RACK_SLOTS[k]["dock"][1]
            RACK_SLOTS[k]["dock"] = (_c["dock_x"], _y, _YAW_WEST)
    print(f"도킹 깊이 {_DOCK_DEPTH} m  →  "
          f"A dock {_RACKS['A']['dock_x']:.2f}, B dock {_RACKS['B']['dock_x']:.2f}")


def _check_vehicle_version(need=8):
    """차량 코드가 시간 제한 스텝을 지원하는지 확인한다.

    옛 kinematic_vehicle 은 ("drive", x, y) 3칸만 풀기 때문에 4칸을 주면
    매 프레임 예외가 나고, 차량은 마지막 명령을 유지한 채 원을 그린다.
    """
    try:
        ver = VEHICLE_VERSION                      # noqa: F821
    except NameError:
        ver = 0
    if ver < need:
        print(f"[cargo] 경고: kinematic_vehicle 이 옛 버전(v{ver})입니다. "
              f"v{need} 이상이 필요합니다.")
        print("  Script Editor 에서 먼저 실행하세요:")
        print("  exec(open('.../kinematic_vehicle.py').read())")
        return False
    return True


def place_rack_direct(rack="A1", vehicle=None, height_real=None,
                      cargo_id=None):
    """랙 슬롯에 화물을 그대로 놓는다 — 주행 절차 없이.

    실물 지게차(REAL_F01)가 세트장에서 실제로 적재를 끝냈을 때 쓴다.
    실물은 이미 자기 힘으로 도킹·적재를 마쳤으므로, 시뮬은 그 결과만
    반영하면 된다. 스텝 머신을 돌리면 미러 위치와 싸우게 되므로 쓰지 않는다.

        place_rack_direct("A1")                     # 빈 슬롯에 새로 놓기
        place_rack_direct("A1", vehicle="REAL_F01") # 그 차가 들고 있던 걸 놓기
    """
    if rack not in RACK_SLOTS:
        print(f"랙 '{rack}' 없음. 사용 가능: {list(RACK_SLOTS)}")
        return False
    px, py, pz = RACK_SLOTS[rack]["place"]

    v = fleet.vehicles.get(vehicle) if vehicle else None   # noqa: F821

    # 차가 들고 있던 화물이면 그걸 옮기고 추종만 끊는다.
    if v is not None and (v.cargo is not None or v.cargo_id is not None):
        cid = v.cargo_id
        v.place(px, py, pz, yaw=_YAW_WEST)
        try:
            v.set_work_state("")
        except AttributeError:
            pass
        print(f"{vehicle} 의 화물 {cid} 를 {rack} ({px}, {py}, {pz}) 에 놓음")
        return True

    # 들고 있는 게 없으면 새로 만들어 슬롯에 얹는다 (실물이 이미 놓은 경우).
    h = (height_real if height_real is not None else 0.15) * SCALE
    _cargo_counter[0] += 1
    cid = cargo_id or f"R{_cargo_counter[0]:04d}"
    root = f"{CARGO_ROOT}/{cid}"
    if not _stage.GetPrimAtPath(CARGO_ROOT).IsValid():
        UsdGeom.Xform.Define(_stage, CARGO_ROOT)
    if _stage.GetPrimAtPath(root).IsValid():
        _stage.RemovePrim(root)

    grp = UsdGeom.Xform.Define(_stage, root)
    UsdGeom.XformCommonAPI(grp).SetTranslate((px, py, pz))
    UsdGeom.XformCommonAPI(grp).SetRotate((0.0, 0.0,
                                           math.degrees(_YAW_WEST)))
    top = _make_pallet(root, (px, py, pz))
    box = UsdGeom.Cube.Define(_stage, f"{root}/box")
    box.CreateSizeAttr(1.0)
    bw, bd, bh = box_size(h)
    UsdGeom.XformCommonAPI(box).SetScale((bw, bd, bh))
    UsdGeom.XformCommonAPI(box).SetTranslate((0.0, 0.0, top + bh / 2))
    box.CreateDisplayColorAttr([Gf.Vec3f(0.8, 0.5, 0.2)])
    print(f"{rack} ({px}, {py}, {pz}) 에 화물 {cid} 배치 (실물 적재 반영)")
    return True


def place_on_rack(rack="A1", vehicle="SIM_F02"):
    """랙 적재 — Nav2 가 approach 까지 데려온 뒤 이 함수를 부른다.

    여기서부터는 Nav2 를 쓰지 않는다. 포크를 랙 안에 넣어야 하는데 그 자리는
    footprint 가 랙과 겹쳐 Nav2 가 갈 수 없기 때문이다. 차량의 스텝 머신으로
    직접 몰아 넣고, 놓고, 후진해서 나온다.

        1. 선반 높이로 포크 상승
        2. dock 지점까지 직진 (포크가 랙 안으로)
        3. 화물을 선반 좌표에 내려놓기
        4. 포크 하강
        5. 후진해서 빠져나오기
    """
    if rack not in RACK_SLOTS:
        print(f"랙 '{rack}' 없음. 사용 가능: {list(RACK_SLOTS)}")
        return False
    v = fleet.vehicles.get(vehicle)  # noqa: F821
    if v is None:
        print(f"차량 '{vehicle}' 없음")
        return False
    if v.cargo is None and v.cargo_id is None:
        print("들고 있는 화물이 없음. 먼저 적재하세요.")
        return False
    if not _check_vehicle_version(8):
        return False

    slot = RACK_SLOTS[rack]
    dx, dy, dyaw = slot["dock"]
    px, py, pz = slot["place"]

    # 랙 앞에서는 조향으로 목표를 쫓지 않는다.
    #   align 으로 각을 잡고 -> dock 으로 그 각도를 유지한 채 곧게 들어간다.
    # 예전에는 drive(목표를 향해 조향) -> align -> drive 를 반복했는데,
    # 랙 코앞에서 목표가 조금만 옆으로 어긋나도 조향이 크게 걸려 차가 과도하게
    # 틀어졌고, 그 상태로 화물을 놓아 비뚤어졌다.
    v._steps = [
        ("align", dyaw, 12.0),              # 랙 정면으로 전후진 정렬
        ("fork", pz, 12.0),                 # 선반 높이로 포크 상승
        ("dock", dx, dy, dyaw, 15.0),       # 각도 고정한 채 곧게 진입
        ("settle", 1.5),                    # 완전히 멈출 때까지
        ("place", px, py, pz, dyaw),        # 선반 좌표에 내려놓기
        ("fork", v.p["lift_min"], 12.0),    # 포크 하강
        ("reverse", 2.0),                   # 후진해서 빠져나오기
    ]
    v._step_t = 0.0
    try:
        v.set_work_state("UNLOADING")   # 스텝이 다 끝나면 자동으로 해제된다
    except AttributeError:
        pass
    print(f"{vehicle} 랙 {rack} 적재 시작: dock({dx},{dy}) place({px},{py},{pz})")
    return True


def deliver(rack="A1", vehicle="SIM_F02"):
    """포크에 얹힌 화물을 지정 랙으로 운반해 내려놓는다."""
    if rack not in RACK_SLOTS:
        print(f"랙 '{rack}' 없음. 사용 가능: {list(RACK_SLOTS)}")
        return
    v = fleet.vehicles.get(vehicle)  # noqa: F821
    if v is None or v.cargo is None:
        print("들고 있는 화물이 없음. load() 를 먼저 실행하세요.")
        return

    slot = RACK_SLOTS[rack]
    ax, ay, ayaw = slot["approach"]   # 지게차가 설 실측 위치
    px, py, pz = slot["place"]        # 화물 놓을 선반 좌표
    # place 각도는 랙 접근 방향(ayaw)에 맞춘다 — 지게차가 틀어져도 화물은 반듯.
    v._steps = [
        ("fork", 0.12 * SCALE, 12.0),  # travel height(실물 10~15cm)로 살짝 들기
        ("drive", ax, ay, 25.0),     # 랙 앞 실측 접근 위치로 이동
        ("face", ayaw),              # 랙 정면으로 방향 정렬 (데모용, 부드럽게)
        ("fork", pz, 12.0),          # 슬롯 높이로 올림
        ("settle", 2.0),             # 완전히 멈춘 뒤에 놓는다
        ("place", px, py, pz, ayaw), # 선반에 내려놓기 (랙 방향 정렬)
        ("fork", v.p["lift_min"]),   # 포크 하강
        ("reverse", 1.0),            # 후진
    ]
    v._step_t = 0.0
    print(f"{vehicle} -> 랙 {rack}: 접근({ax},{ay}) 적재({px},{py},{pz})")


def demo(height_real=0.15, rack="A1", vehicle="SIM_F02"):
    """포크에 화물 얹고 -> 랙 운반 -> 적재 를 한 번에."""
    if load(height_real, vehicle) is not None:
        deliver(rack, vehicle)


# ---------------------------------------------------------------------------
# MQTT 연동 — 측정된 화물 높이를 받아 자동으로 적재
#
# 흐름: AI(비전) 측정 -> 백엔드 -> MQTT -> mqtt_bridge.py -> /{ns}/cargo_cmd
#       -> (여기) load() -> 지게차 포크에 파레트+박스 생성
#
# 화물 프림 생성은 USD 스테이지에 접근해야 하므로 Isaac 안에서만 가능하다.
# 그래서 브릿지가 직접 만들지 못하고 이 ROS 토픽을 거친다.
# ---------------------------------------------------------------------------
import json
import threading

import omni.kit.app
from std_msgs.msg import String

# ---------------------------------------------------------------------------
# ROS 수신 -> 메인 스레드로 넘기기
#
# USD 스테이지 수정(프림 생성/이동)은 Isaac 메인 스레드에서만 해야 한다.
# rclpy 콜백은 별도 스레드(_spin)에서 실행되므로, 거기서 곧바로 화물을 만들면
# 스테이지 락에서 데드락이 나고 Isaac 창 전체가 멈춘다.
#
#   Thread-4 (_spin): load_original -> _on_cargo_cmd -> rclpy executor
#   MainThread: futex_wait_queue (락 대기)
#
# 그래서 수신 스레드는 요청을 큐에 넣기만 하고, 앱 업데이트 이벤트(메인 스레드)
# 에서 꺼내 실행한다.
# ---------------------------------------------------------------------------
_cargo_subs = []
_pending = []
_pending_lock = threading.Lock()
_drain_sub = [None]


def _on_cargo_cmd(msg, vehicle):
    """ROS 수신 스레드. USD 를 절대 건드리지 않고 큐에만 넣는다."""
    try:
        p = json.loads(msg.data)
    except Exception:
        print(f"[cargo_cmd] JSON 파싱 실패: {msg.data}")
        return
    with _pending_lock:
        _pending.append((vehicle, p))


def _drain_cargo_cmds(event):
    """앱 업데이트(메인 스레드)에서 큐를 비우며 실제 작업을 수행한다."""
    if not _pending:
        return
    now = time.time()
    with _pending_lock:
        items, _pending[:] = list(_pending), []
    later = []
    for vehicle, p in items:
        try:
            if not p.get("_logged"):
                p["_logged"] = True
                print(f"[cargo_cmd] {vehicle} <- {p}")
            # delay 가 붙은 명령은 그 시각까지 큐에 남겨 둔다.
            # (정렬이 끝난 뒤에 화물을 받게 하려고 쓴다)
            due = p.get("_due")
            if due is None and p.get("delay"):
                p["_due"] = now + float(p["delay"])
                later.append((vehicle, p))
                continue
            if due is not None and now < due:
                later.append((vehicle, p))
                continue
            act = str(p.get("action", "")).lower()
            if act in ("drop", "unload"):
                drop_here(vehicle)
                continue
            if act in ("place_rack", "rack"):
                # 미러(실물 추종) 차량은 주행 절차를 돌리지 않는다.
                # 실물이 이미 실제로 적재를 끝냈으므로 결과만 반영한다.
                _v = fleet.vehicles.get(vehicle)          # noqa: F821
                if _v is not None and getattr(_v, "mirror", False):
                    place_rack_direct(p.get("rack", "A1"), vehicle,
                                      p.get("height"), p.get("cargoId"))
                else:
                    place_on_rack(p.get("rack", "A1"), vehicle)
                continue
            if act in ("spawn_rack", "rack_set"):
                # 차량과 무관하게 슬롯에 화물을 놓는다 (재고 초기화 등)
                place_rack_direct(p.get("rack", "A1"), None,
                                  p.get("height"), p.get("cargoId"))
                continue
            if act in ("align_bay", "align"):
                align_bay(vehicle, float(p.get("timeout", 10.0)))
                continue
            h = p.get("height") or p.get("heightM") or p.get("height_real")
            if h is None:
                print(f"[cargo_cmd] height 없음: {p}")
                continue

            # 각도가 어긋나 있으면 버리지 말고 정렬시킨 뒤 다시 시도한다.
            # 그냥 거부하면 차량이 빈 포크로 랙까지 갔다가 아무것도 못 놓고
            # 계속 순환로를 돈다.
            veh = fleet.vehicles.get(vehicle)                 # noqa: F821

            # 바이에 왔는데 직전 임무(랙 적재)가 남아 있으면 정렬이 거부되고
            # 재시도만 태우다 끝난다. 바이에서는 아무 임무도 돌면 안 되므로
            # 남은 스텝을 지우고 새로 시작한다.
            if veh is not None and veh._steps:
                print(f"[cargo_cmd] {vehicle} 남은 임무 "
                      f"{[x[0] for x in veh._steps]} 정리")
                veh._steps = []
                veh._step_t = 0.0
                veh.v = 0.0

            # 바이에 왔는데 아직 화물을 들고 있으면(직전 랙 적재가 끝나지
            # 못한 경우) 새 화물을 영영 못 받는다. 들고 있던 걸 정리하고
            # 진행한다 — 여기서 막히면 차량이 계속 순환로만 돈다.
            if veh is not None and veh.cargo_id is not None:
                # 들고 있던 것만 떼어낸다. clear_cargo() 는 랙에 이미
                # 올려둔 화물까지 전부 지우므로 여기서는 쓰지 않는다.
                print(f"[cargo_cmd] {vehicle} 아직 {veh.cargo_id} 를 들고 있음"
                      f" — 떼어내고 새로 받는다 (직전 랙 적재 미완료)")
                veh.cargo = None
                veh.cargo_id = None

            if veh is not None:
                ok, why = check_load_pose(veh, vehicle)
                if not ok:
                    tries = int(p.get("_tries", 0))
                    if tries < 1:
                        print(f"{why} -> 정렬 후 재시도")
                        align_bay(vehicle, 8.0)
                        p["_tries"] = tries + 1
                        p["_due"] = now + 9.0
                        later.append((vehicle, p))
                        continue
                    print(f"{why} -> 정렬로 못 맞춤, 각도를 맞추고 적재한다")
                    veh.yaw = LOAD_YAW
                    veh.v = 0.0
                    veh._steps = []

            try:
                veh.set_work_state("LOADING")
            except AttributeError:
                pass
            print(f"[cargo_cmd] {vehicle} 화물 높이 {h} m 수신 -> 적재")
            res = CARGO_LOADERS.get(CARGO_MODE, load_original)(float(h), vehicle)
            got = veh is not None and veh.cargo_id is not None
            if veh is not None:
                try:
                    veh.set_work_state("")       # 적재 절차 종료
                except AttributeError:
                    pass
            print(f"[cargo_cmd] {vehicle} 적재 결과: "
                  f"{'성공 ' + str(veh.cargo_id) if got else '실패'} "
                  f"(반환 {res})")
            if not got:
                why_no_cargo(vehicle)
        except Exception as e:
            print(f"[cargo_cmd] 처리 실패: {e}")
    if later:
        with _pending_lock:
            _pending.extend(later)


def why_no_cargo(vehicle="SIM_F02"):
    """왜 화물을 못 받는지 한 번에 보여준다."""
    v = fleet.vehicles.get(vehicle)          # noqa: F821
    if v is None:
        print(f"차량 '{vehicle}' 없음 — fleet: {list(fleet.vehicles)}")  # noqa: F821
        return
    d = math.hypot(v.x - LOAD_SPOT[0], v.y - LOAD_SPOT[1])
    err = math.atan2(math.sin(LOAD_YAW - v.yaw), math.cos(LOAD_YAW - v.yaw))
    ok, why = check_load_pose(v, vehicle)
    print(f"── {vehicle} 적재 진단 ──")
    print(f"  위치      : ({v.x:.2f}, {v.y:.2f})  바이까지 {d:.2f} m "
          f"(검사 범위 {LOAD_POS_TOL})")
    print(f"  각도      : {math.degrees(v.yaw):+.1f}도, 오차 "
          f"{math.degrees(err):+.1f}도 (허용 ±{math.degrees(LOAD_YAW_TOL):.0f})")
    print(f"  각도 검사 : {'통과' if ok else '거부 — ' + why}")
    print(f"  들고 있음 : {v.cargo_id}  (있으면 새 화물을 못 받는다)")
    print(f"  임무      : {[x[0] for x in v._steps] or '없음'}")
    print(f"  적재 방식 : {CARGO_MODE}   게이트 {LOAD_GATE}")
    print(f"  파레트    : {_pallet_path[0] or '(아직 안 찾음)'} "
          f"방식 {_pallet_how[0]}")
    print(f"  대기 큐   : {len(_pending)}건")
    if v.cargo_id is not None:
        print("  -> clear_cargo() 로 비우고 다시 시도하세요")
    elif not ok:
        print(f"  -> align_bay('{vehicle}') 또는 LOAD_GATE=False")


def enable_mqtt_cargo():
    """/{ns}/cargo_cmd 를 구독해 받은 높이로 화물을 만든다.

    이 파일을 다시 exec 하면 _pending / _cargo_subs 가 새로 만들어진다.
    그때 옛 구독이 살아 있으면 명령이 옛 큐로 들어가 영영 처리되지 않는다
    (차량이 화물을 못 받고 계속 순환로만 돈다). 그래서 먼저 정리한다.
    """
    for _s in _cargo_subs:
        try:
            fleet.destroy_subscription(_s)          # noqa: F821
        except Exception:
            pass
    _cargo_subs.clear()
    # 미러 차량(REAL_F01)도 포함한다. 실물이 적재를 끝내면 그 결과를
    # 시뮬에 반영해야 하기 때문이다 (place_rack_direct 로 처리된다).
    for vid, v in fleet.vehicles.items():   # noqa: F821
        topic = f"/{v.ns}/cargo_cmd"
        sub = fleet.create_subscription(     # noqa: F821
            String, topic,
            lambda msg, veh=vid: _on_cargo_cmd(msg, veh), 10)
        _cargo_subs.append(sub)
        print(f"  구독: {topic}  -> {vid}")

    # 메인 스레드에서 큐를 비우는 구독 (USD 작업은 여기서만)
    if _drain_sub[0] is None:
        _drain_sub[0] = (omni.kit.app.get_app()
                         .get_update_event_stream()
                         .create_subscription_to_pop(
                             _drain_cargo_cmds, name="cargo_cmd_drain"))
    print("MQTT 화물 적재 대기 중 (작업은 메인 스레드에서 수행)")


CARGO_LOADERS = {
    "original": load_original,
    "classic": load_classic,
    "parent": load,
}


print("cargo_demo 로드됨.  load(height_real=0.15) / deliver(rack='A1') / demo(...)")
print("MQTT 연동:  enable_mqtt_cargo()")
