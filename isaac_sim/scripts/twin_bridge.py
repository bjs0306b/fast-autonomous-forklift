"""
Isaac Sim <-> MQTT 트윈 브리지 (Sprint 1 초안)

하는 일 두 가지:
  1) 시뮬 지게차(SIM01)의 위치를 읽어 forklift/SIM01/location 으로 발행
  2) 실물 지게차(REAL01)의 위치를 구독해 씬 안의 트윈 인형 위치를 갱신

실행: Isaac Sim의 Script Editor에 붙여넣고 실행하거나, 확장(extension)에서 import 한다.

사전 준비:
    Isaac Sim 내장 파이썬에 paho-mqtt 설치
    (Windows 예시) <ISAAC_ROOT>\python.bat -m pip install paho-mqtt
"""

import json
import math
import threading
from datetime import datetime

import paho.mqtt.client as mqtt

# Isaac Sim 버전에 따라 import 경로가 다르다.
#   4.0 이하 : from omni.isaac.core.prims import XFormPrim
#   4.5 이상 : from isaacsim.core.prims import SingleXFormPrim as XFormPrim
# 아래가 실패하면 위 두 줄 중 맞는 쪽으로 바꾼다.
from omni.isaac.core.prims import XFormPrim
import omni.kit.app

# ---------------------------------------------------------------------------
# 설정 — 팀 규격에 맞춰 여기만 고치면 된다
# ---------------------------------------------------------------------------
BROKER_HOST = "localhost"   # EC2 브로커 붙일 땐 여기 주소 교체
BROKER_PORT = 1883

SIM_ID = "SIM_F01"
REAL_ID = "REAL_F01"

SIM_PRIM_PATH = "/World/Forklift_SIM_F01"    # 물리 O — Nav2가 굴리는 차량
REAL_PRIM_PATH = "/World/Forklift_REAL_F01"  # 물리 X — 실물 따라하는 인형

PUBLISH_HZ = 10.0     # 위치 발행 주기. 씬은 60fps로 돌지만 10Hz면 충분하다
STATUS_HZ = 1.0

# 트윈 씬을 미니어처 축척(실물 SLAM 맵과 1:1)으로 지었다면 1.0.
# 실물 크기로 지었다면 10.0 으로 바꾼다. (명세서 축척비 k=10)
SCALE = 1.0


# ---------------------------------------------------------------------------
# 유틸
# ---------------------------------------------------------------------------
def quat_to_yaw(q):
    """USD 쿼터니언(w, x, y, z) -> Z축 회전각(rad). 바닥을 도는 차량이라 yaw만 쓴다."""
    w, x, y, z = q[0], q[1], q[2], q[3]
    return math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))


def yaw_to_quat(yaw):
    """yaw(rad) -> USD 쿼터니언(w, x, y, z)"""
    half = yaw * 0.5
    return (math.cos(half), 0.0, 0.0, math.sin(half))


def now_iso():
    """백엔드 DTO가 java.time.LocalDateTime 이라 타임존 표기(Z, +09:00)를 붙이면 안 된다."""
    return datetime.now().isoformat(timespec="milliseconds")


# ---------------------------------------------------------------------------
# 브리지
# ---------------------------------------------------------------------------
class TwinBridge:
    def __init__(self):
        self.sim_prim = XFormPrim(SIM_PRIM_PATH)
        self.real_prim = XFormPrim(REAL_PRIM_PATH)

        # MQTT 콜백은 별도 스레드에서 돈다. 거기서 USD를 직접 건드리면 크래시하거나
        # 조용히 깨진다. 그래서 값만 여기 넣어두고, 실제 적용은 update 콜백(메인 스레드)에서 한다.
        self._pending_real_pose = None
        self._lock = threading.Lock()

        self._last_pub = 0.0
        self._last_status_pub = 0.0
        self._last_xy = None
        self._elapsed = 0.0

        self.client = mqtt.Client(client_id=f"isaac-{SIM_ID}")
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.client.connect(BROKER_HOST, BROKER_PORT, keepalive=30)
        self.client.loop_start()   # 수신 전용 스레드 시작

        self._sub = (
            omni.kit.app.get_app()
            .get_update_event_stream()
            .create_subscription_to_pop(self._on_update, name="twin_bridge")
        )
        print("[twin_bridge] started")

    # --- MQTT 쪽 (별도 스레드) ------------------------------------------------
    def _on_connect(self, client, userdata, flags, rc):
        print(f"[twin_bridge] connected rc={rc}")
        client.subscribe(f"forklift/{REAL_ID}/location", qos=1)

    def _on_message(self, client, userdata, msg):
        try:
            data = json.loads(msg.payload.decode())
        except (ValueError, UnicodeDecodeError) as e:
            print(f"[twin_bridge] bad payload: {e}")
            return

        with self._lock:
            self._pending_real_pose = (
                float(data["x"]) * SCALE,
                float(data["y"]) * SCALE,
                float(data["direction"]),
            )

    # --- Isaac 쪽 (메인 스레드) -----------------------------------------------
    def _on_update(self, event):
        dt = event.payload["dt"]
        self._elapsed += dt

        self._apply_real_pose()

        if self._elapsed - self._last_pub >= 1.0 / PUBLISH_HZ:
            self._publish_location(self._elapsed - self._last_pub)
            self._last_pub = self._elapsed

        if self._elapsed - self._last_status_pub >= 1.0 / STATUS_HZ:
            self._publish_status()
            self._last_status_pub = self._elapsed

    def _apply_real_pose(self):
        """MQTT로 받은 실물 좌표를 인형에 대입한다. 물리가 꺼져 있어야 튀지 않는다."""
        with self._lock:
            pose = self._pending_real_pose
            self._pending_real_pose = None
        if pose is None:
            return

        x, y, yaw = pose
        _, _, z = self.real_prim.get_world_pose()[0]   # 높이는 원래 값 유지
        self.real_prim.set_world_pose(
            position=(x, y, z),
            orientation=yaw_to_quat(yaw),
        )

    def _publish_location(self, dt):
        pos, quat = self.sim_prim.get_world_pose()
        x, y = float(pos[0]) / SCALE, float(pos[1]) / SCALE

        # 속도는 위치 변화량으로 근사한다. 나중에 Nav2 odom을 쓰면 더 정확하다.
        speed = 0.0
        if self._last_xy is not None and dt > 0:
            dx, dy = x - self._last_xy[0], y - self._last_xy[1]
            speed = math.hypot(dx, dy) / dt
        self._last_xy = (x, y)

        self._publish(f"forklift/{SIM_ID}/location", {
            "forkliftId": SIM_ID,
            "x": round(x, 4),
            "y": round(y, 4),
            "direction": round(quat_to_yaw(quat), 4),
            "speed": round(speed, 4),
            "timestamp": now_iso(),
        })

    def _publish_status(self):
        # TODO: 실제 상태로 교체. status 어휘는 E와 합의 필요
        #       (IDLE / MOVING / LIFTING / LOADING / ERROR / ESTOP)
        self._publish(f"forklift/{SIM_ID}/status", {
            "forkliftId": SIM_ID,
            "status": "IDLE",
            "battery": 100,
            "timestamp": now_iso(),
        })

    def _publish(self, topic, payload):
        self.client.publish(topic, json.dumps(payload), qos=1)

    def stop(self):
        self._sub.unsubscribe()
        self.client.loop_stop()
        self.client.disconnect()
        print("[twin_bridge] stopped")


bridge = TwinBridge()
# 중지하려면 Script Editor에서: bridge.stop()
