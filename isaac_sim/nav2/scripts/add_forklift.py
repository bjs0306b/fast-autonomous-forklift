"""2번째(이후) 지게차를 복제해 다중 차량을 만든다 (Isaac Script Editor 용).

기존 /World/Forklift_SIM_F02 를 통째로 복제하고, 라이다 그래프의
nodeNamespace/frameId 를 새 ID 로 바꾼다. collision 은 켜고 RigidBody 는
kinematic 으로 둬서 서로 라이다로 감지하되 물리에 안 밀리게 한다.

사용:
    exec(open('/home/ubuntu/forklift_ws/nav2/scripts/add_forklift.py').read())
    add_forklift("SIM_F03", spawn=(8.0, 5.0, 0.0))

    # kinematic_vehicle.py 의 fleet 에도 등록:
    fleet.spawn("SIM_F03", "/World/Forklift_SIM_F03",
                spawn=(8.0, 5.0, 0.0),
                lift_prim="/World/Forklift_SIM_F03/forklift_c/lift")
"""
import omni.usd
from pxr import UsdGeom, UsdPhysics, PhysxSchema, Sdf

_stage = omni.usd.get_context().get_stage()
_SRC = "/World/Forklift_SIM_F02"


def add_forklift(new_id, spawn=(8.0, 5.0, 0.0), reuse=True):
    """SIM_F02 를 복제해 new_id(예: 'SIM_F03') 지게차를 만든다.

    reuse=True 이면 이미 그 프림이 있을 때 복제를 건너뛰고 위치·설정만 맞춘다.
    지게차 복제는 메시·라이다·OmniGraph 를 통째로 만드는 무거운 작업이라,
    재생 중에 하면 Isaac 이 스테이지를 다시 파싱하느라 수십 초 멈춘다.
    한 번 만든 뒤 씬을 저장(Ctrl+S)해 두면 다시 만들 필요가 없다.
    """
    dst = f"/World/Forklift_{new_id}"
    old_ns = "sim_f02"
    new_ns = new_id.lower()          # SIM_F03 -> sim_f03
    old_frame = "SIM_F02_laser"
    new_frame = f"{new_id}_laser"

    exists = _stage.GetPrimAtPath(dst).IsValid()
    if exists and reuse:
        print(f"{new_id} 이미 있음 — 복제 건너뜀 (위치·설정만 갱신)")
    else:
        if exists:
            _stage.RemovePrim(dst)
        # 1) 프림 통째 복제 (무거움: 정지 상태에서 하는 것이 좋다)
        omni.usd.duplicate_prim(_stage, _SRC, dst) \
            if hasattr(omni.usd, "duplicate_prim") \
            else Sdf.CopySpec(_stage.GetRootLayer(), Sdf.Path(_SRC),
                              _stage.GetRootLayer(), Sdf.Path(dst))
        if not _stage.GetPrimAtPath(dst).IsValid():
            print(f"복제 실패: {dst}")
            return

    # 2) 위치 설정
    x, y, yaw = spawn
    root = _stage.GetPrimAtPath(dst)
    xf = UsdGeom.XformCommonAPI(root)
    xf.SetTranslate((x, y, 0.0))
    xf.SetRotate((0.0, 0.0, yaw * 57.2958))   # Z 회전(도)

    # 3) 라이다 그래프의 네임스페이스/프레임 교체
    changed = 0
    for p in _stage.Traverse():
        path = str(p.GetPath())
        if not path.startswith(dst):
            continue
        for attr in p.GetAttributes():
            nm = attr.GetName()
            if "nodeNamespace" in nm and attr.Get() == old_ns:
                attr.Set(new_ns); changed += 1
            elif "frameId" in nm and attr.Get() == old_frame:
                attr.Set(new_frame); changed += 1
        # collision 켜기 + RigidBody kinematic
        if p.HasAPI(UsdPhysics.RigidBodyAPI) or p.HasAPI(PhysxSchema.PhysxRigidBodyAPI):
            UsdPhysics.RigidBodyAPI.Apply(p).CreateKinematicEnabledAttr(True)
        for attr in p.GetAttributes():
            if "collisionEnabled" in attr.GetName():
                attr.Set(True)

    print(f"{new_id} 생성: {dst} at {spawn}, 네임스페이스 {new_ns}, 교체 {changed}개")
    print(f"다음: fleet.spawn('{new_id}', '{dst}', spawn={spawn}, "
          f"lift_prim='{dst}/forklift_c/lift')")


print("add_forklift 로드됨. 실행: add_forklift('SIM_F03', spawn=(8.0, 5.0, 0.0))")
