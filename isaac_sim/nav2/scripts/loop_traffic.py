#!/usr/bin/env python3
"""큰 순환로 일방통행 + 적재 지점 뒤 차량 대기 (관제).

맵 구조 (sim_warehouse.pgm 실측):
    가운데 x 9~12 는 랙. 그 좌우로 세로 통로(x 3~8 / x 13~18)가 y 2.5~27.7
    전 구간 뚫려 있고, 아래 홀(y 2~7)과 위 홀(y 26~28)이 둘을 잇는다.
    따라서 랙을 둘러싸는 약 10.5 x 23 m 순환로를 만들 수 있다.

        y=27  ┌──────────────┐   위 홀
              │    [ 랙 ]    │
        y=4   └──────────────┘   아래 홀
             x=5            x=15.5

규칙 3개:
    1. 모든 차량은 한 방향으로만 순환한다 (CCW 기본). 관제가 그 방향
       경유점만 주므로 역주행·차선 넘기 목표 자체가 생기지 않는다.
    2. 어떤 차량이 적재/하역 중이면, 순환로를 따라 그 '적재 좌표 뒤'에 있는
       차량은 적재 좌표 앞 정지선에서 멈춘다. 끝나면 재개한다.
    3. 시작할 때 한 대씩 순차 합류한다 (동시 출발 금지).

앞뒤 판정은 x 좌표가 아니라 '순환로를 따라간 거리(호장)' 로 한다.
세로 구간이 있으므로 x 만으로는 앞뒤를 알 수 없다.

사용:
    source /opt/ros/humble/setup.bash
    python3 loop_traffic.py            # 반시계, F03 40초 적재
    python3 loop_traffic.py 40 cw      # 시계방향
    python3 loop_traffic.py 0          # 적재 없이 순환만
"""
import sys
import math
import rclpy
from rclpy.node import Node
from rclpy.action import ActionClient
from nav2_msgs.action import NavigateToPose
from geometry_msgs.msg import PoseStamped
from tf2_ros import Buffer, TransformListener

# ── 순환로 모서리 (맵에서 가로/세로 양방향 통과 확인된 좌표) ──────────
X_LEFT, X_RIGHT = 5.0, 15.5      # 좌/우 세로 통로
Y_BOT, Y_TOP = 4.0, 27.0         # 아래/위 가로 홀

DIRECTION = "CCW"                # "CW" | "CCW"


def build_corners(direction):
    """순환 방향에 맞춘 모서리 순서.

      CCW (반시계): 아래 +x → 오른쪽 +y → 위 -x → 왼쪽 -y
      CW  (시계)  : 아래 -x → 왼쪽 +y   → 위 +x → 오른쪽 -y
    """
    if direction.upper() == "CW":
        return [(X_LEFT, Y_BOT), (X_LEFT, Y_TOP),
                (X_RIGHT, Y_TOP), (X_RIGHT, Y_BOT)]
    return [(X_RIGHT, Y_BOT), (X_RIGHT, Y_TOP),
            (X_LEFT, Y_TOP), (X_LEFT, Y_BOT)]


# ── 적재 지점 ───────────────────────────────────────────────────────
LOAD_SPOT = (10.0, Y_BOT)   # 아래 홀 가운데에서 적재한다고 가정
STOP_GAP = 7.0              # 적재 좌표 앞 정지선까지 거리 (순환로 따라)
LOADER_INDEX = 1            # 적재를 수행하는 차량

VEHICLES = [
    {"ns": "sim_f02", "frame": "SIM_F02"},
    {"ns": "sim_f03", "frame": "SIM_F03"},
]

ENTRY_HEADWAY = 10.0   # 합류 시 앞에 움직이는 차가 이 안이면 보류
ENTRY_INTERVAL = 3.0   # 합류 허가 사이 최소 간격(초)
SAFE_DIST = 6.0        # 앞차가 이 안이면 비상 정지
OFF_LOOP_TOL = 4.0     # 순환로에서 이만큼 벗어난 차량은 교통 판단에서 제외
REACH_TOL = 1.5        # 경유점 도달 판정
STOPLINE_TOL = 1.2     # 정지선 도달 판정
TICK = 0.5


# ── 순환로 기하 (호장 기반) ─────────────────────────────────────────
class Track:
    """모서리들을 이은 닫힌 경로. 위치를 '따라간 거리 s' 로 다룬다."""

    def __init__(self, corners):
        self.corners = corners
        self.segs = []          # (시작점, 끝점, 길이)
        self.corner_s = []      # 각 모서리의 s
        acc = 0.0
        n = len(corners)
        for i in range(n):
            a, b = corners[i], corners[(i + 1) % n]
            L = math.dist(a, b)
            self.corner_s.append(acc)
            self.segs.append((a, b, L))
            acc += L
        self.length = acc

    def project(self, p):
        """p 를 경로에 사영 -> (따라간 거리 s, 경로에서 벗어난 거리)."""
        best_d, best_s = float("inf"), 0.0
        acc = 0.0
        for a, b, L in self.segs:
            if L < 1e-6:
                continue
            vx, vy = b[0] - a[0], b[1] - a[1]
            t = ((p[0] - a[0]) * vx + (p[1] - a[1]) * vy) / (L * L)
            t = max(0.0, min(1.0, t))
            cx, cy = a[0] + t * vx, a[1] + t * vy
            d = math.hypot(p[0] - cx, p[1] - cy)
            if d < best_d:
                best_d, best_s = d, acc + t * L
            acc += L
        return best_s, best_d

    def point_at(self, s):
        """거리 s 지점의 (x, y, 진행 방향)."""
        s %= self.length
        acc = 0.0
        for a, b, L in self.segs:
            if s <= acc + L or L < 1e-6:
                t = (s - acc) / L if L > 1e-6 else 0.0
                return (a[0] + t * (b[0] - a[0]),
                        a[1] + t * (b[1] - a[1]),
                        math.atan2(b[1] - a[1], b[0] - a[0]))
            acc += L
        a, b, _ = self.segs[-1]
        return b[0], b[1], math.atan2(b[1] - a[1], b[0] - a[0])

    def gap(self, s_from, s_to):
        """진행 방향으로 s_from 에서 s_to 까지 남은 거리."""
        return (s_to - s_from) % self.length

    def corner_pose(self, i):
        """모서리 i 의 (x, y, 그 모서리를 돈 뒤 진행할 방향)."""
        x, y = self.corners[i]
        a = self.corners[i]
        b = self.corners[(i + 1) % len(self.corners)]
        return x, y, math.atan2(b[1] - a[1], b[0] - a[0])


TRACK = Track(build_corners(DIRECTION))


def make_pose(x, y, yaw):
    p = PoseStamped()
    p.header.frame_id = "map"
    p.pose.position.x = float(x)
    p.pose.position.y = float(y)
    p.pose.orientation.z = math.sin(yaw / 2.0)
    p.pose.orientation.w = math.cos(yaw / 2.0)
    return p


class LoopTraffic(Node):
    def __init__(self, load_seconds):
        super().__init__("loop_traffic")
        from rclpy.parameter import Parameter
        self.set_parameters([Parameter("use_sim_time", Parameter.Type.BOOL, True)])
        self.buf = Buffer()
        TransformListener(self.buf, self)

        for v in VEHICLES:
            v["ac"] = ActionClient(self, NavigateToPose,
                                   f"/{v['ns']}/navigate_to_pose")
            v["idx"] = None       # 목표 모서리 번호
            v["gh"] = None
            v["mode"] = ""        # "loop" | "stopline" | "held"
            v["joined"] = False

        self.release_cooldown = 0.0
        self.load_left = float(load_seconds)
        self.load_s = TRACK.project(LOAD_SPOT)[0]

        name = "시계방향" if DIRECTION.upper() == "CW" else "반시계방향"
        self.get_logger().info(
            f"{name} 큰 순환로 관제 시작 — 둘레 {TRACK.length:.1f} m "
            f"(x {X_LEFT}~{X_RIGHT}, y {Y_BOT}~{Y_TOP})")
        if self.load_left > 0:
            self.get_logger().info(
                f"{VEHICLES[LOADER_INDEX]['frame']} 이 {LOAD_SPOT} 에서 "
                f"{self.load_left:.0f}초간 적재")
        self.create_timer(TICK, self.tick)

    # ── 조회 ────────────────────────────────────────────────────────
    def pos(self, frame):
        try:
            t = self.buf.lookup_transform("map", f"{frame}_base_link",
                                          rclpy.time.Time())
            return (t.transform.translation.x, t.transform.translation.y)
        except Exception:
            return None

    # ── 지시 ────────────────────────────────────────────────────────
    def goto(self, v, x, y, yaw, mode, note=""):
        if not v["ac"].wait_for_server(timeout_sec=1.0):
            return
        g = NavigateToPose.Goal()
        g.pose = make_pose(x, y, yaw)
        fut = v["ac"].send_goal_async(g)
        fut.add_done_callback(lambda f, veh=v: veh.__setitem__("gh", f.result()))
        if v["mode"] != mode or mode == "loop":
            self.get_logger().info(f"{v['frame']} → ({x:.1f}, {y:.1f}) {note}")
        v["mode"] = mode

    def halt(self, v, note):
        if v["gh"] is not None:
            v["gh"].cancel_goal_async()
            v["gh"] = None
        if v["mode"] != "held":
            self.get_logger().info(f"{v['frame']} 정지 — {note}")
        v["mode"] = "held"

    # ── 앞차까지 거리 (순환로 따라) ─────────────────────────────────
    def gap_ahead(self, me, s_me, moving_only=False):
        loader_stopped = self.load_left > 0
        best = None
        for other in VEHICLES:
            if other is me:
                continue
            if moving_only:
                if not other["joined"]:
                    continue
                if loader_stopped and other is VEHICLES[LOADER_INDEX]:
                    continue
            op = self.pos(other["frame"])
            if op is None:
                continue
            s_o, off = TRACK.project(op)
            if off > OFF_LOOP_TOL:      # 순환로 밖에 있는 차량은 무시
                continue
            g = TRACK.gap(s_me, s_o)
            if best is None or g < best[0]:
                best = (g, other["frame"])
        return best

    # ── 합류: 한 번에 한 대 ─────────────────────────────────────────
    def release_one(self):
        self.release_cooldown = max(0.0, self.release_cooldown - TICK)
        if self.release_cooldown > 0:
            return
        for i, v in enumerate(VEHICLES):
            if v["joined"]:
                continue
            if i == LOADER_INDEX and self.load_left > 0:
                continue
            p = self.pos(v["frame"])
            if p is None:
                continue
            s_me, off = TRACK.project(p)
            gap = self.gap_ahead(v, s_me, moving_only=True)
            if gap and gap[0] < ENTRY_HEADWAY:
                self.get_logger().info(
                    f"{v['frame']} 합류 대기 — 앞에 {gap[1]} 이 {gap[0]:.1f} m")
                return
            v["joined"] = True
            v["idx"] = self.next_corner(s_me)
            self.release_cooldown = ENTRY_INTERVAL
            self.get_logger().info(
                f"{v['frame']} 합류 허가 (순환로 이탈 {off:.1f} m) — 순환 시작")
            return

    def next_corner(self, s_me):
        """진행 방향으로 가장 가까운 다음 모서리 번호."""
        gaps = [(TRACK.gap(s_me, cs), i) for i, cs in enumerate(TRACK.corner_s)]
        gaps = [(g if g > REACH_TOL else g + TRACK.length, i) for g, i in gaps]
        return min(gaps)[1]

    # ── 관제 주기 ───────────────────────────────────────────────────
    def tick(self):
        self.release_one()
        loading = self.load_left > 0
        if loading:
            self.load_left -= TICK
            if self.load_left <= 0:
                self.get_logger().info(
                    f"{VEHICLES[LOADER_INDEX]['frame']} 적재 완료 — 통행 재개")

        for i, v in enumerate(VEHICLES):
            if i == LOADER_INDEX and loading:
                continue
            if not v["joined"]:
                continue
            p = self.pos(v["frame"])
            if p is None:
                continue
            s_me, _ = TRACK.project(p)
            if v["idx"] is None:
                v["idx"] = self.next_corner(s_me)

            gap_corner = TRACK.gap(s_me, TRACK.corner_s[v["idx"]])

            # ── 규칙 2: 적재 좌표가 다음 경유점보다 먼저면 정지선까지만 ──
            if loading and i != LOADER_INDEX:
                gap_load = TRACK.gap(s_me, self.load_s)
                if gap_load <= gap_corner:
                    sx, sy, syaw = TRACK.point_at(self.load_s - STOP_GAP)
                    remain = gap_load - STOP_GAP
                    if remain <= STOPLINE_TOL:
                        self.halt(v, f"적재 지점 {LOAD_SPOT} 뒤 정지선 대기")
                    else:
                        self.goto(v, sx, sy, syaw, "stopline",
                                  f"정지선 (앞에서 적재 중, {remain:.1f} m)")
                    continue

            # ── 보조 안전장치 ──────────────────────────────────────
            gap = self.gap_ahead(v, s_me)
            if gap and gap[0] < SAFE_DIST:
                self.halt(v, f"{gap[1]} 이 {gap[0]:.1f} m 앞")
                continue

            # ── 정상 순환 ─────────────────────────────────────────
            if gap_corner < REACH_TOL:
                v["idx"] = (v["idx"] + 1) % len(TRACK.corners)
                x, y, yaw = TRACK.corner_pose(v["idx"])
                self.goto(v, x, y, yaw, "loop", f"모서리{v['idx']}")
            elif v["mode"] != "loop" or v["gh"] is None:
                x, y, yaw = TRACK.corner_pose(v["idx"])
                self.goto(v, x, y, yaw, "loop", f"모서리{v['idx']}")


def main():
    global DIRECTION, TRACK
    load = float(sys.argv[1]) if len(sys.argv) > 1 else 40.0
    if len(sys.argv) > 2:
        DIRECTION = sys.argv[2].upper()
        TRACK = Track(build_corners(DIRECTION))
    rclpy.init()
    node = LoopTraffic(load)
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
