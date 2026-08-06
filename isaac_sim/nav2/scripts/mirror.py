"""실물 지게차를 시뮬에 그대로 비추는 트윈 미러 (Isaac Script Editor 용).

    실물 지게차 → MQTT → mqtt_bridge → /real_f01/pose → (여기) 씬의 REAL_F01 이동

시뮬 차량(SIM_F02/F03)은 Nav2 가 몰지만, 이 차량은 스스로 움직이지 않는다.
받은 좌표를 그대로 반영하기만 한다(미러 모드). 그래서 화면에서 실물과 시뮬
차량이 같은 창고 안에서 함께 움직이는 것처럼 보인다.

좌표 축척
    2026-08-05 확정: MQTT 위에는 시뮬 좌표를 그대로 싣는다. 실물(오린카)이
    자기 위치를 보낼 때 ×10 해서 시뮬 좌표로 올리고, 목표를 받을 때 ÷10 해서
    쓴다. 그래서 여기서는 변환하지 않는다 (REAL_SCALE = 1.0).

사용:
    exec(open('/home/ubuntu/forklift_ws/nav2/scripts/mirror.py').read())
    enable_mirror()                  # /real_f01/pose 구독 시작

    # 손으로 확인해 보기
    mirror_to(5.0, 8.0, 0.0)         # 실물이 (0.5, 0.8) 에 있다고 가정한 시뮬 좌표
"""
import json
import math

import omni.usd
from std_msgs.msg import String

REAL_ID = "REAL_F01"
REAL_PRIM = f"/World/Forklift_{REAL_ID}"
REAL_TOPIC = "/real_f01/pose"
REAL_SCALE = 1.0         # MQTT 는 시뮬 좌표계 — 그대로 쓴다

_mirror_sub = []
_stage_m = omni.usd.get_context().get_stage()


def _ensure_real_vehicle():
    """씬의 REAL_F01 을 fleet 에 미러 모드로 등록한다."""
    if REAL_ID in fleet.vehicles:            # noqa: F821
        return fleet.vehicles[REAL_ID]       # noqa: F821
    if not _stage_m.GetPrimAtPath(REAL_PRIM).IsValid():
        # 씬에 없으면 SIM_F02 를 복제해 만든다.
        # 복제는 메시·라이다·그래프를 통째로 만드는 무거운 작업이라 재생 중에
        # 하면 Isaac 이 수십 초 멈춘다. Stop 상태에서 하고, 한 번 만든 뒤
        # 씬을 저장(Ctrl+S)해 두면 다음부터는 재사용된다.
        print(f"씬에 {REAL_PRIM} 이 없습니다 — SIM_F02 를 복제해 만듭니다."
              f" (Stop 상태에서 하는 것이 안전)")
        try:
            exec(open("/home/ubuntu/forklift_ws/nav2/scripts/add_forklift.py")
                 .read(), globals())
            add_forklift(REAL_ID, spawn=(3.0, 2.0, 0.0), reuse=True)  # noqa: F821
        except Exception as e:
            print(f"복제 실패: {e}")
            return None
        if not _stage_m.GetPrimAtPath(REAL_PRIM).IsValid():
            print("복제했는데도 프림이 없습니다.")
            return None
        print(f"{REAL_PRIM} 생성됨 — 씬을 저장(Ctrl+S)해 두면 다음부터 재사용")
    lift = f"{REAL_PRIM}/forklift_c/lift"
    v = fleet.spawn(REAL_ID, REAL_PRIM,      # noqa: F821
                    spawn=(3.0, 2.0, 0.0),
                    lift_prim=lift if _stage_m.GetPrimAtPath(lift).IsValid()
                    else None)
    v.mirror = True                          # cmd_vel 로 움직이지 않는다
    print(f"{REAL_ID} 미러 모드로 등록됨")
    return v


def mirror_to(x, y, yaw=0.0):
    """시뮬 좌표로 직접 옮겨 본다 (동작 확인용)."""
    v = _ensure_real_vehicle()
    if v is None:
        return False
    v.set_mirror_pose(x, y, yaw)
    print(f"{REAL_ID} → ({x}, {y}, {math.degrees(yaw):.0f}도)")
    return True


def _on_pose(msg):
    """ROS 콜백(다른 스레드). 값만 넘기고 USD 는 건드리지 않는다."""
    try:
        p = json.loads(msg.data)
    except Exception:
        print(f"[mirror] JSON 파싱 실패: {msg.data}")
        return
    pose = p.get("pose", p)
    try:
        x = float(pose["x"]) * REAL_SCALE
        y = float(pose["y"]) * REAL_SCALE
        yaw = float(pose.get("yaw", 0.0))     # 각도는 축척과 무관
    except (KeyError, TypeError, ValueError):
        print(f"[mirror] x/y 를 찾지 못함: {p}")
        return
    v = fleet.vehicles.get(REAL_ID)           # noqa: F821
    if v is not None:
        v.set_mirror_pose(x, y, yaw)


def set_mirror_smooth(seconds=0.12, snap=2.0, vehicle=REAL_ID):
    """미러 보간을 조절한다.

        set_mirror_smooth(0.05)   # 즉각 반응 (조금 끊길 수 있음)
        set_mirror_smooth(0.20)   # 아주 부드럽지만 더 뒤처짐
        set_mirror_smooth(0.0)    # 보간 없음 (예전 동작)

    snap 보다 크게 튀면 보간하지 않고 즉시 옮긴다 (AMCL 재정합 대비).
    """
    v = fleet.vehicles.get(vehicle)          # noqa: F821
    if v is None:
        print(f"차량 '{vehicle}' 없음")
        return False
    v.p["mirror_smooth"] = float(seconds)
    v.p["mirror_snap"] = float(snap)
    print(f"{vehicle} 미러 보간: {seconds}초 따라붙기, {snap} m 넘으면 즉시 이동")
    return True


def enable_mirror():
    """실물 좌표 토픽을 구독해 씬의 REAL_F01 을 따라 움직이게 한다."""
    if _mirror_sub:
        print("이미 활성화됨")
        return
    if _ensure_real_vehicle() is None:
        return
    sub = fleet.create_subscription(          # noqa: F821
        String, REAL_TOPIC, _on_pose, 10)
    _mirror_sub.append(sub)
    print(f"미러 대기 중: {REAL_TOPIC}  (축척 x{REAL_SCALE})")
    print("  실물 → MQTT → mqtt_bridge → 이 토픽 → 씬의 REAL_F01")


print("mirror 로드됨.  enable_mirror() / mirror_to(5.0, 8.0, 0.0)")
