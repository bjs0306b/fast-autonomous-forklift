"""오린카 흉내 — 실물이 준비되기 전에 미러·관제 경로를 시험한다.

    이 스크립트 → MQTT fast/v1/vehicle/fk01/telemetry (10 Hz)
                → mqtt_bridge → /real_f01/pose → Isaac 의 REAL_F01

좌표는 실물 규격과 똑같이 **시뮬 좌표계(0~20 x 0~30)** 로 보낸다.
실제 오린카도 자기가 잰 실물 m 에 x10 해서 이 범위로 보내게 된다.

task / control 도 구독해서 실물처럼 반응한다.
  - control ESTOP/HOLD  -> 그 자리에 정지
  - control RESUME      -> 재개
  - task {x, y}         -> 그 좌표로 이동 (직선)

사용:
    source /home/ubuntu/forklift_ws/nav2/config/mqtt.env
    python3 fake_orin.py                # 순환로를 따라 반시계로 돈다
    python3 fake_orin.py circle         # 창고 가운데를 원으로
    python3 fake_orin.py line           # 왕복 직선
    python3 fake_orin.py park 10 15     # 그 자리에 서 있기만
    python3 fake_orin.py loop --speed 2 # 속도 지정 (시뮬 m/s)

종료: Ctrl+C
"""
import json
import math
import os
import ssl
import sys
import time

try:
    import paho.mqtt.client as mqtt
except ImportError:
    raise SystemExit("paho-mqtt 가 없습니다:  pip3 install paho-mqtt")

HOST = os.environ.get("MQTT_HOST", "i15a304.p.ssafy.io")
PORT = int(os.environ.get("MQTT_PORT", 8883))
USER = os.environ.get("MQTT_USER")
PASS = os.environ.get("MQTT_PASS")
CA = os.environ.get("MQTT_CA")
VID = os.environ.get("FAKE_ORIN_ID", "fk01")

BASE = "fast/v1/vehicle"
HZ = 10.0                      # 실물 규격과 같은 발행 주기
DT = 1.0 / HZ

# 순환로 모서리 (시뮬 좌표, 반시계). demo_loop2 와 같은 경로다.
LOOP = [(15.5, 4.0), (15.5, 27.0), (5.0, 27.0), (5.0, 4.0)]


def wrap(a):
    return math.atan2(math.sin(a), math.cos(a))


class FakeOrin:
    def __init__(self, mode, speed, start):
        self.mode = mode
        self.speed = speed
        self.x, self.y, self.yaw = start
        self.stopped = False
        self.stop_kind = ""
        self.goal = None           # task 로 받은 목표
        self.task_id = None
        self.i = 0                 # 순환로 모서리 index
        self.t = 0.0
        self.fork = 0.0
        self.loaded = False

        self.cli = mqtt.Client(client_id=f"fake-{VID}")
        if USER:
            self.cli.username_pw_set(USER, PASS)
        if CA:
            self.cli.tls_set(ca_certs=CA)
            self.cli.tls_insecure_set(True)      # 인증서 CN 이 IP 라서
        else:
            self.cli.tls_set(cert_reqs=ssl.CERT_NONE)
            self.cli.tls_insecure_set(True)
        self.cli.on_connect = self._on_connect
        self.cli.on_message = self._on_message
        self.cli.connect(HOST, PORT, keepalive=30)
        self.cli.loop_start()

    # ── MQTT ────────────────────────────────────────────────────────
    def _on_connect(self, c, u, f, rc):
        if rc != 0:
            print(f"연결 실패 rc={rc} (4=아이디/비번 틀림)")
            return
        c.subscribe(f"{BASE}/{VID}/task", qos=1)
        c.subscribe(f"{BASE}/{VID}/control", qos=1)
        c.subscribe("fast/v1/control/all", qos=1)
        print(f"연결됨 {HOST}:{PORT}  —  {VID} 로 발행/구독")

    def _on_message(self, c, u, msg):
        try:
            p = json.loads(msg.payload.decode())
        except Exception:
            return
        if msg.topic.endswith("/control"):
            cmd = str(p.get("command", "")).upper()
            if cmd in ("ESTOP", "HOLD"):
                self.stopped, self.stop_kind = True, cmd
                print(f"[control] {cmd} — 정지")
            elif cmd == "RESUME":
                self.stopped, self.stop_kind = False, ""
                print("[control] RESUME — 재개")
            elif cmd == "CANCEL_TASK":
                self.goal, self.task_id = None, None
                print("[control] CANCEL_TASK — 목표 취소")
        elif msg.topic.endswith("/task"):
            g = p.get("pickup") or p.get("approach") or p
            if "x" in g and "y" in g:
                # 실물이라면 여기서 ÷10 해서 자기 좌표로 바꾼다.
                # 이 흉내 스크립트는 시뮬 좌표로 그대로 움직인다.
                self.goal = (float(g["x"]), float(g["y"]))
                self.task_id = p.get("taskId")
                print(f"[task] {self.task_id} → {self.goal}")

    # ── 움직임 ──────────────────────────────────────────────────────
    def step(self):
        self.t += DT
        if self.stopped:
            return
        if self.goal is not None:
            self._toward(*self.goal, arrive=0.3)
            if math.hypot(self.goal[0] - self.x, self.goal[1] - self.y) < 0.3:
                print(f"[task] {self.task_id} 도착")
                self.goal = None
            return
        if self.mode == "loop":
            tx, ty = LOOP[self.i]
            self._toward(tx, ty, arrive=0.5)
            if math.hypot(tx - self.x, ty - self.y) < 0.5:
                self.i = (self.i + 1) % len(LOOP)
        elif self.mode == "circle":
            w = self.speed / 6.0
            self.x = 10.0 + 4.0 * math.cos(w * self.t)
            self.y = 15.0 + 6.0 * math.sin(w * self.t)
            self.yaw = wrap(w * self.t + math.pi / 2)
        elif self.mode == "line":
            span, y0 = 10.0, 15.0
            self.x = 5.0 + span * (0.5 - 0.5 * math.cos(self.speed / span * self.t))
            self.y = y0
            self.yaw = 0.0 if math.sin(self.speed / span * self.t) > 0 else math.pi
        # park 는 아무것도 안 한다

    def _toward(self, tx, ty, arrive=0.3):
        d = math.hypot(tx - self.x, ty - self.y)
        if d < 1e-6:
            return
        want = math.atan2(ty - self.y, tx - self.x)
        # 방향을 한 번에 꺾지 않는다 (실물처럼 보이게)
        self.yaw = wrap(self.yaw + max(-2.0 * DT, min(2.0 * DT,
                                                      wrap(want - self.yaw))))
        step = min(self.speed * DT, d)
        self.x += step * math.cos(self.yaw)
        self.y += step * math.sin(self.yaw)

    # ── 발행 ────────────────────────────────────────────────────────
    def publish(self):
        state = ("ESTOPPED" if self.stop_kind == "ESTOP"
                 else "HOLDING" if self.stop_kind == "HOLD"
                 else "IDLE" if self.mode == "park" and self.goal is None
                 else "MOVING")
        self.cli.publish(f"{BASE}/{VID}/telemetry", json.dumps({
            "vehicleId": VID,
            "ts": int(time.time() * 1000),
            "pose": {"x": round(self.x, 2), "y": round(self.y, 2),
                     "yaw": round(self.yaw, 4)},
            "velocity": {"linear": 0.0 if self.stopped else self.speed,
                         "angular": 0.0},
            "forkHeight": round(self.fork, 2),
            "loaded": self.loaded,
            "cargoId": None,
            "cargo": None,
            "state": state,
            "taskId": self.task_id,
            "battery": 87.5,
        }), qos=0)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    mode = args[0] if args else "loop"
    if mode not in ("loop", "circle", "line", "park"):
        print(__doc__)
        return 1

    speed = 1.5
    if "--speed" in sys.argv:
        speed = float(sys.argv[sys.argv.index("--speed") + 1])

    start = (15.5, 4.0, 0.0)
    if mode == "park" and len(args) >= 3:
        start = (float(args[1]), float(args[2]), 0.0)

    if not USER:
        print("경고: MQTT_USER 가 없습니다. mqtt.env 를 source 하세요.")

    o = FakeOrin(mode, speed, start)
    print(f"모드 {mode}, 속도 {speed} (시뮬 m/s), {HZ:.0f} Hz 발행")
    print("Isaac 에서 REAL_F01 이 따라오는지 보세요.  종료: Ctrl+C")
    try:
        n = 0
        while True:
            o.step()
            o.publish()
            n += 1
            if n % 50 == 0:                       # 5초마다 한 줄
                print(f"  ({o.x:5.2f}, {o.y:5.2f})  yaw {o.yaw:+.2f}"
                      f"{'  [정지]' if o.stopped else ''}")
            time.sleep(DT)
    except KeyboardInterrupt:
        print("\n중단됨")
    finally:
        o.cli.loop_stop()
        o.cli.disconnect()
    return 0


if __name__ == "__main__":
    sys.exit(main())
