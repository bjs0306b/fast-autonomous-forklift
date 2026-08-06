"""데모 전 차량 위치 리셋 (Isaac Script Editor 용).

데모가 끝나면 차량은 마지막 지점에 서 있다. 다시 돌리려면 출발 위치로
되돌려야 한다. 이 스크립트를 실행하면 기본 배치로 돌아간다.

    exec(open('/home/ubuntu/forklift_ws/nav2/scripts/place.py').read())

개별 지정도 된다:
    place("SIM_F02", 15.5, 4.0, 0.0)      # x, y, yaw(rad)
    place("SIM_F03", 10.0, 4.0)
    where()                                # 현재 위치 확인

주의 — 순간이동 뒤에는 3초쯤 기다렸다 목표를 준다.
    프림을 순간이동시키면 Nav2 의 TF 버퍼에 옛 위치가 잠시 남는다. 그 상태로
    목표를 주면 "이미 도착" 으로 처리되어 안 움직이고 SUCCEEDED 가 뜬다.
    demo_solo.py / demo_scenario.py 는 시작할 때 costmap 을 지우고 TF 를
    기다리므로, 이 스크립트 실행 후 바로 데모를 돌려도 된다.
"""
import math

# 기본 배치 — 순환로 아래 홀. 두 대가 겹치지 않게 5.5 m 떨어뜨린다.
DEFAULT_PLACE = {
    "SIM_F02": (15.5, 4.0, 0.0),
    "SIM_F03": (10.0, 4.0, 0.0),
}


def place(vehicle, x, y, yaw=0.0):
    """차량 하나를 (x, y, yaw) 로 옮긴다."""
    v = fleet.vehicles.get(vehicle)      # noqa: F821
    if v is None:
        print(f"차량 '{vehicle}' 없음. 있는 차량: {list(fleet.vehicles)}")  # noqa: F821
        return False
    v.x, v.y, v.yaw = float(x), float(y), float(yaw)
    v.v = 0.0
    v.steer = 0.0
    v._steps = []                        # 진행 중이던 미션 비우기
    print(f"{vehicle} → ({x}, {y}, {math.degrees(yaw):.0f}도)")
    return True


def reset(places=None):
    """전 차량을 기본(또는 지정) 배치로 되돌린다."""
    for vid, sp in (places or DEFAULT_PLACE).items():
        place(vid, *sp)
    # 들고 있던 화물도 정리 (다시 적재할 수 있게)
    try:
        clear_cargo()                    # noqa: F821
    except NameError:
        pass
    print("리셋 완료 — 이제 데모를 실행하세요")


def where():
    """현재 위치를 보여준다."""
    for vid, v in fleet.vehicles.items():   # noqa: F821
        cargo = f", 화물 {v.cargo_id}" if v.cargo is not None else ""
        print(f"  {vid}: ({v.x:.2f}, {v.y:.2f}) "
              f"{math.degrees(v.yaw):.0f}도, 포크 {v.lift_height:.2f}m{cargo}")


reset()
print("place 로드됨.  reset() / place('SIM_F02', 15.5, 4.0) / where()")
