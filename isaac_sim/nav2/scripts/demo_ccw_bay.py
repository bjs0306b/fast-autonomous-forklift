#!/usr/bin/env python3
"""데모: 맵을 크게 한 바퀴(반시계) 돌고 입고 바이(16.5, 5)로 진입.

맵 구조 (실측): 가운데 x 9~12 는 랙. 좌우 세로 통로(x 3~8 / x 13~18)가
y 2.5~27.7 전 구간 뚫려 있고, 아래 홀(y 2~7)·위 홀(y 26~28)이 둘을 잇는다.
그래서 랙을 둘러싸는 둘레 약 67 m 순환로가 만들어진다.

    (7, 4) 출발 ── 아래 홀을 동쪽으로 ──▶ (15.5, 4)
                                          │ 오른쪽 통로 올라감
    (5, 27) ◀── 위 홀을 서쪽으로 ──── (15.5, 27)
      │ 왼쪽 통로 내려옴
    (5, 4) ──── 아래 홀 동쪽으로 ────▶ (16.5, 5) 바이

경유점은 전부 맵에서 3.8x1.9 차체가 가로/세로 양방향으로 통과 가능한지
확인한 좌표다. navigate_through_poses 는 humble 에서 robot_base_frame 을
"base_link" 로 조회하는 문제가 있어 쓰지 않고, navigate_to_pose 로 한 점씩
보낸다.

사용:
    source /opt/ros/humble/setup.bash
    python3 demo_ccw_bay.py
"""
import math
import rclpy
from rclpy.node import Node
from rclpy.action import ActionClient
from nav2_msgs.action import NavigateToPose
from nav2_msgs.srv import ClearEntireCostmap
from geometry_msgs.msg import PoseStamped
from tf2_ros import Buffer, TransformListener

NS = "sim_f02"
FRAME = "SIM_F02"

E, W = 0.0, math.pi                      # 동(+x), 서(-x)
N, S = math.pi / 2, -math.pi / 2         # 북(+y), 남(-y)

# 도착 heading 은 '그 모서리를 돌고 나서 이어 달릴 방향' 으로 준다.
ROUTE = [
    (15.5,  4.0, N, "아래 홀 동쪽 끝 → 오른쪽 통로로"),
    (15.5, 27.0, W, "오른쪽 통로 끝(위) → 위 홀로"),
    (5.0,  27.0, S, "위 홀 서쪽 끝 → 왼쪽 통로로"),
    (5.0,   4.0, E, "왼쪽 통로 끝(아래) → 한 바퀴 완료"),
    (17.0,  5.0, E, "입고 바이 진입"),
]


def make_pose(x, y, yaw):
    p = PoseStamped()
    p.header.frame_id = "map"
    p.pose.position.x = float(x)
    p.pose.position.y = float(y)
    p.pose.orientation.z = math.sin(yaw / 2.0)
    p.pose.orientation.w = math.cos(yaw / 2.0)
    return p


class Demo(Node):
    def __init__(self):
        super().__init__("demo_ccw_bay")
        from rclpy.parameter import Parameter
        self.set_parameters([Parameter("use_sim_time", Parameter.Type.BOOL, True)])
        self.buf = Buffer()
        TransformListener(self.buf, self)
        self.ac = ActionClient(self, NavigateToPose, f"/{NS}/navigate_to_pose")

    def pos(self):
        try:
            t = self.buf.lookup_transform("map", f"{FRAME}_base_link",
                                          rclpy.time.Time())
            return (t.transform.translation.x, t.transform.translation.y)
        except Exception:
            return None

    def clear_costmaps(self):
        """순간이동 리셋 뒤에는 예전 위치 마킹이 유령으로 남는다."""
        for cm in ("global", "local"):
            cli = self.create_client(
                ClearEntireCostmap,
                f"/{NS}/{cm}_costmap/clear_entirely_{cm}_costmap")
            if cli.wait_for_service(timeout_sec=3.0):
                fut = cli.call_async(ClearEntireCostmap.Request())
                rclpy.spin_until_future_complete(self, fut, timeout_sec=3.0)
        import time
        t0 = time.time()
        while time.time() - t0 < 2.0:
            rclpy.spin_once(self, timeout_sec=0.1)
        print("costmap 청소 완료")

    def run(self):
        import time
        t0 = time.time()
        p = None
        while time.time() - t0 < 8.0 and p is None:
            rclpy.spin_once(self, timeout_sec=0.2)
            p = self.pos()
        if p is None:
            print("차량 TF 를 못 받음 — clock_pub / Nav2 확인")
            return
        print(f"{FRAME} 현재 위치 ({p[0]:.1f}, {p[1]:.1f})")

        self.clear_costmaps()

        if not self.ac.wait_for_server(timeout_sec=5.0):
            print("navigate_to_pose 서버 없음 — Nav2 확인")
            return

        for i, (x, y, yaw, note) in enumerate(ROUTE, 1):
            print(f">>> {i}/{len(ROUTE)} ({x}, {y}) {note}")
            g = NavigateToPose.Goal()
            g.pose = make_pose(x, y, yaw)
            fut = self.ac.send_goal_async(g)
            rclpy.spin_until_future_complete(self, fut)
            gh = fut.result()
            if gh is None or not gh.accepted:
                print("  목표 거부됨 — 중단")
                return
            res = gh.get_result_async()
            rclpy.spin_until_future_complete(self, res)
            st = res.result().status
            here = self.pos()
            loc = f"({here[0]:.1f}, {here[1]:.1f})" if here else "?"
            print(f"  status={st} {'성공' if st == 4 else '실패'}  현재 {loc}")
            if st != 4:
                print("  이 구간에서 실패 — 중단")
                return
        print(">>> 반시계 순환 후 바이 도착 완료")


def main():
    rclpy.init()
    node = Demo()
    try:
        node.run()
    except KeyboardInterrupt:
        pass
    finally:
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
