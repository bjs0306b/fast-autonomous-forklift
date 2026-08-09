#!/usr/bin/env python3
"""단독 데모 — 한 대가 바이에서 적재하고 랙에 내려놓고 돌아온다.

    (15.5,4) 출발
      → 바이(17,5)          적재 (측정 높이로 화물 생성)
      → 바이 탈출 (15.5,4)
      → 오른쪽 통로 (15.5,27)
      → 위 홀 (5,27)
      → 랙 A1 접근 (5,9.4)   여기까지 Nav2
      → 랙 A1 선반 적재       여기부터 차량 스텝 머신 (도킹·놓기·후진)
      → 아래 홀 (5,4)
      → 바이(17,5) 복귀

경유점은 전부 맵에서 3.8x1.9 차체가 가로/세로 양방향으로 통과 가능한지
확인한 좌표다. 한 대만 쓰므로 교통 규칙(앞차 대기)은 필요 없다.

이 노드는 use_sim_time 을 쓰지 않는다. Isaac 이 잠깐 버벅여 /clock 이 끊겨도
스크립트가 같이 멈추지 않게 하기 위해서다. TF 는 '최신값' 으로 조회하므로
시뮬 시간과 무관하게 동작한다.

준비:
    Isaac 은 Play, 터미널은  run_all.sh --solo --no-mqtt
    (--solo 면 F03 을 costmap 에 넣지 않아 통로를 막지 않는다)

사용:
    source /opt/ros/humble/setup.bash
    python3 demo_solo.py
    python3 demo_solo.py 0.25          # 화물 높이(실물 m) 지정
"""
import sys
import math
import json
import time

import rclpy
from rclpy.node import Node
from rclpy.action import ActionClient
from nav2_msgs.action import NavigateToPose
from nav2_msgs.srv import ClearEntireCostmap
from geometry_msgs.msg import PoseStamped
from std_msgs.msg import String, Float32
from tf2_ros import Buffer, TransformListener

NS = "sim_f02"
FRAME = "SIM_F02"

E, W = 0.0, math.pi                  # 동(+x), 서(-x)
N, S = math.pi / 2, -math.pi / 2     # 북(+y), 남(-y)

WORK_SEC = 5.0        # 적재에 두는 시간
RACK_SEC = 25.0       # 랙 적재(정렬·도킹·놓기·후진)에 두는 시간


def steps(height):
    """(종류, …) 목록.  go = Nav2 주행,  cargo = 화물 지시."""
    return [
        ("go", 16.5, 5.0, E, "입고 바이 진입"),
        ("cargo", {"height": height}, f"적재 (높이 {height} m)"),
        # 바이는 막다른 곳이다. 오른쪽 벽이 x≈19.5 라 3.8 m 짜리 후륜조향 차가
        # 그 안에서 방향을 바꿀 수 없다. 곧장 먼 목표를 주면 경로를 못 만든다.
        # 먼저 순환로 위 가까운 지점으로 빠져나온다.
        ("go", 15.5, 4.0, N, "바이 탈출 → 순환로 복귀"),
        ("go", 15.5, 27.0, W, "오른쪽 통로 → 위 홀"),
        ("go", 5.0, 27.0, S, "위 홀 → 왼쪽 통로"),
        # 랙 A1 앞 통로. 여기까지만 Nav2 가 데려온다.
        ("go", 5.0, 9.4, W, "랙 A1 접근"),
        # 여기서부터는 Nav2 를 쓰지 않는다. 포크를 랙 안에 넣어야 하는데 그
        # 자리는 footprint 가 랙과 겹쳐 Nav2 가 경로를 못 만들기 때문이다.
        # 차량 스텝 머신이 정렬 → 포크 상승 → 전진 → 놓기 → 하강 → 후진 한다.
        ("cargo", {"action": "place_rack", "rack": "A1"}, "랙 A1 선반에 적재"),
        ("go", 5.0, 4.0, E, "왼쪽 통로 → 아래 홀"),
        ("go", 16.5, 5.0, E, "바이 복귀"),
    ]


def make_pose(x, y, yaw):
    p = PoseStamped()
    p.header.frame_id = "map"
    p.pose.position.x = float(x)
    p.pose.position.y = float(y)
    p.pose.orientation.z = math.sin(yaw / 2.0)
    p.pose.orientation.w = math.cos(yaw / 2.0)
    return p


class Solo(Node):
    def __init__(self, height):
        super().__init__("demo_solo")
        self.height = height
        self.buf = Buffer()
        TransformListener(self.buf, self)
        self.ac = ActionClient(self, NavigateToPose, f"/{NS}/navigate_to_pose")
        self.cargo_pub = self.create_publisher(String, f"/{NS}/cargo_cmd", 10)
        self.fork_pub = self.create_publisher(Float32, f"/{NS}/fork_cmd", 10)

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
        self.sleep(1.5)
        print("costmap 청소 완료")

    def sleep(self, sec):
        """스핀하면서 기다린다 (콜백이 계속 돌게)."""
        t0 = time.time()
        while rclpy.ok() and time.time() - t0 < sec:
            rclpy.spin_once(self, timeout_sec=0.1)

    def run(self):
        t0 = time.time()
        p = None
        while rclpy.ok() and time.time() - t0 < 8.0 and p is None:
            rclpy.spin_once(self, timeout_sec=0.2)
            p = self.pos()
        if p is None:
            print("차량 TF 를 못 받음 — Isaac Play / run_all.sh 확인")
            return
        print(f"{FRAME} 현재 위치 ({p[0]:.1f}, {p[1]:.1f})")

        self.clear_costmaps()
        if not self.ac.wait_for_server(timeout_sec=5.0):
            print("navigate_to_pose 서버 없음 — Nav2 확인")
            return

        plan = steps(self.height)
        for i, st in enumerate(plan, 1):
            if not rclpy.ok():
                return
            if st[0] == "cargo":
                _, payload, note = st
                print(f">>> {i}/{len(plan)} {note}")
                self.cargo_pub.publish(String(data=json.dumps(payload)))
                if payload.get("action") == "drop":
                    self.fork_pub.publish(Float32(data=0.0))
                # 랙 적재는 차량이 실제로 움직이는 동작(도킹·후진)이라 오래 걸린다
                wait = RACK_SEC if payload.get("action") == "place_rack" else WORK_SEC
                self.sleep(wait)
                here = self.pos()
                print(f"    완료  현재 ({here[0]:.1f}, {here[1]:.1f})"
                      if here else "    완료")
                continue

            _, x, y, yaw, note = st
            print(f">>> {i}/{len(plan)} ({x}, {y}) {note}")
            goal = NavigateToPose.Goal()
            goal.pose = make_pose(x, y, yaw)
            fut = self.ac.send_goal_async(goal)
            rclpy.spin_until_future_complete(self, fut)
            gh = fut.result()
            if gh is None or not gh.accepted:
                print("    목표 거부됨 — 중단")
                return
            res = gh.get_result_async()
            rclpy.spin_until_future_complete(self, res)
            status = res.result().status
            here = self.pos()
            loc = f"({here[0]:.1f}, {here[1]:.1f})" if here else "?"
            print(f"    status={status} {'성공' if status == 4 else '실패'}  현재 {loc}")
            if status != 4:
                print("    이 구간에서 실패 — 중단")
                return

        print(f">>> 단독 데모 완료 ({time.time() - t0:.0f}초)")


def main():
    height = float(sys.argv[1]) if len(sys.argv) > 1 else 0.15
    rclpy.init()
    node = Solo(height)
    try:
        node.run()
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
