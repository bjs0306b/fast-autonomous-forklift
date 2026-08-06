"""MQTT/ROS 명령으로 Isaac 뷰포트 카메라를 바꾼다 (Script Editor 용).

관제 화면에서 "sim02 시점으로 보기" 를 누르면 시뮬 화면이 그 지게차 카메라로
전환되게 하는 용도다.

    백엔드 --MQTT--> mqtt_bridge --/sim/camera_cmd--> (여기) 뷰포트 전환

카메라 전환은 Isaac 뷰포트 API 를 써야 하므로 Isaac 안에서만 가능하다.
그래서 브릿지가 직접 못 하고 ROS 토픽을 한 번 거친다.

사용 (Script Editor):
    exec(open('/home/ubuntu/forklift_ws/nav2/scripts/camera_switch.py').read())
    list_cameras()            # 씬에 있는 카메라 목록
    set_camera("SIM_F02")     # 그 지게차 시점
    set_camera("top")         # 위에서 내려다보기
    enable_mqtt_camera()      # ROS 토픽 수신 시작
"""
import json

import omni.usd
from pxr import UsdGeom
from std_msgs.msg import String

_stage = omni.usd.get_context().get_stage()

# 이름 -> 카메라 프림 경로. 지게차 카메라는 씬을 훑어 자동으로 채운다.
PRESETS = {
    "persp": "/OmniverseKit_Persp",
    "top": "/OmniverseKit_Top",
    "front": "/OmniverseKit_Front",
}

_camera_sub = []


def list_cameras():
    """씬의 모든 카메라 프림을 찾아 프리셋에 등록하고 보여준다."""
    found = []
    for p in _stage.Traverse():
        if p.GetTypeName() != "Camera":
            continue
        path = str(p.GetPath())
        found.append(path)
        # /World/Forklift_SIM_F02/... 형태면 차량 ID 를 키로 등록
        for part in path.split("/"):
            if part.startswith("Forklift_"):
                PRESETS[part.replace("Forklift_", "")] = path
    print(f"카메라 {len(found)}개")
    for path in found:
        print(f"  {path}")
    print("사용 가능한 이름:", ", ".join(sorted(PRESETS)))
    return found


def set_camera(name):
    """뷰포트 활성 카메라를 바꾼다. name 은 프리셋 이름 또는 프림 경로."""
    path = PRESETS.get(name) or PRESETS.get(str(name).upper()) or name
    if not _stage.GetPrimAtPath(path).IsValid():
        print(f"카메라 없음: {name} -> {path}")
        print("  list_cameras() 로 목록을 확인하세요")
        return False
    try:
        from omni.kit.viewport.utility import get_active_viewport
        vp = get_active_viewport()
        if vp is None:
            print("활성 뷰포트를 찾지 못함")
            return False
        vp.camera_path = path
    except Exception as e:
        print(f"카메라 전환 실패: {e}")
        return False
    print(f"카메라 전환: {name} -> {path}")
    return True


# ROS 수신 스레드에서 뷰포트를 건드리면 화물과 같은 이유로 위험하다.
# (USD/뷰포트 조작은 메인 스레드 전용) 요청은 큐에 넣고 메인 스레드에서 처리한다.
import threading
import omni.kit.app

_pending_cam = []
_cam_lock = threading.Lock()
_cam_drain_sub = [None]


def _on_camera_cmd(msg):
    """ROS 수신 스레드. 큐에만 넣는다."""
    try:
        p = json.loads(msg.data)
        name = p.get("camera") or p.get("view")
    except Exception:
        name = msg.data.strip()      # 그냥 문자열로 와도 받아준다
    if not name:
        print(f"[camera_cmd] camera 값이 없음: {msg.data}")
        return
    with _cam_lock:
        _pending_cam.append(name)


def _drain_camera_cmds(event):
    """앱 업데이트(메인 스레드)에서 실제 전환을 수행한다."""
    if not _pending_cam:
        return
    with _cam_lock:
        items, _pending_cam[:] = list(_pending_cam), []
    for name in items:
        try:
            set_camera(name)
        except Exception as e:
            print(f"[camera_cmd] 전환 실패: {e}")


def enable_mqtt_camera():
    """/sim/camera_cmd 를 구독해 카메라를 전환한다."""
    if _camera_sub:
        print("이미 활성화됨")
        return
    sub = fleet.create_subscription(          # noqa: F821
        String, "/sim/camera_cmd", _on_camera_cmd, 10)
    _camera_sub.append(sub)
    if _cam_drain_sub[0] is None:
        _cam_drain_sub[0] = (omni.kit.app.get_app()
                             .get_update_event_stream()
                             .create_subscription_to_pop(
                                 _drain_camera_cmds, name="camera_cmd_drain"))
    print("카메라 전환 대기 중 (/sim/camera_cmd, 메인 스레드에서 처리)")


list_cameras()
print("camera_switch 로드됨.  set_camera('SIM_F02') / enable_mqtt_camera()")
