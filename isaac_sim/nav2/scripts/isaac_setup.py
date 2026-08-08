"""Isaac 쪽 준비를 한 번에 — Script Editor 에 이 한 줄만 붙이면 된다.

    exec(open('/home/ubuntu/forklift_ws/nav2/scripts/isaac_setup.py').read())

수행 순서 (순서가 중요하다):
    1. 물리      리지드바디·조인트 비활성   PhysX 조인트 오류·부하 제거
    2. clock     /clock 발행                이게 없으면 Nav2 전체가 얼어붙는다
    3. 차량      kinematic_vehicle (F02)    cmd_vel 구독, odom/TF/state 발행
    4. 차량      SIM_F03                    씬에 있으면 재사용, 없으면 복제
    5. 화물      cargo_demo                 상자 미리 생성 + 높이 수신 대기
    6. 카메라    camera_switch              시점 전환 수신 대기
    6.5 미러     실물 좌표 반영           REAL_F01 프림이 있을 때만
    7. 배치      초기 위치                  F02/F03 을 순환로 아래 홀에 세움

각 단계는 실패해도 다음으로 넘어가고, 마지막에 요약을 보여준다.

실행 순서 — Play 는 이 스크립트 뒤에 누른다
    ff.usd 열기  →  이 스크립트 실행  →  ▶ Play  →  터미널 run_all.sh

    재생 중에 프림을 만들면(화물 상자, F03 복제) Isaac 이 스테이지를 다시
    파싱하느라 수십 초 멈춘다. 정지 상태에서 만들어 두면 그 멈춤이 없다.

주의
    Isaac 을 Stop → Play 하면 시뮬 시간이 0 으로 되감긴다. 그러면 이미 떠 있는
    Nav2 는 얼어붙으므로 Nav2 를 다시 띄워야 한다.
"""
DIR = "/home/ubuntu/forklift_ws/nav2/scripts"

# 초기 배치 (x, y, yaw). 순환로 아래 홀 위에 두 대를 세운다.
# 물리를 껐으므로 위치는 전적으로 스크립트가 쓴다(kinematic_vehicle 이 매 프레임
# set_world_pose 로 프림을 옮긴다). 물리가 되돌리려는 충돌이 없어 안정적이다.
# 세 대가 왼쪽 통로에 어깨를 나란히 하고 +x(동쪽)를 본다.
# 실물 오린카는 REAL_SPAWN 자리에서 시작한다 — 세트장 바닥에
# (40cm, 20cm) 를 표시해 두고 매번 거기서 출발시킨다.
F02_SPAWN  = (4.0, 4.0, 0.0)     # 실물 0.400, 0.400 m
F03_SPAWN  = (4.0, 6.0, 0.0)     # 실물 0.400, 0.600 m
REAL_SPAWN = (4.0, 2.0, 0.0)     # 실물 0.400, 0.200 m  ← 오린카

_result = []


def _ok(step, detail=""):
    _result.append((step, True, detail))
    print(f"  [OK] {step} {detail}")


def _fail(step, err):
    _result.append((step, False, str(err)))
    print(f"  [실패] {step}: {err}")


print("=" * 60)
print("Isaac 준비 시작")
print("=" * 60)

# ── 1. 물리 끄기 ────────────────────────────────────────────────────
# 이 지게차는 물리로 굴리지 않는다. kinematic_vehicle.py 가 자전거 모델을 적분해
# 프림 트랜스폼을 직접 쓴다. 그런데 리지드바디를 kinematic 으로만 바꿔 두면
# PhysX 가 "정지 물체 사이에는 조인트를 만들 수 없다" 며 바퀴·포크 조인트 14개를
# 매 Play 마다 실패시키고, 계속 재시도하며 부하와 불안정을 만든다.
#
#   PhysicsUSD: CreateJoint - cannot create a joint between static bodies
#
# 그래서 리지드바디와 조인트를 아예 비활성화한다. RTX 라이다는 물리 콜라이더가
# 아니라 렌더 메시를 스캔하므로, 물리를 꺼도 서로를 정상적으로 감지한다.
print("\n[1/7] 물리 끄기 (조인트 오류 제거)")
try:
    import omni.usd
    from pxr import UsdPhysics, Sdf

    _stage_setup = omni.usd.get_context().get_stage()
    _nb = _nj = 0
    for _p in _stage_setup.Traverse():
        if "Forklift_SIM" not in str(_p.GetPath()):
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
    _ok("물리", f"(리지드바디 {_nb}개, 조인트 {_nj}개 비활성)")
except Exception as _e:
    _fail("물리", _e)

# ── 2. /clock 발행 ──────────────────────────────────────────────────
print("\n[2/7] /clock 발행")
try:
    exec(open(f"{DIR}/clock_pub.py").read())
    _ok("clock", "(ros2 topic hz /clock 로 확인)")
except Exception as _e:
    _fail("clock", _e)

# ── 3. 차량 노드 (F02) ──────────────────────────────────────────────
print("\n[3/7] 차량 노드 F02")
try:
    exec(open(f"{DIR}/kinematic_vehicle.py").read())
    _ok("F02", f"({list(fleet.vehicles)})")
except Exception as _e:
    _fail("F02", _e)

# ── 4. 차량 추가 (F03) ──────────────────────────────────────────────
# 단독 데모에서는 GPU 를 아끼려고 건너뛸 수 있다. 이 스크립트 전에
#   SETUP_F03 = False
# 를 먼저 실행하면 F03 을 만들지 않는다. (라이다·카메라가 통째로 빠져 부담이 준다)
try:
    _want_f03 = SETUP_F03
except NameError:
    _want_f03 = True

print(f"\n[4/7] 차량 추가 F03 {'' if _want_f03 else '(건너뜀)'}")
if not _want_f03:
    _ok("F03", "(SETUP_F03=False 로 생략)")
else:
    try:
        _f03 = "/World/Forklift_SIM_F03"
        _lift = f"{_f03}/forklift_c/lift"
        if _stage_setup.GetPrimAtPath(_f03).IsValid():
            # 씬(ff.usd)에 이미 들어 있다. 복제는 메시·라이다·그래프를 통째로
            # 만드는 무거운 작업이라, 있는 프림을 그대로 쓰고 fleet 에만 등록한다.
            if "SIM_F03" not in fleet.vehicles:
                fleet.spawn("SIM_F03", _f03, spawn=F03_SPAWN, lift_prim=_lift)
            _ok("F03", f"(씬에 있는 프림 재사용, {list(fleet.vehicles)})")
        else:
            exec(open(f"{DIR}/add_forklift.py").read())
            add_forklift("SIM_F03", spawn=F03_SPAWN, reuse=True)
            fleet.spawn("SIM_F03", _f03, spawn=F03_SPAWN, lift_prim=_lift)
            _ok("F03", f"(새로 복제, {list(fleet.vehicles)})")
    except Exception as _e:
        _fail("F03", _e)

# ── 5. 화물 (MQTT 수신) ─────────────────────────────────────────────
print("\n[5/7] 화물 모듈")
try:
    exec(open(f"{DIR}/cargo_demo.py").read())
    # 재생 중 프림 생성은 스테이지 재파싱을 유발해 Isaac 이 멈춘다.
    # 필요한 화물을 미리 만들어 숨겨 두고, 적재 때는 크기·위치만 바꾼다.
    # 이전 실행에서 남은 화물을 먼저 지운다. 안 그러면 차량이 아무것도
    # 안 했는데 포크에 파레트를 들고 있는 것처럼 보인다.
    reset_cargo_scene()
    _npool = prepare_cargo_boxes()
    enable_mqtt_cargo()
    _ok("화물", f"(상자 {_npool}개 준비, cargo 토픽으로 높이 전송)")
except Exception as _e:
    _fail("화물", _e)

# ── 6. 카메라 (MQTT 수신) ───────────────────────────────────────────
print("\n[6/7] 카메라 모듈")
try:
    exec(open(f"{DIR}/camera_switch.py").read())
    enable_mqtt_camera()
    _ok("카메라", "(fast/v1/sim/camera 로 시점 전환)")
except Exception as _e:
    _fail("카메라", _e)

# ── 6.5 실물 미러 (선택) ────────────────────────────────────────────
# 실물 오린카가 MQTT 로 보내는 좌표를 씬의 REAL_F01 에 그대로 반영한다.
#   실물 → MQTT fast/v1/vehicle/fk01/telemetry
#        → mqtt_bridge → ROS /real_f01/pose
#        → mirror.py → 씬 REAL_F01 프림
# 씬에 REAL_F01 프림이 없으면 조용히 건너뛴다.
# 끄고 싶으면 이 스크립트 전에  SETUP_MIRROR = False  를 실행한다.
try:
    _want_mirror = SETUP_MIRROR
except NameError:
    _want_mirror = True

print(f"\n[6.5] 실물 미러 {'' if _want_mirror else '(건너뜀)'}")
if not _want_mirror:
    _ok("미러", "(SETUP_MIRROR=False 로 생략)")
else:
    try:
        exec(open(f"{DIR}/mirror.py").read())
        enable_mirror()
        _ok("미러", "(/real_f01/pose 구독 — mqtt_bridge 가 있어야 좌표가 온다)")
    except Exception as _e:
        _fail("미러", _e)

# ── 7. 초기 배치 + 유틸 ─────────────────────────────────────────────
# place.py 를 함께 로드해 place() / where() / reset() 을 쓸 수 있게 한다.
# 배치를 손으로 바꿔가며 시험할 때 매번 필요하다.
print("\n[7/7] 차량 배치")
try:
    exec(open(f"{DIR}/place.py").read())
except Exception as _e:
    print(f"  (place.py 로드 실패, 무시: {_e})")
try:
    _placed = []
    for _vid, _sp in (("SIM_F02", F02_SPAWN), ("SIM_F03", F03_SPAWN),
                      ("REAL_F01", REAL_SPAWN)):
        _v = fleet.vehicles.get(_vid)
        if _v is None:
            continue
        _v.x, _v.y, _v.yaw = _sp
        _v.v = 0.0
        _v.steer = 0.0
        _v._steps = []
        _placed.append(f"{_vid}({_sp[0]},{_sp[1]})")
    _ok("배치", "(" + ", ".join(_placed) + ")  place()/where()/reset() 사용 가능")
except Exception as _e:
    _fail("배치", _e)

# ── 요약 ────────────────────────────────────────────────────────────
print("\n" + "=" * 60)
_bad = [s for s, good, _ in _result if not good]
for _s, _good, _d in _result:
    print(f"  {'OK  ' if _good else '실패'}  {_s}  {_d}")
if _bad:
    print(f"\n실패한 단계: {', '.join(_bad)}")
else:
    print("\n모두 준비됨. 다음 순서:")
    print("  1) Isaac 에서 ▶ Play 를 누른다  (프림 생성이 끝난 뒤에 눌러야 안 멈춘다)")
    print("  2) 터미널: ./nav2/scripts/run_all.sh --no-mqtt")
print("=" * 60)

try:
    import omni.timeline
    if omni.timeline.get_timeline_interface().is_playing():
        print("\n⚠ 이미 재생 중입니다. 재생 중 프림 생성은 Isaac 을 멈추게 합니다.")
        print("  다음부터는 Stop 상태에서 이 스크립트를 먼저 실행하세요.")
except Exception:
    pass
