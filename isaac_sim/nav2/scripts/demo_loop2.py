#!/usr/bin/env python3
"""무제한 순환 데모 — 두 대가 계속 받고(적재) 넣는다(랙 적재).

각 차량은 이 주기를 끝없이 반복한다.

    바이 예약 → 바이에서 적재 → 바이 탈출(예약 해제) → 랙 접근 → 선반 적재 → 다시 바이

바이는 한 번에 한 대만 쓴다(예약). 랙은 차량마다 여러 곳을 번갈아 쓴다.
이동은 항상 일방통행 순환로를 따르고, 진행 내내 교통 규칙이 살아 있다.

    규칙 1  한 방향(기본 반시계)으로만 순환한다.
    규칙 2  앞차가 '작업 중'/'대기 중' 이면 HOLD_DIST 안에서 멈춘다.
    규칙 3  바이는 한 대만 — 남이 쓰는 중이면 멈추지 않고 순환로를
            계속 돈다(대기 선회). 차선에 서면 뒤차가 막혀 교착이 난다.
    규칙 4  시작할 때 한 대씩 순차 합류한다. 전원이 동시에 출발하면
            간격 없이 몰려 바로 서로를 막는다.
    규칙 5  정체 감시 — 20초간 진전이 없으면 목표를 다시 보낸다.

랙 적재는 두 단계다. Nav2 로 approach 까지 가고, 거기서부터는 차량 스텝
머신이 도킹·적재·후진을 한다. 포크를 랙 안에 넣는 자리는 3.8 m footprint 가
랙과 겹쳐 Nav2 가 경로를 못 만들기 때문이다.

종료: Ctrl+C

사용:
    source /opt/ros/humble/setup.bash
    python3 demo_loop2.py              # 반시계, 무제한
    python3 demo_loop2.py cw           # 시계
    python3 demo_loop2.py ccw 5        # 5주기만 돌고 종료
    python3 demo_loop2.py --with-real  # 실물(오린카)도 규칙에 포함
"""
import os
import sys
import math
import json
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import rclpy
from rclpy.qos import QoSProfile, QoSDurabilityPolicy
from rclpy.node import Node
from rclpy.action import ActionClient
from action_msgs.srv import CancelGoal
from geometry_msgs.msg import PoseStamped
from nav2_msgs.action import NavigateToPose
from std_msgs.msg import String
from tf2_ros import Buffer, TransformListener

from track import Track, build_corners

# ── 스테이션 (맵에서 3.8x1.9 차체가 설 수 있는지 확인한 좌표) ────────
STATIONS = {
    "BAY":  (17.0, 5.0, 0.0),            # 입고 바이 (공용, 한 대만)
    "EXIT": (15.5, 4.0, math.pi / 2),    # 바이 탈출점 (막다른 곳에서 나옴)
}

# 랙 접근점 — cargo_demo.RACK_SLOTS 와 같은 격자에서 생성한다.
#   A1~A12  왼쪽 벽 랙  → 왼쪽 통로(x 5.0)에서 진입
#   B1~B12  가운데 랙   → 오른쪽 통로(x 14.5)에서 진입
_SLOT_Y = [9.3, 10.5, 11.8, 13.2, 14.5, 15.8,
           17.2, 18.5, 19.7, 21.2, 22.5, 23.8]
for _i, _y in enumerate(_SLOT_Y, 1):
    STATIONS[f"A{_i}"] = (5.0, _y, math.pi)
    STATIONS[f"B{_i}"] = (14.5, _y, math.pi)

# 차량별로 번갈아 쓸 랙
RACKS = {
    "SIM_F02": [f"A{i}" for i in range(1, 13)],   # 왼쪽 벽 랙 12칸
    "SIM_F03": [f"B{i}" for i in range(1, 13)],   # 가운데 랙 12칸
}

# 실물 지게차(오린카)를 교통 규칙에 포함시킬지.
#   --with-real 로 켠다. 켜면 실물도 시뮬과 똑같은 규칙을 받는다.
#
#   전달 방식만 다르다:
#     시뮬  관제 → ROS2 navigate_to_pose 액션 → Nav2
#     실물  관제 → ROS /out/fk01/{task,control} → mqtt_bridge → MQTT → 오린카
#
#   실물이 그 명령대로 움직이려면 C 팀이 MQTT 수신을 구현해야 한다.
#   구현 전이라도 켜 두면 시뮬 차량들이 실물을 보고 피하고 멈춘다.
REAL_ID = "REAL_F01"          # 씬/TF 이름
REAL_MQTT_ID = "fk01"         # MQTT vehicleId
REAL_RACKS = [f"B{i}" for i in range(1, 13)]
REAL_SCALE = 1.0              # MQTT 는 시뮬 좌표계. 실물이 자기 쪽에서 ÷10 한다

CARGO_HEIGHT = 0.15      # 실물 기준 m
LOAD_SEC = 6.0           # 적재에 두는 시간
LOAD_WAIT_MAX = 40.0     # 적재 확인을 기다리는 최대 시간 (재시도 포함)
ALIGN_SEC = 7.0          # 바이에서 정면으로 맞추는 시간 (각도가 맞아야 화물을 받는다)
RACK_SEC = 8.0           # 랙 적재 명령 후 최소 대기 (스텝이 시작되기까지)
RACK_MAX = 90.0          # 랙 적재를 기다리는 최대 시간 (스텝 총합 73초 + 여유)

HOLD_DIST = 9.0          # 앞차가 작업/대기 중이면 멈추는 거리
SAFE_DIST = 6.0          # 그냥 앞차가 가까울 때 멈추는 거리
OFF_LOOP_TOL = 4.0       # 순환로에서 이만큼 벗어난 차량은 교통 판단서 제외
ARRIVE_TOL = 1.5
CORNER_TOL = 1.5
APPROACH_TRIGGER = 4.0   # 스테이션 진입점에 이만큼 남으면 빠져나간다
# 시작 합류 — 전원이 동시에 출발하면 간격 없이 몰려 바로 막힌다.
# 한 대씩 순환로에 올린다.
ENTRY_HEADWAY = 10.0     # 진행 방향 앞에 '움직이는' 차가 이 안이면 합류 보류
ENTRY_INTERVAL = 3.0     # 합류 허가 사이 최소 간격(초)
ENTRY_OFF_WARN = 4.0     # 순환로에서 이만큼 벗어나 있으면 경고

STALL_SEC = 20.0         # 이 시간 동안 안 움직이면 목표를 다시 보낸다
STALL_MOVE = 0.3         # 이만큼 이상 움직이면 진행 중으로 본다
TICK = 0.5


def make_pose(x, y, yaw):
    p = PoseStamped()
    p.header.frame_id = "map"
    p.pose.position.x = float(x)
    p.pose.position.y = float(y)
    p.pose.orientation.z = math.sin(float(yaw) / 2.0)
    p.pose.orientation.w = math.cos(float(yaw) / 2.0)
    return p


class LoopDemo(Node):
    def __init__(self, direction, max_cycles, with_real=False):
        super().__init__("demo_loop2")
        # use_sim_time 을 쓰지 않는다. Isaac 이 잠깐 버벅여도 관제가 같이
        # 멈추지 않게 하기 위해서다. TF 는 '최신값' 으로 조회한다.
        self.track = Track(build_corners(direction))
        self.buf = Buffer()
        TransformListener(self.buf, self)
        self.entry_s = {n: self.track.project(p[:2])[0]
                        for n, p in STATIONS.items()}

        self.max_cycles = max_cycles
        self.release_cooldown = 0.0      # 합류 허가 간격
        self.bay_owner = None            # 바이를 쓰고 있는 차량 (한 대만)
        self.done = False
        self.t0 = time.time()

        self.v = {vid: self.mk(vid.lower(), vid) for vid in RACKS}
        if with_real:
            self.v[REAL_ID] = self.mk(REAL_ID.lower(), REAL_ID, transport="mqtt")
            RACKS[REAL_ID] = REAL_RACKS
            self.get_logger().info(
                f"{REAL_ID} 실물 포함 — 명령은 MQTT({REAL_MQTT_ID})로 나간다")

        name = "시계방향" if str(direction).upper() == "CW" else "반시계방향"
        lim = "무제한" if max_cycles <= 0 else f"{max_cycles}주기"
        self.get_logger().info(
            f"무제한 순환 데모 시작 ({name}, 둘레 {self.track.length:.1f} m, {lim})")
        for vid, racks in RACKS.items():
            self.get_logger().info(f"  {vid}: 바이 → {' / '.join(racks)} 번갈아")
        self.create_timer(TICK, self.tick)

    def mk(self, ns, frame, transport="ros"):
        """차량 하나의 상태. transport 만 다르고 판단 로직은 완전히 같다."""
        d = {
            "ns": ns, "frame": frame, "transport": transport,
            "cargo_pub": self.create_publisher(String, f"/{ns}/cargo_cmd", 10),
            "loaded": False,       # 실제로 화물을 들고 있는가 (/state 로 확인)
            "mission": False,      # 차량 스텝 머신이 돌고 있는가
            "state_seen": False,   # /state 를 한 번이라도 받았는가
            "ext_stop": "",        # 관제(MQTT)가 세운 상태 ESTOP/HOLD
            "ext_noted": False,    # 정지 로그를 한 번만 남기려고
            "step": "",            # 지금 수행 중인 스텝
            "joined": False,       # 순환로 합류 허가를 받았는가 (시작 규칙)
            "phase": "to_bay",     # to_bay|load|to_exit|to_rack|rack
            "target": None,        # 지금 향하는 스테이션 이름
            "rack_i": 0,           # 다음에 쓸 랙 번호
            "cycles": 0,           # 완료한 주기 수
            "idx": None,           # 목표 모서리
            "mode": "",            # 마지막 목표(중복 전송 방지)
            "held": False,
            "work_until": 0.0,
            "load_deadline": 0.0,
            "rack_deadline": 0.0,
            "approaching": False,
            # 정체 감시 — Nav2 목표가 조용히 실패하면 그대로 서 있게 되므로,
            # 일정 시간 진전이 없으면 목표를 다시 보낸다.
            "last_pos": None,
            "last_move_t": time.time(),
            "resends": 0,
        }
        if transport == "ros":
            d["ac"] = ActionClient(self, NavigateToPose,
                                   f"/{ns}/navigate_to_pose")
            d["cancel"] = self.create_client(
                CancelGoal, f"/{ns}/navigate_to_pose/_action/cancel_goal")
        else:                                   # MQTT (실물)
            d["task_pub"] = self.create_publisher(
                String, f"/out/{REAL_MQTT_ID}/task", 10)
            d["ctrl_pub"] = self.create_publisher(
                String, f"/out/{REAL_MQTT_ID}/control", 10)
        # 차량이 실제로 화물을 들었는지 본다. 이게 없으면 적재에 실패해도
        # 빈 포크로 랙까지 갔다가 아무것도 못 놓고 계속 순환로를 돈다.
        def _on_state(msg, dd=d):
            try:
                j = json.loads(msg.data)
            except Exception:
                return
            dd["state_seen"] = True
            dd["loaded"] = bool(j.get("loaded"))
            dd["mission"] = bool(j.get("mission"))
            dd["step"] = j.get("step", "")
        self.create_subscription(String, f"/{ns}/state", _on_state, 10)

        # 관제(MQTT)가 이 차를 멈춰 세웠는지. 래치 발행이라 늦게 떠도 받는다.
        # 이걸 안 보면 정체 감시가 "20초째 안 움직인다"며 목표를 다시 보내
        # 정지 명령을 무효로 만든다.
        self.create_subscription(
            String, f"/{ns}/control_state",
            lambda msg, dd=d: dd.__setitem__("ext_stop", msg.data.strip()),
            QoSProfile(depth=1,
                       durability=QoSDurabilityPolicy.TRANSIENT_LOCAL))

        return d

    # ── 조회 ────────────────────────────────────────────────────────
    def pos(self, frame):
        try:
            t = self.buf.lookup_transform("map", f"{frame}_base_link",
                                          rclpy.time.Time())
            return (t.transform.translation.x, t.transform.translation.y)
        except Exception:
            return None

    def stopped_kind(self, v):
        if v["ext_stop"]:
            return "관제정지"
        if v["phase"] in ("align_bay", "load", "rack"):
            return "작업중"
        if v["held"]:
            return "대기중"
        return None

    # ── 지시 ────────────────────────────────────────────────────────
    def goto(self, v, x, y, yaw, mode, note):
        if v["mode"] == mode and not v["held"]:
            return
        if v["transport"] == "ros":
            if not v["ac"].wait_for_server(timeout_sec=1.0):
                return
            g = NavigateToPose.Goal()
            g.pose = make_pose(x, y, yaw)
            v["ac"].send_goal_async(g)
        else:
            # 실물에는 실물 축척(1/10)으로 보낸다
            v["task_pub"].publish(String(data=json.dumps({
                "taskId": f"L-{int(time.time() * 10) % 100000}",
                "ts": int(time.time() * 1000),
                "pickup": {"x": round(x / REAL_SCALE, 3),
                           "y": round(y / REAL_SCALE, 3),
                           "yaw": round(yaw, 4)},
            })))
        if v["held"] and v["transport"] == "mqtt":
            # 실물은 HOLD 중 새 task 를 무시한다. 먼저 풀어 준다.
            v["ctrl_pub"].publish(String(data=json.dumps(
                {"ts": int(time.time() * 1000), "command": "RESUME"})))
        v["mode"] = mode
        v["held"] = False
        self.get_logger().info(f"{v['frame']} → ({x:.1f}, {y:.1f}) {note}")

    def halt(self, v, note):
        if v["transport"] == "ros":
            if v["cancel"].service_is_ready():
                v["cancel"].call_async(CancelGoal.Request())
        else:
            v["ctrl_pub"].publish(String(data=json.dumps(
                {"ts": int(time.time() * 1000), "command": "HOLD"})))
        if not v["held"]:
            self.get_logger().info(f"{v['frame']} 정지 — {note}")
        v["held"] = True
        v["mode"] = ""

    # ── 규칙 2 ──────────────────────────────────────────────────────
    def blocker(self, me, s_me):
        best = None
        for other in self.v.values():
            if other is me:
                continue
            op = self.pos(other["frame"])
            if op is None:
                continue
            s_o, off = self.track.project(op)
            if off > OFF_LOOP_TOL:
                continue
            g = self.track.gap(s_me, s_o)
            if best is None or g < best[0]:
                best = (g, other)
        if best is None:
            return None
        gap, other = best
        kind = self.stopped_kind(other)
        if kind and gap < HOLD_DIST:
            return gap, f"{other['frame']} 이 {kind} ({gap:.1f} m 앞)"
        if gap < SAFE_DIST:
            return gap, f"{other['frame']} 이 {gap:.1f} m 앞"
        return None

    # ── 시작 합류 — 한 번에 한 대씩만 순환로에 올린다 ──────────────
    def release_one(self):
        self.release_cooldown = max(0.0, self.release_cooldown - TICK)
        if self.release_cooldown > 0:
            return
        for vid, v in self.v.items():
            if v["joined"]:
                continue
            p = self.pos(v["frame"])
            if p is None:
                continue
            s_me, off = self.track.project(p)

            # 이미 합류해 움직이는 차가 앞에 가까이 있으면 보류
            gap = None
            for other in self.v.values():
                if other is v or not other["joined"]:
                    continue
                op = self.pos(other["frame"])
                if op is None:
                    continue
                s_o, o_off = self.track.project(op)
                if o_off > OFF_LOOP_TOL:
                    continue
                g = self.track.gap(s_me, s_o)
                if gap is None or g < gap:
                    gap = g
            if gap is not None and gap < ENTRY_HEADWAY:
                self.get_logger().info(
                    f"{v['frame']} 합류 대기 — 앞차가 {gap:.1f} m")
                return                       # 이번 주기엔 아무도 더 안 올린다

            v["joined"] = True
            self.release_cooldown = ENTRY_INTERVAL
            note = ""
            if off > ENTRY_OFF_WARN:
                note = f" (순환로에서 {off:.1f} m 벗어남 — 먼저 복귀한다)"
            self.get_logger().info(f"{v['frame']} 합류 허가{note}")
            return                           # 한 주기에 한 대만

    # ── 주기 진행 ───────────────────────────────────────────────────
    def set_phase(self, v, phase, target=None):
        v["phase"] = phase
        v["target"] = target
        v["mode"] = ""
        v["idx"] = None
        v["approaching"] = False

    def tick(self):
        if self.done:
            return

        self.release_one()

        for vid, v in self.v.items():
            if self.max_cycles > 0 and v["cycles"] >= self.max_cycles:
                continue
            if not v["joined"]:              # 아직 합류 허가 전 — 출발하지 않는다
                continue

            # 관제가 세운 차량 — 목표를 다시 보내지 않는다.
            # 정체 감시 시각도 계속 밀어 둔다. 안 그러면 해제 직후 곧바로
            # "정체" 로 오판한다.
            if v["ext_stop"]:
                if not v["ext_noted"]:
                    v["ext_noted"] = True
                    self.get_logger().warn(
                        f"{v['frame']} 관제 {v['ext_stop']} — 대기 "
                        f"(RESUME 까지 목표를 보내지 않음)")
                    self.halt(v, f"관제 {v['ext_stop']}")
                v["mode"] = ""               # 해제되면 목표를 새로 보내게
                v["last_move_t"] = time.time()
                continue
            if v["ext_noted"]:
                v["ext_noted"] = False
                self.get_logger().info(f"{v['frame']} 관제 해제 — 재개")

            p = self.pos(v["frame"])
            if p is None:
                continue
            s_me, _ = self.track.project(p)

            # 작업 중 — 제자리. 뒤차는 규칙 2로 알아서 멈춘다.
            if v["phase"] in ("align_bay", "load", "rack"):
                if time.time() < v["work_until"]:
                    continue
                if v["phase"] == "align_bay":
                    if v["state_seen"] and v["mission"]:   # 정렬 스텝 진행 중
                        continue
                    # 정면으로 맞춰졌으니 이제 화물을 받는다.
                    v["cargo_pub"].publish(
                        String(data=json.dumps({"height": CARGO_HEIGHT})))
                    v["phase"] = "load"
                    v["work_until"] = time.time() + LOAD_SEC
                    v["load_deadline"] = time.time() + LOAD_WAIT_MAX
                    self.get_logger().info(
                        f"{v['frame']} 적재 (높이 {CARGO_HEIGHT} m) — {LOAD_SEC:.0f}초")
                elif v["phase"] == "load":
                    if v["state_seen"] and not v["loaded"] and \
                            time.time() < v["load_deadline"]:
                        continue          # 아직 못 받았다 — 바이에서 더 기다린다
                    if not v["state_seen"]:
                        self.get_logger().warn(
                            f"{v['frame']} /{v['ns']}/state 없음 — 적재 확인 생략."
                            f" Isaac 에서 kinematic_vehicle 이 떠 있는지 보세요")
                    elif not v["loaded"]:
                        self.get_logger().warn(
                            f"{v['frame']} 적재 확인 실패 — Isaac 콘솔의 "
                            f"[cargo_cmd] 줄을 확인하세요")
                    self.set_phase(v, "to_exit", "EXIT")
                else:
                    # 랙 적재는 스텝 머신이 끝나야 완료다. 시간으로만 넘어가면
                    # 아직 place 를 못 한 차가 바이로 가버려 화물을 계속 든 채
                    # 빙글빙글 돈다.
                    if v["state_seen"] and (v["mission"] or v["loaded"]) and \
                            time.time() < v["rack_deadline"]:
                        continue
                    if v["loaded"]:
                        self.get_logger().warn(
                            f"{v['frame']} 랙 적재 실패 — 화물을 든 채로 진행")
                    v["cycles"] += 1
                    self.get_logger().info(
                        f"{v['frame']} {v['cycles']}주기 완료")
                    self.set_phase(v, "to_bay", "BAY")
                continue

            # 규칙 2 — 이동 중에는 항상 적용
            blk = self.blocker(v, s_me)
            if blk:
                self.halt(v, blk[1])
                v["last_move_t"] = time.time()      # 의도적 정지는 정체가 아니다
                v["last_pos"] = p
                continue

            # 정체 감시 — Nav2 가 목표를 조용히 실패하면 아무 일도 안 일어난다.
            # 진전이 없으면 목표를 다시 보내 스스로 회복한다.
            if v["last_pos"] is None or \
                    math.dist(p, v["last_pos"]) > STALL_MOVE:
                v["last_pos"] = p
                v["last_move_t"] = time.time()
            elif time.time() - v["last_move_t"] > STALL_SEC:
                v["resends"] += 1
                self.get_logger().warn(
                    f"{v['frame']} {STALL_SEC:.0f}초간 정체 — 목표 재전송 "
                    f"({v['resends']}회째)")
                v["mode"] = ""                       # 같은 목표를 다시 보내게
                v["last_move_t"] = time.time()
                if v["resends"] % 3 == 0:            # 반복되면 경로도 새로 잡는다
                    v["idx"] = None
                    v["approaching"] = False

            name = v["target"] or "BAY"
            st = STATIONS[name]

            # 바이 탈출은 순환로를 타지 않는다.
            # EXIT 는 바이 바로 뒤(약 1.8 m)라 일방통행 순환로로 가면 66 m 를
            # 한 바퀴 돌게 된다. 짧은 후진·정렬이므로 Nav2 에 바로 목표를 준다.
            if v["phase"] == "to_exit":
                if math.hypot(p[0] - st[0], p[1] - st[1]) < ARRIVE_TOL:
                    self.arrived(v, vid, name)
                else:
                    self.goto(v, st[0], st[1], st[2], "st_EXIT", "바이 탈출")
                continue

            # 스테이션 접근 중
            if v["approaching"]:
                if math.hypot(p[0] - st[0], p[1] - st[1]) < ARRIVE_TOL:
                    self.arrived(v, vid, name)
                else:
                    self.goto(v, st[0], st[1], st[2],
                              f"st_{name}", f"{name} 접근")
                continue

            # 순환 중 — 진입점이 가까워지면 빠져나간다
            gap_entry = self.track.gap(s_me, self.entry_s[name])
            if gap_entry <= APPROACH_TRIGGER:
                # 규칙 3 — 바이는 한 대만. 남이 쓰는 중이면 들어가지 않고
                # 순환로를 계속 돈다(대기 선회). 차선에 멈춰 서면 뒤차가
                # 막혀 교착이 생기기 때문이다.
                if name == "BAY" and self.bay_owner not in (None, vid):
                    self.drive_loop(v, s_me, f"바이 대기 선회 ({self.bay_owner} 사용 중)")
                    continue
                if name == "BAY" and self.bay_owner is None:
                    self.bay_owner = vid
                    self.get_logger().info(f"{v['frame']} 바이 예약")
                v["approaching"] = True
                v["mode"] = ""
                self.goto(v, st[0], st[1], st[2], f"st_{name}", f"{name} 진입")
            else:
                self.drive_loop(v, s_me, f"{name} 로 가는 중")

        if self.max_cycles > 0 and all(
                x["cycles"] >= self.max_cycles for x in self.v.values()):
            self.get_logger().info(
                f"=== 전체 완료 ({time.time() - self.t0:.0f}초) ===")
            self.done = True

    def arrived(self, v, vid, name):
        """스테이션 도착 — 다음 단계로."""
        self.get_logger().info(f"{v['frame']} {name} 도착")

        if v["phase"] == "to_bay":
            # Nav2 는 목표 각도를 느슨하게 본다(yaw_goal_tolerance 3.14).
            # 그래서 도착하자마자 전후진으로 바이 정면에 맞춘 뒤에 화물을 부른다.
            v["cargo_pub"].publish(String(data=json.dumps(
                {"action": "align_bay", "timeout": ALIGN_SEC})))
            v["phase"] = "align_bay"
            v["work_until"] = time.time() + ALIGN_SEC
            self.get_logger().info(
                f"{v['frame']} 바이 정면 정렬 — {ALIGN_SEC:.0f}초")

        elif v["phase"] == "to_exit":
            if self.bay_owner == vid:      # 바이를 비웠다
                self.bay_owner = None
                self.get_logger().info(f"{v['frame']} 바이 반납")
            racks = RACKS[vid]
            rack = racks[v["rack_i"] % len(racks)]
            v["rack_i"] += 1
            self.set_phase(v, "to_rack", rack)

        elif v["phase"] == "to_rack":
            if v["state_seen"] and not v["loaded"]:
                self.get_logger().warn(
                    f"{v['frame']} 화물이 없어 적재를 건너뛴다 — 바이로 복귀")
                v["rack_i"] -= 1                  # 이 랙은 다음에 다시 쓴다
                self.set_phase(v, "to_bay", "BAY")
                return
            rack = v["target"]
            v["cargo_pub"].publish(String(data=json.dumps(
                {"action": "place_rack", "rack": rack})))
            v["phase"] = "rack"
            v["work_until"] = time.time() + RACK_SEC
            v["rack_deadline"] = time.time() + RACK_MAX
            self.get_logger().info(
                f"{v['frame']} 랙 {rack} 선반 적재 (최대 {RACK_MAX:.0f}초)")

    def drive_loop(self, v, s_me, note):
        """순환로를 따라 다음 모서리로 보낸다."""
        if v["idx"] is None:
            v["idx"] = self.track.next_corner(s_me, CORNER_TOL)
        if self.track.gap(s_me, self.track.corner_s[v["idx"]]) < CORNER_TOL:
            v["idx"] = (v["idx"] + 1) % len(self.track.corners)
            v["mode"] = ""
        cx, cy, cyaw = self.track.corner_pose(v["idx"])
        self.goto(v, cx, cy, cyaw, f"corner_{v['idx']}",
                  f"모서리{v['idx']} — {note}")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    with_real = "--with-real" in sys.argv
    direction = args[0] if len(args) > 0 else "CCW"
    cycles = int(args[1]) if len(args) > 1 else 0             # 0 = 무제한
    rclpy.init()
    node = LoopDemo(direction, cycles, with_real)
    try:
        while rclpy.ok() and not node.done:
            rclpy.spin_once(node, timeout_sec=0.1)
    except KeyboardInterrupt:
        print("\n중단됨")
    finally:
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
