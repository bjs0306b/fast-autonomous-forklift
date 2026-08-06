"""화물만 따로 테스트 (Isaac Script Editor 용).

Nav2 도, F03 도, /clock 도 없이 화물 동작만 확인한다. 부하가 최소라
"화물 때문에 멈추는가" 를 깨끗하게 가를 수 있다.

    exec(open('/home/ubuntu/forklift_ws/nav2/scripts/test_cargo.py').read())

준비되면 아래를 하나씩 실행해 본다:

    load(0.15)              # 포크에 화물 생성 (실물 15cm -> 시뮬 1.5m)
    go(10.0, 4.0)           # 차를 옮겨 화물이 따라오는지 확인
    fork(1.0)               # 포크를 올려 화물이 같이 올라가는지 확인
    drop_here()             # 지금 자리에 내려놓기
    clear_cargo()           # 전부 치우기

    load_classic(0.15)      # 기존 방식(매 프레임 추종)과 비교하고 싶을 때

멈추면 다른 터미널에서:
    ./nav2/scripts/why_frozen.sh
"""
DIR = "/home/ubuntu/forklift_ws/nav2/scripts"
VEHICLE = "SIM_F02"

print("=" * 60)
print("화물 단독 테스트 준비")
print("=" * 60)

# 1) 물리 끄기 — 조인트 오류/부하 제거 (F02 만)
try:
    import omni.usd
    from pxr import UsdPhysics, Sdf

    _st = omni.usd.get_context().get_stage()
    _nb = _nj = 0
    for _p in _st.Traverse():
        if f"Forklift_{VEHICLE}" not in str(_p.GetPath()):
            continue
        if "Joint" in str(_p.GetTypeName()):
            _a = _p.GetAttribute("physics:jointEnabled")
            if not _a or not _a.IsValid():
                _a = _p.CreateAttribute("physics:jointEnabled",
                                        Sdf.ValueTypeNames.Bool)
            _a.Set(False)
            _nj += 1
        if _p.HasAPI(UsdPhysics.RigidBodyAPI):
            UsdPhysics.RigidBodyAPI(_p).CreateRigidBodyEnabledAttr(False)
            _nb += 1
    print(f"[1/3] 물리 끔 (바디 {_nb}, 조인트 {_nj})")
except Exception as _e:
    print(f"[1/3] 물리 끄기 실패: {_e}")

# 2) 차량 노드 (F02 만)
try:
    exec(open(f"{DIR}/kinematic_vehicle.py").read())
    print(f"[2/3] 차량 노드: {list(fleet.vehicles)}")
except Exception as _e:
    print(f"[2/3] 차량 노드 실패: {_e}")

# 3) 화물 모듈
try:
    exec(open(f"{DIR}/cargo_demo.py").read())
    prepare_cargo_boxes()
    print("[3/3] 화물 준비 완료")
except Exception as _e:
    print(f"[3/3] 화물 준비 실패: {_e}")


def go(x, y, yaw=0.0):
    """차를 순간이동시켜 화물이 따라오는지 본다."""
    v = fleet.vehicles[VEHICLE]          # noqa: F821
    v.x, v.y, v.yaw = float(x), float(y), float(yaw)
    v.v = 0.0
    v.steer = 0.0
    print(f"{VEHICLE} → ({x}, {y})")


def fork(height):
    """포크 높이를 바꿔 화물이 같이 올라가는지 본다."""
    v = fleet.vehicles[VEHICLE]          # noqa: F821
    v._lift_target = float(height)
    print(f"포크 목표 {height} m")


print("=" * 60)
print("이제 ▶ Play 를 누르고, 아래를 하나씩 실행하세요:")
print("  load(0.15)      화물 생성")
print("  go(10.0, 4.0)   차 이동 — 화물이 따라오나")
print("  fork(1.0)       포크 상승 — 화물이 같이 오르나")
print("  drop_here()     내려놓기")
print("  clear_cargo()   정리")
print("=" * 60)
