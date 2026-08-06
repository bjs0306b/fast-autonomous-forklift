#!/usr/bin/env python3
"""다른 차량의 '몸통 전체'를 각 차량의 costmap 에 장애물로 넣어준다.

왜 필요한가:
    라이다는 상대 차량의 '앞면 한 겹'만 본다. 그래서 costmap 에는 3.8 m 짜리
    몸통이 선 하나로 찍히고, 그걸 몸통 크기로 덮으려고 inflation 을 키우면
    통로까지 막혀서 "Starting point in lethal space" 가 난다.

    각 차량의 위치는 TF 로 이미 알고 있으므로, 상대 몸통 사각형을 점구름으로
    만들어 obstacle_layer 의 추가 관측 소스로 넣는다. 몸통 전체가 장애물이
    되므로 inflation 을 작게 유지해도 제대로 피한다.

    점구름은 '보는 차량의 base_link 프레임' 으로 발행한다. 그래야 obstacle_layer
    가 그 차량을 센서 원점으로 삼아 레이트레이싱 클리어링을 해서, 상대가 움직인
    뒤 남는 잔상이 지워진다.

사용:
    source /opt/ros/humble/setup.bash
    python3 fleet_obstacles.py                 # 전 차량 상호 반영
    python3 fleet_obstacles.py --only sim_f02  # sim_f02 만 발행 (단독 주행 데모)

단독 주행 데모에서는 --only 로 대상을 좁히면, 세워둔 다른 차량이 통로를
막아 "detected collision ahead" 로 멈추는 일을 피할 수 있다. 이 창고는
주행 가능 공간이 전부 순환로 통로라 차량을 치워 둘 여유 공간이 없다.
"""
import sys
import math
import rclpy
from rclpy.node import Node
from sensor_msgs.msg import PointCloud2
from sensor_msgs_py import point_cloud2
from std_msgs.msg import Header
from tf2_ros import Buffer, TransformListener

# 차량 목록: (네임스페이스, TF 프레임 접두사)
VEHICLES = [
    ("sim_f02", "SIM_F02"),
    ("sim_f03", "SIM_F03"),
    # 실물 미러. mirror.py 가 켜져 있으면 TF 가 생기고, 그러면 시뮬 차량들의
    # costmap 에 몸통이 찍혀 실물을 피해 간다. 미러가 꺼져 있으면 TF 가 없어
    # 자동으로 건너뛴다(pose() 가 None 을 돌려준다).
    ("real_f01", "REAL_F01"),
]

# 차체 크기 (실측 bbox). 절반값으로 사각형을 채운다.
HALF_LEN = 1.9      # x 방향 반길이 (전체 3.8 m)
HALF_WID = 0.95     # y 방향 반폭   (전체 1.9 m)
STEP = 0.25         # 점 간격 (m)
POINT_Z = 0.3       # 점 높이 - obstacle_layer 의 height 범위 안이어야 한다
RATE_HZ = 5.0

# ── 중앙 분리대 ────────────────────────────────────────────────────
# 일방통행 2차선(위 y=6.2 → , 아래 y=2.8 ←) 사이에 가상 분리대를 세운다.
# 이걸 costmap 에 장애물로 넣으면 차선을 넘는 추월 자체가 불가능해지고,
# 앞차가 운반/적재로 서 있으면 뒤차는 넘어갈 수 없어 기다리게 된다.
# 양 끝(x < DIVIDER_X0, x > DIVIDER_X1)은 U턴용으로 비워 둔다.
DIVIDER_Y = 4.5
DIVIDER_X0, DIVIDER_X1 = 6.0, 13.5
DIVIDER_ON = False   # 분리대 대신 시계방향 순환으로 통제한다


def divider_points_world():
    """중앙 분리대를 이루는 점들 (월드 좌표). 양 끝은 U턴용으로 비어 있다."""
    if not DIVIDER_ON:
        return []
    n = int((DIVIDER_X1 - DIVIDER_X0) / STEP)
    return [(DIVIDER_X0 + i * STEP, DIVIDER_Y) for i in range(n + 1)]


def body_points_world(cx, cy, yaw):
    """차량 몸통 사각형을 덮는 점들을 월드 좌표로 만든다."""
    pts = []
    c, s = math.cos(yaw), math.sin(yaw)
    nx = int(HALF_LEN / STEP)
    ny = int(HALF_WID / STEP)
    for i in range(-nx, nx + 1):
        for j in range(-ny, ny + 1):
            lx, ly = i * STEP, j * STEP
            pts.append((cx + lx * c - ly * s, cy + lx * s + ly * c))
    return pts


class FleetObstacles(Node):
    def __init__(self):
        super().__init__("fleet_obstacles")
        from rclpy.parameter import Parameter
        self.set_parameters([Parameter("use_sim_time", Parameter.Type.BOOL, True)])
        self.buf = Buffer()
        TransformListener(self.buf, self)

        self.pubs = {
            ns: self.create_publisher(PointCloud2, f"/{ns}/fleet_obstacles", 1)
            for ns, _ in VEHICLES
        }
        self.warned = False
        self.last_err = ""
        self.create_timer(1.0 / RATE_HZ, self.tick)
        self.get_logger().info(
            "fleet_obstacles 시작 - 상대 차량 몸통을 costmap 에 넣는 중")

    def pose(self, frame):
        """map 기준 (x, y, yaw). 못 얻으면 None."""
        try:
            t = self.buf.lookup_transform("map", f"{frame}_base_link",
                                          rclpy.time.Time())
        except Exception as e:
            self.last_err = f"{frame}: {e}"
            return None
        q = t.transform.rotation
        yaw = math.atan2(2 * (q.w * q.z + q.x * q.y),
                         1 - 2 * (q.y * q.y + q.z * q.z))
        return (t.transform.translation.x, t.transform.translation.y, yaw)

    def tick(self):
        poses = {frame: self.pose(frame) for _, frame in VEHICLES}
        if all(p is None for p in poses.values()):
            if not self.warned:
                self.get_logger().warn(
                    f"차량 TF 를 못 받음 -> {self.last_err}\n"
                    "  순서 확인: ① clock_pub.py(Isaac) ② run_nav2_multi.sh "
                    "③ 이 스크립트")
                self.warned = True
            return
        if self.warned:
            self.get_logger().info("TF 복구됨 - 정상 발행 중")
        self.warned = False

        now = self.get_clock().now().to_msg()
        for ns, frame in VEHICLES:
            me = poses.get(frame)
            if me is None:
                continue
            mx, my, myaw = me

            # 나 자신을 뺀 나머지 차량들의 몸통 점 -> 내 base_link 프레임으로
            pts = []
            c, s = math.cos(-myaw), math.sin(-myaw)
            for _, other in VEHICLES:
                if other == frame:
                    continue
                op = poses.get(other)
                if op is None:
                    continue
                for wx, wy in body_points_world(op[0], op[1], op[2]):
                    dx, dy = wx - mx, wy - my
                    pts.append((dx * c - dy * s, dx * s + dy * c, POINT_Z))

            # 중앙 분리대도 같은 구름에 실어 보낸다 -> 차선 넘기 불가
            for wx, wy in divider_points_world():
                dx, dy = wx - mx, wy - my
                pts.append((dx * c - dy * s, dx * s + dy * c, POINT_Z))

            header = Header(stamp=now, frame_id=f"{frame}_base_link")
            self.pubs[ns].publish(point_cloud2.create_cloud_xyz32(header, pts))


def main():
    global VEHICLES
    if "--only" in sys.argv:
        keep = sys.argv[sys.argv.index("--only") + 1]
        VEHICLES = [v for v in VEHICLES if v[0] == keep]
        print(f"단독 모드: {keep} 만 발행 (다른 차량은 costmap 에 안 넣음)")
    rclpy.init()
    node = FleetObstacles()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
