#!/usr/bin/env python3
"""2대 랙 적재 데모 — 바이를 번갈아 쓰고 각자 다른 랙에 올린다.

    F02 : 바이(적재) → [게이트 개방] → 랙 A1 접근 → A1 선반 적재 → 끝
    F03 : F02 가 바이를 떠난 뒤 → 바이(적재) → 랙 B1 접근 → B1 선반 적재 → 끝

이동은 항상 일방통행 순환로를 따르고, 진행 내내 교통 규칙이 살아 있다.

    규칙 1  한 방향(기본 반시계)으로만 순환한다. 관제가 그 방향 경유점만 주므로
            역주행·추월 목표 자체가 생기지 않는다.
    규칙 2  순환로를 따라 앞 차량이 '작업 중' 이거나 '대기 중' 이면 HOLD_DIST
            안에서 멈춘다. 앞차가 움직이면 재개한다.
    규칙 3  동시에 출발하지 않는다 (F03 은 게이트가 열려야 움직인다).

앞뒤 판정은 x 좌표가 아니라 순환로를 따라간 거리(호장)로 한다.

랙 적재는 두 단계다.
    Nav2 로 approach 까지 → 거기서부터는 차량 스텝 머신이 도킹·적재·후진.
    포크를 랙 안에 넣어야 하는데 그 자리는 3.8 m footprint 가 랙과 겹쳐
    Nav2 가 경로를 만들 수 없기 때문이다. 실제 물류 시스템도 같은 구조다.

사용:
    source /opt/ros/humble/setup.bash
    python3 demo_rack2.py              # 반시계
    python3 demo_rack2.py cw           # 시계
"""
import os
import sys
import math
import json
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import rclpy
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
    "BAY":  (17.0, 5.0, 0.0),            # 입고 바이
    "EXIT": (15.5, 4.0, math.pi / 2),    # 바이 탈출점 (막다른 곳에서 빠져나옴)
}

CARGO_HEIGHT = 0.15      # 실물 기준 m (시뮬 화물 높이 = x10)
LOAD_SEC = 6.0           # 적재에 두는 시간
RACK_SEC = 30.0          # 랙 적재(정렬·포크·도킹·놓기·후진)에 두는 시간

HOLD_DIST = 9.0          # 앞차가 작업/대기 중이면 멈추는 거리
SAFE_DIST = 6.0          # 그냥 앞차가 가까울 때 멈추는 거리
OFF_LOOP_TOL = 4.0       # 순환로에서 이만큼 벗어난 차량은 교통 판단서 제외
ARRIVE_TOL = 1.5
CORNER_TOL = 1.5
APPROACH_TRIGGER = 4.0   # 스테이션 진입점에 이만큼 남으면 빠져나간다
TICK = 0.5


def make_pose(x, y, yaw):
    p = PoseStamped()
    p.header.frame_id = "map"
    p.pose.position.x = float(x)
    p.pose.position.y = float(y)
    p.pose.orientation.z = math.sin(float(yaw) / 2.0)
    p.pose.orientation.w = math.cos(float(yaw) / 2.0)
    return p


class RackDemo(Node):
    def __init__(self, direction):
        super().__init__("demo_rack2")
        # use_sim_time 을 쓰지 않는다. Isaac 이 잠깐 버벅여 /clock 이 끊겨도
        # 관제 스크립트가 같이 멈추지 않게 하기 위해서다. TF 는 '최신값' 으로
        # 조회하므로 시뮬 시간과 무관하게 동작한다.
        self.track = Track(build_corners(direction))
        self.buf = Buffer()
        TransformListener(self.buf, self)
        self.entry_s = {n: self.track.project(p[:2])[0]
                        for n, p in STATIONS.items()}

        # 대본 — 순서대로 하나씩 수행한다.
        #   ("go", 스테이션)    순환로를 따라가 그 스테이션에 선다
        #   ("load", 높이)      화물 생성
        #   ("rack", 랙이름)    선반 적재 (도킹·놓기·후진, Nav2 아님)
        #   ("gate"/"open", 이름)  동시 출발 방지
        self.v = {
            "SIM_F02": self.mk("sim_f02", "SIM_F02", [
                ("go", "BAY"), ("load", CARGO_HEIGHT),
                ("open", "bay_free"),        # 바이를 비웠다고 알림
                ("go", "EXIT"),
                ("go", "A1"), ("rack", "A1"),
                ("done",),
            ]),
            "SIM_F03": self.mk("sim_f03", "SIM_F03", [
                ("gate", "bay_free"),        # F02 가 바이를 뜰 때까지 대기
                ("go", "BAY"), ("load", CARGO_HEIGHT),
                ("go", "EXIT"),
                ("go", "B1"), ("rack", "B1"),
                ("done",),
            ]),
        }
        self.gates = set()
        self.finished = set()
        self.done = False
        self.t0 = time.time()

        name = "시계방향" if str(direction).upper() == "CW" else "반시계방향"
        self.get_logger().info(
            f"2대 랙 적재 데모 시작 ({name}, 순환로 둘레 {self.track.length:.1f} m)")
        self.get_logger().info("  F02: 바이(적재) → 랙 A1 선반")
        self.get_logger().info("  F03: (F02 가 바이를 뜨면) 바이(적재) → 랙 B1 선반")
        self.create_timer(TICK, self.tick)

    def mk(self, ns, frame, script):
        return {
            "ns": ns, "frame": frame, "script": script, "step": 0,
            "ac": ActionClient(self, NavigateToPose, f"/{ns}/navigate_to_pose"),
            "cancel": self.create_client(
                CancelGoal, f"/{ns}/navigate_to_pose/_action/cancel_goal"),
            "cargo_pub": self.create_publisher(String, f"/{ns}/cargo_cmd", 10),
            "phase": "loop",       # loop | approach | work
            "idx": None,           # 목표 모서리
            "mode": "",            # 마지막 목표(중복 전송 방지)
            "held": False,
            "work_until": 0.0,
        }

    # ── 조회 ────────────────────────────────────────────────────────
    def pos(self, frame):
        try:
            t = self.buf.lookup_transform("map", f"{frame}_base_link",
                                          rclpy.time.Time())
            return (t.transform.translation.x, t.transform.translation.y)
        except Exception:
            return None

    def stopped_kind(self, v):
        if v["phase"] == "work":
            return "작업중"
        if v["held"]:
            return "대기중"
        return None

    # ── 지시 ────────────────────────────────────────────────────────
    def goto(self, v, x, y, yaw, mode, note):
        if v["mode"] == mode and not v["held"]:
            return
        if not v["ac"].wait_for_server(timeout_sec=1.0):
            return
        g = NavigateToPose.Goal()
        g.pose = make_pose(x, y, yaw)
        v["ac"].send_goal_async(g)
        v["mode"] = mode
        v["held"] = False
        self.get_logger().info(f"{v['frame']} → ({x:.1f}, {y:.1f}) {note}")

    def halt(self, v, note):
        if v["cancel"].service_is_ready():
            v["cancel"].call_async(CancelGoal.Request())
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

    # ── 대본 진행 ───────────────────────────────────────────────────
    def advance(self, v):
        v["step"] += 1
        v["phase"] = "loop"
        v["mode"] = ""
        v["idx"] = None

    def tick(self):
        if self.done:
            return
        if len(self.finished) == len(self.v):
            self.get_logger().info(
                f"=== 데모 완료 ({time.time() - self.t0:.0f}초) ===")
            self.done = True
            return

        for v in self.v.values():
            if v["step"] >= len(v["script"]):
                continue
            act = v["script"][v["step"]]
            kind = act[0]

            # 즉시 처리
            if kind == "done":
                if v["frame"] not in self.finished:
                    self.finished.add(v["frame"])
                    self.get_logger().info(f"{v['frame']} 대본 종료")
                continue
            if kind == "gate":
                if act[1] in self.gates:
                    self.get_logger().info(f"{v['frame']} 게이트 '{act[1]}' 열림")
                    self.advance(v)
                continue
            if kind == "open":
                self.gates.add(act[1])
                self.get_logger().info(f"게이트 '{act[1]}' 개방")
                self.advance(v)
                continue

            p = self.pos(v["frame"])
            if p is None:
                continue
            s_me, _ = self.track.project(p)

            # 작업 중 — 제자리. 뒤차는 규칙 2로 알아서 멈춘다.
            if v["phase"] == "work":
                if time.time() >= v["work_until"]:
                    self.advance(v)
                continue

            # 규칙 2 — 이동 중에는 항상 적용
            blk = self.blocker(v, s_me)
            if blk:
                self.halt(v, blk[1])
                continue

            if kind == "load":
                # 바이 정면으로 맞춘 뒤에 화물을 부른다. 각도가 어긋나 있으면
                # cargo_demo 가 적재를 거부한다(실제로도 포크가 안 꽂힌다).
                v["cargo_pub"].publish(String(data=json.dumps(
                    {"action": "align_bay", "timeout": 7.0})))
                time.sleep(0.2)
                v["cargo_pub"].publish(
                    String(data=json.dumps({"height": act[1], "delay": 7.5})))
                v["phase"] = "work"
                v["work_until"] = time.time() + 7.5 + LOAD_SEC
                self.get_logger().info(
                    f"{v['frame']} 적재 (높이 {act[1]} m) — {LOAD_SEC:.0f}초")
                continue

            if kind == "rack":
                v["cargo_pub"].publish(String(data=json.dumps(
                    {"action": "place_rack", "rack": act[1]})))
                v["phase"] = "work"
                v["work_until"] = time.time() + RACK_SEC
                self.get_logger().info(
                    f"{v['frame']} 랙 {act[1]} 선반 적재 — {RACK_SEC:.0f}초 "
                    f"(도킹·놓기·후진)")
                continue

            if kind == "go":
                st = STATIONS[act[1]]
                if v["phase"] == "approach":
                    if math.hypot(p[0] - st[0], p[1] - st[1]) < ARRIVE_TOL:
                        self.get_logger().info(f"{v['frame']} {act[1]} 도착")
                        self.advance(v)
                    else:
                        self.goto(v, st[0], st[1], st[2],
                                  f"st_{act[1]}", f"{act[1]} 접근")
                    continue
                gap_entry = self.track.gap(s_me, self.entry_s[act[1]])
                if gap_entry <= APPROACH_TRIGGER:
                    v["phase"] = "approach"
                    v["mode"] = ""
                    self.goto(v, st[0], st[1], st[2],
                              f"st_{act[1]}", f"{act[1]} 진입")
                else:
                    self.drive_loop(v, s_me, f"{act[1]} 로 가는 중")
                continue

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
    direction = sys.argv[1] if len(sys.argv) > 1 else "CCW"
    rclpy.init()
    node = RackDemo(direction)
    try:
        while rclpy.ok() and not node.done:
            rclpy.spin_once(node, timeout_sec=0.1)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
