#!/usr/bin/env python3
"""통합 관제 — 작업(적재/하역)을 하면서도 순환 교통 규칙이 계속 적용된다.

차량은 일감(스테이션)을 순서대로 돌지만, 이동은 항상 일방통행 순환로를
따라간다. 스테이션에 도착하면 그 자리에서 작업(정지)하고, 끝나면 순환로로
복귀해 다음 스테이션으로 간다.

    순환(CIRCULATE) → 스테이션 접근(APPROACH) → 작업(WORK)
        → 순환로 복귀(REJOIN) → 순환 … 반복

교통 규칙은 작업 중이든 이동 중이든 항상 살아 있다:

    규칙 1  모든 차량은 한 방향으로만 순환한다 (CCW 기본).
            관제가 그 방향 경유점만 주므로 역주행·추월 목표가 생기지 않는다.
    규칙 2  순환로를 따라 내 앞에 있는 차량이 '작업 중' 이거나 '대기 중' 이면
            HOLD_DIST 안에서 멈춘다. 그 차가 움직이기 시작하면 재개한다.
    규칙 3  시작할 때 한 대씩 순차 합류한다 (동시 출발 금지).

앞뒤 판정은 순환로를 따라간 거리(호장)로 한다. 세로 구간이 있어 x 좌표만으로는
앞뒤를 알 수 없다.

사용:
    source /opt/ros/humble/setup.bash
    python3 fleet_manager.py               # 반시계
    python3 fleet_manager.py cw            # 시계
"""
import os
import sys
import math
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import rclpy
from rclpy.node import Node
from rclpy.action import ActionClient
from action_msgs.srv import CancelGoal
from geometry_msgs.msg import PoseStamped
from nav2_msgs.action import NavigateToPose
from std_msgs.msg import Float32
from tf2_ros import Buffer, TransformListener

from track import Track, build_corners

# ── 스테이션 (맵에서 3.8x1.9 차체가 설 수 있는지 확인된 좌표만) ──────
#   work  : 그 자리에서 머무는 시간(초). 이 동안 뒤차는 규칙 2로 멈춘다.
#   fork  : 도착 시 포크 높이(m). None 이면 건드리지 않는다.
STATIONS = {
    "BAY":  {"pos": (16.5, 5.0, 0.0),          "work": 15.0, "fork": 0.0},
    "RACK": {"pos": (5.0, 20.0, math.pi / 2),  "work": 12.0, "fork": 1.35},
}
# 각 차량이 반복할 일감 순서
JOB_CYCLE = ["BAY", "RACK"]

VEHICLES = [
    {"ns": "sim_f02", "frame": "SIM_F02"},
    {"ns": "sim_f03", "frame": "SIM_F03"},
]

DIRECTION = "CCW"

APPROACH_TRIGGER = 4.0   # 스테이션 진입점에 이만큼 남으면 빠져나간다
ARRIVE_TOL = 1.2         # 스테이션 도착 판정
REJOIN_TOL = 1.5         # 순환로 복귀 판정
HOLD_DIST = 9.0          # 앞차가 작업/대기 중일 때 멈추는 거리
SAFE_DIST = 6.0          # 그냥 앞차가 가까울 때 멈추는 거리
ENTRY_HEADWAY = 10.0     # 합류 시 앞이 이만큼 비어야 한다
ENTRY_INTERVAL = 3.0
OFF_LOOP_TOL = 4.0       # 순환로에서 이만큼 벗어난 차량은 교통 판단서 제외
CORNER_TOL = 1.5
TICK = 0.5


def make_pose(x, y, yaw):
    p = PoseStamped()
    p.header.frame_id = "map"
    p.pose.position.x = float(x)
    p.pose.position.y = float(y)
    p.pose.orientation.z = math.sin(float(yaw) / 2.0)
    p.pose.orientation.w = math.cos(float(yaw) / 2.0)
    return p


class FleetManager(Node):
    def __init__(self, direction):
        super().__init__("fleet_manager")
        from rclpy.parameter import Parameter
        self.set_parameters([Parameter("use_sim_time", Parameter.Type.BOOL, True)])

        self.track = Track(build_corners(direction))
        self.buf = Buffer()
        TransformListener(self.buf, self)

        # 각 스테이션의 '순환로상 진입점' 을 미리 구해 둔다
        self.entry_s = {}
        for name, st in STATIONS.items():
            s, off = self.track.project(st["pos"][:2])
            self.entry_s[name] = s
            self.get_logger().info(
                f"스테이션 {name} {st['pos'][:2]} → 순환로 s={s:.1f} "
                f"(이탈 {off:.1f} m)")

        for i, v in enumerate(VEHICLES):
            v["ac"] = ActionClient(self, NavigateToPose,
                                   f"/{v['ns']}/navigate_to_pose")
            v["cancel"] = self.create_client(
                CancelGoal, f"/{v['ns']}/navigate_to_pose/_action/cancel_goal")
            v["fork_pub"] = self.create_publisher(
                Float32, f"/{v['ns']}/fork_cmd", 10)
            v["phase"] = "WAIT_ENTRY"   # WAIT_ENTRY|CIRCULATE|APPROACH|WORK|REJOIN
            v["job"] = i % len(JOB_CYCLE)   # 차량마다 다른 일감부터 시작
            v["idx"] = None             # 목표 모서리
            v["mode"] = ""              # 마지막으로 보낸 목표 종류(중복 전송 방지)
            v["held"] = False
            v["work_until"] = 0.0

        self.release_cooldown = 0.0
        name = "시계방향" if str(direction).upper() == "CW" else "반시계방향"
        self.get_logger().info(
            f"{name} 통합 관제 시작 — 순환로 둘레 {self.track.length:.1f} m, "
            f"일감 {' → '.join(JOB_CYCLE)}")
        self.create_timer(TICK, self.tick)

    # ── 조회 ────────────────────────────────────────────────────────
    def pos(self, frame):
        try:
            t = self.buf.lookup_transform("map", f"{frame}_base_link",
                                          rclpy.time.Time())
            return (t.transform.translation.x, t.transform.translation.y)
        except Exception:
            return None

    def stopped_kind(self, v):
        """이 차량이 '멈춰 있는' 상태인가 (뒤차가 멈춰야 하는 이유)."""
        if v["phase"] == "WORK":
            return "적재중"
        if v["held"]:
            return "대기중"
        return None

    # ── 지시 ────────────────────────────────────────────────────────
    def goto(self, v, x, y, yaw, mode, note=""):
        if v["mode"] == mode and not v["held"]:
            return                      # 같은 목표를 반복 전송하지 않는다
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

    # ── 규칙 2: 앞차가 작업/대기 중이면 멈춘다 ──────────────────────
    def blocker(self, me, s_me):
        best = None
        for other in VEHICLES:
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

    # ── 규칙 3: 순차 합류 ───────────────────────────────────────────
    def release_one(self):
        self.release_cooldown = max(0.0, self.release_cooldown - TICK)
        if self.release_cooldown > 0:
            return
        for v in VEHICLES:
            if v["phase"] != "WAIT_ENTRY":
                continue
            p = self.pos(v["frame"])
            if p is None:
                continue
            s_me, off = self.track.project(p)
            blk = self.blocker(v, s_me)
            if blk and blk[0] < ENTRY_HEADWAY:
                self.get_logger().info(f"{v['frame']} 합류 대기 — {blk[1]}")
                return
            v["phase"] = "CIRCULATE"
            v["idx"] = self.track.next_corner(s_me, CORNER_TOL)
            self.release_cooldown = ENTRY_INTERVAL
            self.get_logger().info(
                f"{v['frame']} 합류 — 첫 일감 {JOB_CYCLE[v['job']]}")
            return

    # ── 관제 주기 ───────────────────────────────────────────────────
    def tick(self):
        self.release_one()

        for v in VEHICLES:
            p = self.pos(v["frame"])
            if p is None:
                continue

            # 작업 중: 제자리. 뒤차는 규칙 2로 알아서 멈춘다.
            if v["phase"] == "WORK":
                if time.time() >= v["work_until"]:
                    job = JOB_CYCLE[v["job"]]
                    v["job"] = (v["job"] + 1) % len(JOB_CYCLE)
                    v["phase"] = "REJOIN"
                    v["mode"] = ""
                    self.get_logger().info(
                        f"{v['frame']} {job} 작업 완료 → 순환 복귀 "
                        f"(다음 {JOB_CYCLE[v['job']]})")
                continue

            if v["phase"] == "WAIT_ENTRY":
                continue

            s_me, off = self.track.project(p)

            # 규칙 2 — 이동 중에는 항상 적용
            blk = self.blocker(v, s_me)
            if blk:
                self.halt(v, blk[1])
                continue

            job = JOB_CYCLE[v["job"]]
            st = STATIONS[job]

            # 순환로 복귀 중
            if v["phase"] == "REJOIN":
                ex, ey, eyaw = self.track.point_at(self.entry_s[job])
                if math.hypot(p[0] - ex, p[1] - ey) < REJOIN_TOL:
                    v["phase"] = "CIRCULATE"
                    v["idx"] = self.track.next_corner(s_me, CORNER_TOL)
                    v["mode"] = ""
                    self.get_logger().info(f"{v['frame']} 순환 복귀 완료")
                else:
                    self.goto(v, ex, ey, eyaw, "rejoin", "순환로 복귀")
                continue

            # 스테이션 접근 중
            if v["phase"] == "APPROACH":
                sx, sy, syaw = st["pos"]
                if math.hypot(p[0] - sx, p[1] - sy) < ARRIVE_TOL:
                    v["phase"] = "WORK"
                    v["work_until"] = time.time() + st["work"]
                    v["mode"] = ""
                    if st.get("fork") is not None:
                        v["fork_pub"].publish(Float32(data=float(st["fork"])))
                    self.get_logger().info(
                        f"{v['frame']} {job} 도착 — {st['work']:.0f}초 작업 "
                        f"(뒤차는 이 앞에서 대기)")
                else:
                    self.goto(v, sx, sy, syaw, f"approach_{job}", f"{job} 접근")
                continue

            # 순환 중 — 일감 진입점이 다음 모서리보다 가까우면 빠져나간다
            gap_entry = self.track.gap(s_me, self.entry_s[job])
            gap_corner = self.track.gap(s_me, self.track.corner_s[v["idx"]])
            if gap_entry <= APPROACH_TRIGGER:
                v["phase"] = "APPROACH"
                v["mode"] = ""
                sx, sy, syaw = st["pos"]
                self.goto(v, sx, sy, syaw, f"approach_{job}", f"{job} 진입")
                continue

            if gap_corner < CORNER_TOL:
                v["idx"] = (v["idx"] + 1) % len(self.track.corners)
                v["mode"] = ""
            cx, cy, cyaw = self.track.corner_pose(v["idx"])
            self.goto(v, cx, cy, cyaw, f"corner_{v['idx']}",
                      f"모서리{v['idx']} (일감 {job})")


def main():
    direction = sys.argv[1] if len(sys.argv) > 1 else DIRECTION
    rclpy.init()
    node = FleetManager(direction)
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
