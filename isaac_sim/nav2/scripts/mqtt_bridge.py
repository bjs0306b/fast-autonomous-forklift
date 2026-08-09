#!/usr/bin/env python3
"""ROS2 <-> MQTT 브릿지. docs/interface-spec.md 형식을 그대로 따른다.

발행 (차량 -> 백엔드)
    fast/v1/vehicle/{id}/telemetry   10Hz, QoS 0
        {"vehicleId","ts","pose":{x,y,yaw},"velocity":{linear,angular},
         "forkHeight","loaded","cargoId","state","taskId","battery"}
    fast/v1/vehicle/{id}/event       상태 전환 시, QoS 1

구독 (백엔드 -> 차량)
    fast/v1/vehicle/{id}/task        {"taskId","pickup":{x,y,yaw,forkHeight},
                                      "dropoff":{...}}  -> Nav2 목표로 실행
    fast/v1/vehicle/{id}/control     {"command":"ESTOP"|"RESUME"|"HOLD"|"CANCEL_TASK"}
    fast/v1/control/all              전 차량 동시 (주로 ESTOP)

간단 테스트용으로 task 에 {"x":10,"y":4} 처럼 좌표만 줘도 그 자리로 간다.

정지 방식 (2단):
    1) Nav2 목표 취소 -> 컨트롤러가 cmd_vel 발행을 멈춘다
    2) /{ns}/cmd_vel 에 0 을 20Hz 로 계속 -> 확실히 선다
    차량 노드에 0.5초 워치독이 있어 명령이 끊겨도 서지만, ESTOP 은 확실해야 한다.
    ESTOP/HOLD 중에는 새 task 를 무시한다. RESUME 이 와야 풀린다.

스레드 주의:
    paho 의 on_message 는 ROS 실행 스레드가 아니다. 거기서 액션/서비스를 직접
    호출하면 불안정하므로, 받은 메시지는 큐에만 넣고 ROS 타이머에서 처리한다.

준비:
    sudo apt install -y mosquitto mosquitto-clients
    pip3 install paho-mqtt

사용:
    source /opt/ros/humble/setup.bash
    python3 mqtt_bridge.py
    python3 mqtt_bridge.py --host 192.168.0.10
"""
import argparse
import json
import math
import os
import threading
import time

import rclpy
from rclpy.node import Node
from rclpy.qos import QoSProfile, QoSDurabilityPolicy
from rclpy.action import ActionClient
from action_msgs.srv import CancelGoal
from geometry_msgs.msg import Twist, PoseStamped
from nav2_msgs.action import NavigateToPose
from std_msgs.msg import String, Float32
from tf2_ros import Buffer, TransformListener

try:
    import paho.mqtt.client as mqtt
except ImportError:
    raise SystemExit("paho-mqtt 가 없습니다.  pip3 install paho-mqtt")

# ROS 네임스페이스 -> MQTT vehicleId (실물 fk01, 시뮬 sim02/sim03)
# MQTT vehicleId 는 Isaac 프림 이름과 숫자를 맞춘다.
#   SIM_F02 -> sim02,  SIM_F03 -> sim03,  실물 REAL_F01 -> fk01
# (예전에는 sim01/sim02 라 프림 번호와 하나씩 어긋나 헷갈렸다)
VEHICLES = [
    {"ns": "sim_f02", "frame": "SIM_F02", "id": "sim02"},
    {"ns": "sim_f03", "frame": "SIM_F03", "id": "sim03"},
]

BASE = "fast/v1/vehicle"
ALL_CONTROL = "fast/v1/control/all"
# 관제 화면에서 시뮬 시점을 바꿀 때. {"camera": "SIM_F02"} 또는 {"view": "top"}
SIM_CAMERA = "fast/v1/sim/camera"

# 실물 지게차 → 시뮬 미러(트윈). 실물이 보내는 telemetry 를 받아 Isaac 의
# REAL_F01 프림을 같은 자리로 움직인다. Isaac 쪽은 mirror.py 가 받는다.
REAL_VEHICLES = {
    "fk01": "/real_f01/pose",       # MQTT telemetry -> ROS (미러용, 들어옴)
}
# 실물의 화물 사건을 시뮬에 반영한다. 실물이 세트장에서 실제로 적재를 끝내면
# 시뮬의 REAL_F01 도 같은 화물을 들거나 같은 랙에 놓아야 그림이 맞는다.
#   fast/v1/vehicle/fk01/cargo  ->  ROS /real_f01/cargo_cmd  ->  cargo_demo
REAL_CARGO = {
    "fk01": "/real_f01/cargo_cmd",
}

# 관제 -> 실물 명령 중계. 관제 스크립트는 ROS 토픽으로만 말하고, MQTT 전송은
# 여기서 한다. 그래야 관제가 시뮬/실물을 같은 방식(ROS 발행)으로 다룰 수 있다.
REAL_CMD_OUT = {
    "/out/fk01/task":    ("fk01", "task"),
    "/out/fk01/control": ("fk01", "control"),
}
TELEMETRY_HZ = 10.0

# 내보낼 좌표의 눈금.
# 2026-08-05 확정: MQTT 위에는 **시뮬 좌표를 그대로** 싣는다 (나누지 않는다).
# 실물(오린카)이 자기 쪽에서 ÷10 해서 쓰고, 자기 위치를 보낼 때는 ×10 해서
# 시뮬 좌표로 올린다. 즉 MQTT 는 시뮬 좌표계 하나로 통일된다.
#   1.0   시뮬 좌표 그대로 (x=17.0)   ← 확정값
#   10.0  실물 m 로 나눠서 (x=1.70)   옛 방식
POSE_SCALE = float(os.environ.get("MQTT_POSE_SCALE", "1.0"))
FORK_WAIT = 4.0          # 포크 명령 후 기다리는 시간(초)

# 입고 바이. 여기 도착해 멈추면 arrived 를 발행한다(AI 측정 트리거).
# docs/mqtt-arrived.md 참조. 좌표는 시뮬 좌표 그대로 내보낸다(SCALE=1.0).
BAY_XY = (16.5, 5.0)
BAY_RADIUS = 2.0
SCALE = 1.0


def _scale_cargo(c):
    """화물 크기를 좌표와 같은 눈금으로 맞춘다."""
    if not c:
        return None
    k = POSE_SCALE
    return {"id": c.get("id"),
            "w": round(float(c.get("w", 0)) / k, 3),
            "d": round(float(c.get("d", 0)) / k, 3),
            "h": round(float(c.get("h", 0)) / k, 3)}


def now_ms():
    return int(time.time() * 1000)


def make_pose(x, y, yaw):
    p = PoseStamped()
    p.header.frame_id = "map"
    p.pose.position.x = float(x)
    p.pose.position.y = float(y)
    p.pose.orientation.z = math.sin(float(yaw) / 2.0)
    p.pose.orientation.w = math.cos(float(yaw) / 2.0)
    return p


class MqttBridge(Node):
    def __init__(self, host, port, user=None, password=None, tls=False,
                 ca=None, insecure=False):
        super().__init__("mqtt_bridge")
        from rclpy.parameter import Parameter
        self.set_parameters([Parameter("use_sim_time", Parameter.Type.BOOL, True)])

        self.buf = Buffer()
        TransformListener(self.buf, self)

        self.inbox = []                     # (topic, payload) - MQTT 스레드가 넣음
        self.inbox_lock = threading.Lock()
        # 카메라 전환은 Isaac 뷰포트 API 가 필요해 Isaac 안에서만 가능하다.
        # 브릿지는 이 토픽으로 넘기고, camera_switch.enable_mqtt_camera() 가 받는다.
        self.camera_pub = self.create_publisher(String, "/sim/camera_cmd", 10)
        # 실물 좌표를 Isaac 으로 넘기는 토픽 (트윈 미러)
        self.real_cargo_pubs = {
            rid: self.create_publisher(String, topic, 10)
            for rid, topic in REAL_CARGO.items()}
        self.real_pubs = {rid: self.create_publisher(String, topic, 10)
                          for rid, topic in REAL_VEHICLES.items()}
        # 관제가 ROS 로 낸 실물 명령을 MQTT 로 내보낸다
        for ros_topic, (rid, kind) in REAL_CMD_OUT.items():
            self.create_subscription(
                String, ros_topic,
                lambda msg, r=rid, k=kind: self.relay_to_mqtt(r, k, msg), 10)

        for v in VEHICLES:
            v["state"] = None               # 차량이 낸 상태 JSON
            v["last_event"] = None
            v["stopped"] = False
            v["stop_kind"] = ""
            self.publish_hold(v)
            v["task_id"] = None
            v["mission"] = []               # 남은 단계
            v["busy"] = False               # 목표 수행 중
            v["wait_until"] = 0.0           # 포크 대기 종료 시각
            self.create_subscription(
                String, f"/{v['ns']}/state",
                lambda msg, veh=v: veh.__setitem__("state", msg.data), 10)
            v["cmd_pub"] = self.create_publisher(Twist, f"/{v['ns']}/cmd_vel", 10)
            # 관제가 이 차를 멈춰 세웠다는 사실을 ROS 쪽에도 알린다.
            # 이게 없으면 demo_loop2 의 정체 감시가 "20초째 안 움직인다"며
            # 목표를 다시 보내고, 정지 명령이 사실상 무효가 된다.
            v["hold_pub"] = self.create_publisher(
                String, f"/{v['ns']}/control_state",
                QoSProfile(depth=1,
                           durability=QoSDurabilityPolicy.TRANSIENT_LOCAL))
            v["fork_pub"] = self.create_publisher(Float32, f"/{v['ns']}/fork_cmd", 10)
            # 화물 생성은 USD 접근이 필요해 Isaac 안에서만 가능하다.
            # 브릿지는 이 토픽으로 넘기고, cargo_demo.enable_mqtt_cargo() 가 받는다.
            v["cargo_pub"] = self.create_publisher(
                String, f"/{v['ns']}/cargo_cmd", 10)
            v["arrived_sent"] = False       # 바이 도착 알림 중복 방지
            v["nav_ac"] = ActionClient(
                self, NavigateToPose, f"/{v['ns']}/navigate_to_pose")
            v["cancel_cli"] = self.create_client(
                CancelGoal, f"/{v['ns']}/navigate_to_pose/_action/cancel_goal")

        self.cli = mqtt.Client()
        # 인증이 걸린 브로커(백엔드 팀 운영)면 아이디/비번이 필요하다.
        # 직접 띄운 브로커에 allow_anonymous true 면 없어도 된다.
        if user:
            self.cli.username_pw_set(user, password or None)
            self.get_logger().info(f"MQTT 인증: {user}")
        if tls or ca or insecure:
            # 이 프로젝트 브로커(i15a304.p.ssafy.io:8883)는 자체 서명 CA
            # (FAST-MQTT-CA)를 쓰고, 인증서 CN 이 호스트명이 아니라 IP 다.
            # 그래서 시스템 CA 로는 검증에 실패한다.
            #   --tls-ca <파일>  백엔드팀 CA 인증서로 정상 검증 (권장)
            #   --tls-insecure   검증 생략 (개발용. 도청·위장에 취약)
            if ca:
                self.cli.tls_set(ca_certs=ca)
                self.get_logger().info(f"MQTT TLS (CA: {ca})")
            else:
                import ssl
                self.cli.tls_set(cert_reqs=ssl.CERT_NONE)
                self.get_logger().info("MQTT TLS (인증서 검증 생략 — 개발용)")
            if insecure:
                self.cli.tls_insecure_set(True)
        self.cli.on_connect = self._on_connect
        self.cli.on_message = self._on_mqtt
        try:
            self.cli.connect(host, port, keepalive=30)
        except Exception as e:
            raise SystemExit(
                f"MQTT 브로커 연결 실패 ({host}:{port}): {e}\n"
                f"  - 브로커가 떠 있는지:  ss -tln | grep {port}\n"
                f"  - 로컬에 없으면:      sudo apt install -y mosquitto\n"
                f"  - 다른 서버면:        --host 주소 --port 포트 "
                f"[--user 아이디 --pass 비번]")
        self.cli.loop_start()
        self.get_logger().info(f"MQTT 연결: {host}:{port}")

        for v in VEHICLES:
            self.publish_hold(v)        # 초기값 "" 을 래치

        self.create_timer(1.0 / TELEMETRY_HZ, self.publish_telemetry)
        self.create_timer(0.05, self.hold_stopped)
        self.create_timer(0.1, self.process_inbox)
        self.create_timer(0.2, self.run_missions)
        self.get_logger().info(
            "브릿지 시작 — " + ", ".join(f"{v['ns']}→{v['id']}" for v in VEHICLES))

    # ── MQTT (별도 스레드) ──────────────────────────────────────────
    def _on_connect(self, client, userdata, flags, rc):
        for v in VEHICLES:
            for kind in ("task", "control", "cargo"):
                client.subscribe(f"{BASE}/{v['id']}/{kind}", qos=1)
        client.subscribe(ALL_CONTROL, qos=1)
        client.subscribe(SIM_CAMERA, qos=1)
        for rid in REAL_VEHICLES:
            client.subscribe(f"{BASE}/{rid}/telemetry", qos=0)
            self.get_logger().info(f"구독: {BASE}/{rid}/telemetry (실물 미러)")
        for rid in REAL_CARGO:
            client.subscribe(f"{BASE}/{rid}/cargo", qos=1)
            self.get_logger().info(f"구독: {BASE}/{rid}/cargo (실물 화물 반영)")
        self.get_logger().info(
            "구독: task, control, cargo, control/all, sim/camera")

    def _on_mqtt(self, client, userdata, msg):
        try:
            payload = json.loads(msg.payload.decode())
        except Exception:
            payload = {}
        with self.inbox_lock:
            self.inbox.append((msg.topic, payload))

    # ── 수신 처리 (ROS 스레드) ──────────────────────────────────────
    def process_inbox(self):
        with self.inbox_lock:
            items, self.inbox = self.inbox, []
        for topic, payload in items:
            if not any(topic == f"{BASE}/{r}/telemetry" for r in REAL_VEHICLES):
                self.get_logger().info(f"[수신] {topic} {payload}")
            if topic == ALL_CONTROL:
                for v in VEHICLES:
                    self.apply_control(v, payload.get("command", ""))
                continue
            # 실물 telemetry -> 시뮬 미러
            hit = False
            for rid, ros_topic in REAL_VEHICLES.items():
                if topic == f"{BASE}/{rid}/telemetry":
                    pose = payload.get("pose")
                    if pose:
                        self.real_pubs[rid].publish(String(
                            data=json.dumps({"pose": pose})))
                    hit = True
            if hit:
                continue
            # 실물 화물 지시 -> 시뮬의 미러 차량에 반영
            for rid, ros_topic in REAL_CARGO.items():
                if topic == f"{BASE}/{rid}/cargo":
                    self.real_cargo_pubs[rid].publish(
                        String(data=json.dumps(payload, ensure_ascii=False)))
                    self.get_logger().info(f"실물 화물 반영: {rid} {payload}")
                    hit = True
            if hit:
                continue

            if topic == SIM_CAMERA:
                self.camera_pub.publish(
                    String(data=json.dumps(payload, ensure_ascii=False)))
                self.get_logger().info(f"시뮬 카메라 전환 지시: {payload}")
                continue
            for v in VEHICLES:
                if topic == f"{BASE}/{v['id']}/control":
                    self.apply_control(v, payload.get("command", ""))
                elif topic == f"{BASE}/{v['id']}/task":
                    self.accept_task(v, payload)
                elif topic == f"{BASE}/{v['id']}/cargo":
                    self.spawn_cargo(v, payload)

    def relay_to_mqtt(self, vehicle_id, kind, msg):
        """관제(ROS) -> 실물(MQTT) 명령 중계."""
        topic = f"{BASE}/{vehicle_id}/{kind}"
        self.cli.publish(topic, msg.data, qos=1)
        self.get_logger().info(f"[중계] {topic} {msg.data}")

    def spawn_cargo(self, v, p):
        """측정된 화물 높이를 Isaac 으로 넘겨 포크 위에 파레트+박스를 만든다.

        기대 payload: {"height": 0.15, "cargoId": "C-0007"}   (height 는 실물 m)
        """
        h = p.get("height", p.get("heightM"))
        if h is None:
            self.get_logger().warn(f"{v['id']} cargo 에 height 없음: {p}")
            return
        body = {"height": float(h), "cargoId": p.get("cargoId")}
        v["cargo_pub"].publish(String(data=json.dumps(body)))
        self.get_logger().info(
            f"{v['id']} 화물 적재 지시 — 높이 {h} m (실물 기준)")
        self.send_event(v, "CARGO_LOADED", "INFO",
                        f"측정 높이 {h} m 로 화물 생성 요청")

    # ── 제어 ────────────────────────────────────────────────────────
    def publish_hold(self, v):
        """정지 여부를 래치 발행한다. 늦게 뜬 데모도 즉시 알 수 있다."""
        try:
            v["hold_pub"].publish(String(data=v["stop_kind"]))
        except Exception:
            pass

    def apply_control(self, v, command):
        cmd = str(command).upper()
        if cmd in ("ESTOP", "HOLD"):
            v["stopped"] = True
            v["stop_kind"] = cmd
            v["mission"] = []
            v["busy"] = False
            self.cancel_nav(v)
            self.publish_hold(v)
            self.get_logger().warn(f"{v['id']} {cmd} — 정지")
            self.send_event(v,
                            "ESTOP_TRIGGERED" if cmd == "ESTOP" else "HOLD",
                            "ERROR" if cmd == "ESTOP" else "WARN",
                            f"{cmd} 수신, 차량 정지")
        elif cmd == "RESUME":
            if v["stopped"]:
                self.send_event(v, "RESUMED", "INFO", "RESUME 수신")
                self.get_logger().info(f"{v['id']} RESUME — 정지 해제")
            v["stopped"] = False
            v["stop_kind"] = ""
        elif cmd == "CANCEL_TASK":
            v["mission"] = []
            v["busy"] = False
            v["task_id"] = None
            self.cancel_nav(v)
            self.get_logger().info(f"{v['id']} CANCEL_TASK — 목표 취소")
            self.send_event(v, "TASK_FAILED", "WARN", "CANCEL_TASK 수신")
        else:
            self.get_logger().warn(f"{v['id']} 알 수 없는 command: {command}")

    def cancel_nav(self, v):
        """Nav2 목표 취소. 빈 CancelGoal 요청은 '전체 취소' 로 해석된다."""
        if v["cancel_cli"].service_is_ready():
            v["cancel_cli"].call_async(CancelGoal.Request())
        else:
            self.get_logger().warn(f"{v['id']} 취소 서비스 없음 (Nav2 미기동?)")

    # ── 작업(task) ──────────────────────────────────────────────────
    def accept_task(self, v, p):
        if v["stopped"]:
            self.get_logger().warn(f"{v['id']} {v['stop_kind']} 중 — task 무시")
            return

        # 랙 적재/반출은 미션이 아니라 화물 처리기로 넘긴다.
        # 백엔드가 시뮬·실물에 **같은 페이로드**를 쓸 수 있게 하려는 것이다.
        #   실물은 approach/dock/shelfHeight 좌표를 보고 스스로 도킹하고,
        #   시뮬은 rack 이름만 보고 내장 좌표표로 처리한다.
        # 그래서 백엔드는 둘을 한 메시지에 같이 담아 보내면 된다.
        act = str(p.get("action", "")).upper()
        if act in ("PLACE_RACK", "PICK_RACK"):
            rack = p.get("rack")
            if not rack:
                self.get_logger().warn(
                    f"{v['id']} {act} 에 rack 이름이 없음 — 시뮬은 이름이 필요합니다. "
                    f"백엔드는 좌표와 함께 \"rack\":\"A1\" 도 넣어 주세요")
                return
            v["task_id"] = p.get("taskId")
            body = {"action": "place_rack" if act == "PLACE_RACK" else "pick_rack",
                    "rack": rack}
            v["cargo_pub"].publish(String(data=json.dumps(body)))
            self.get_logger().info(f"{v['id']} {act} {rack} (task {v['task_id']})")
            return

        mission = []
        if "pickup" in p or "dropoff" in p:
            for key in ("pickup", "dropoff"):
                g = p.get(key)
                if not g:
                    continue
                mission.append(("goto", g.get("x"), g.get("y"), g.get("yaw", 0.0),
                                key))
                if g.get("forkHeight") is not None:
                    mission.append(("fork", float(g["forkHeight"])))
        elif "x" in p and "y" in p:          # 간단 테스트: 좌표만
            mission.append(("goto", p["x"], p["y"], p.get("yaw", 0.0), "goal"))
        else:
            self.get_logger().warn(f"{v['id']} task 에 좌표가 없음: {p}")
            return

        v["task_id"] = p.get("taskId")
        v["mission"] = mission
        v["busy"] = False
        self.get_logger().info(
            f"{v['id']} task 수락 ({v['task_id']}) — 단계 {len(mission)}개")

    def run_missions(self):
        for v in VEHICLES:
            if v["stopped"] or v["busy"] or not v["mission"]:
                continue
            if time.time() < v["wait_until"]:
                continue
            step = v["mission"][0]
            if step[0] == "goto":
                _, x, y, yaw, tag = step
                if not v["nav_ac"].wait_for_server(timeout_sec=1.0):
                    self.get_logger().warn(f"{v['id']} Nav2 액션 서버 없음")
                    return
                v["busy"] = True
                v["mission"].pop(0)
                self.get_logger().info(f"{v['id']} → ({x}, {y}) [{tag}]")
                goal = NavigateToPose.Goal()
                goal.pose = make_pose(x, y, yaw)
                fut = v["nav_ac"].send_goal_async(goal)
                fut.add_done_callback(
                    lambda f, veh=v, t=tag: self._on_goal_response(f, veh, t))
            elif step[0] == "fork":
                v["mission"].pop(0)
                v["fork_pub"].publish(Float32(data=float(step[1])))
                v["wait_until"] = time.time() + FORK_WAIT
                self.get_logger().info(f"{v['id']} 포크 {step[1]} m")

    def _on_goal_response(self, fut, v, tag):
        gh = fut.result()
        if gh is None or not gh.accepted:
            self.get_logger().warn(f"{v['id']} 목표 거부됨 [{tag}]")
            v["busy"] = False
            v["mission"] = []
            self.send_event(v, "TASK_FAILED", "ERROR", f"{tag} 목표 거부")
            return
        gh.get_result_async().add_done_callback(
            lambda f, veh=v, t=tag: self._on_goal_result(f, veh, t))

    def _on_goal_result(self, fut, v, tag):
        status = fut.result().status
        v["busy"] = False
        if status == 4:
            self.get_logger().info(f"{v['id']} 도착 [{tag}]")
            if not v["mission"]:
                self.send_event(v, "TASK_COMPLETED", "INFO",
                                f"작업 완료 ({v['task_id']})")
                v["task_id"] = None
        else:
            self.get_logger().warn(f"{v['id']} 주행 실패 [{tag}] status={status}")
            v["mission"] = []
            self.send_event(v, "TASK_FAILED", "ERROR",
                            f"{tag} 주행 실패 (status={status})")

    # ── 상태 발행 ───────────────────────────────────────────────────
    def hold_stopped(self):
        zero = Twist()
        for v in VEHICLES:
            if v["stopped"]:
                v["cmd_pub"].publish(zero)

    def pose_from_tf(self, frame):
        try:
            t = self.buf.lookup_transform("map", f"{frame}_base_link",
                                          rclpy.time.Time())
        except Exception:
            return None
        q = t.transform.rotation
        yaw = math.atan2(2 * (q.w * q.z + q.x * q.y),
                         1 - 2 * (q.y * q.y + q.z * q.z))
        return {"x": round(t.transform.translation.x, 3),
                "y": round(t.transform.translation.y, 3),
                "yaw": round(yaw, 4)}

    def publish_telemetry(self):
        for v in VEHICLES:
            body = {}
            if v["state"]:
                try:
                    body = json.loads(v["state"])
                except Exception:
                    body = {}
            if "pose" not in body:
                p = self.pose_from_tf(v["frame"])
                if p is None:
                    continue
                body["pose"] = p

            state = ("ESTOPPED" if v["stop_kind"] == "ESTOP"
                     else "HOLDING" if v["stop_kind"] == "HOLD"
                     else body.get("state", "IDLE"))
            pose = body.get("pose")
            if pose and POSE_SCALE != 1.0:
                pose = {"x": round(pose["x"] / POSE_SCALE, 3),
                        "y": round(pose["y"] / POSE_SCALE, 3),
                        "yaw": pose.get("yaw", 0.0)}   # 각도는 눈금과 무관
            msg = {
                "vehicleId": v["id"],
                "ts": now_ms(),
                "pose": pose,
                "velocity": body.get("velocity", {"linear": 0.0, "angular": 0.0}),
                "forkHeight": body.get("forkHeight", 0.0),
                "loaded": body.get("loaded", False),
                "cargoId": body.get("cargoId"),
                # 싣고 있는 화물의 크기. 없으면 null.
                # 좌표와 같은 눈금을 쓴다(POSE_SCALE).
                "cargo": _scale_cargo(body.get("cargo")),
                "state": state,
                "taskId": v["task_id"],
                "battery": 100.0,
            }
            self.cli.publish(f"{BASE}/{v['id']}/telemetry",
                             json.dumps(msg, ensure_ascii=False), qos=0)

            self.check_arrived(v, msg)

            if state != v["last_event"]:
                if v["last_event"] is not None:
                    self.send_event(v, "STATE_CHANGED", "INFO",
                                    f"{v['last_event']} -> {state}"
                                    + (" (적재)" if msg["loaded"] else ""))
                v["last_event"] = state

    def check_arrived(self, v, msg):
        """입고 바이에 도착해 멈추면 arrived 를 한 번 발행한다.

        AI(비전)가 이걸 받아 용적 측정을 시작한다 (docs/mqtt-arrived.md).
        좌표는 시뮬 좌표 그대로 내보낸다 (SCALE=1.0).
        """
        p = msg.get("pose")
        if not p:
            return
        d = math.hypot(p["x"] - BAY_XY[0], p["y"] - BAY_XY[1])
        # "멈췄나" 는 상태 이름이 아니라 실제 속도로 본다.
        # 상태 이름으로만 보면 새 상태가 생길 때마다 여기가 조용히 깨진다
        # (LOADING 을 추가했을 때 실제로 그랬다 — arrived 가 안 나가 AI 가
        #  측정을 시작하지 못했다).
        v_lin = abs((msg.get("velocity") or {}).get("linear", 0.0))
        stopped = (v_lin < 0.05
                   or msg["state"] in ("IDLE", "ESTOPPED", "HOLDING", "LOADING"))

        if d <= BAY_RADIUS and stopped:
            if v["arrived_sent"]:
                return
            v["arrived_sent"] = True
            body = {
                "vehicleId": v["id"],
                "event": "ARRIVED",
                "location": "INBOUND",
                "position": {"x": round(p["x"] / SCALE, 3),
                             "y": round(p["y"] / SCALE, 3),
                             "frameId": "map"},
                "heading": p["yaw"],
                "messageAt": time.strftime("%Y-%m-%dT%H:%M:%S+09:00",
                                           time.localtime()),
            }
            self.cli.publish(f"forklift/{v['id']}/arrived",
                             json.dumps(body, ensure_ascii=False), qos=1)
            self.get_logger().info(f"{v['id']} 입고 바이 도착 — arrived 발행")
        elif d > BAY_RADIUS:
            v["arrived_sent"] = False       # 바이를 벗어나면 다시 발행 가능

    def send_event(self, v, etype, severity, detail):
        ev = {"vehicleId": v["id"], "ts": now_ms(), "type": etype,
              "severity": severity, "taskId": v["task_id"], "detail": detail}
        self.cli.publish(f"{BASE}/{v['id']}/event",
                         json.dumps(ev, ensure_ascii=False), qos=1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=os.environ.get("MQTT_HOST", "localhost"))
    ap.add_argument("--port", type=int,
                    default=int(os.environ.get("MQTT_PORT", 1883)))
    ap.add_argument("--user", default=os.environ.get("MQTT_USER"),
                    help="브로커 아이디 (인증이 걸린 경우)")
    ap.add_argument("--pass", dest="password",
                    default=os.environ.get("MQTT_PASS"),
                    help="브로커 비밀번호")
    ap.add_argument("--tls", action="store_true",
                    default=bool(os.environ.get("MQTT_TLS")),
                    help="TLS 사용 (보통 포트 8883)")
    ap.add_argument("--tls-ca", dest="ca", default=os.environ.get("MQTT_CA"),
                    help="브로커 CA 인증서 파일 (자체 서명 CA 검증용)")
    ap.add_argument("--tls-insecure", dest="insecure", action="store_true",
                    default=bool(os.environ.get("MQTT_INSECURE")),
                    help="인증서 검증 생략 (개발용)")
    a = ap.parse_args()

    rclpy.init()
    node = MqttBridge(a.host, a.port, a.user, a.password, a.tls,
                      a.ca, a.insecure)
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.cli.loop_stop()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
