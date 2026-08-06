#!/usr/bin/env python3
"""관제가 우회 경로를 지시한다 — 앞 차량을 경유점으로 돌아간다.

Nav2 가 장애물 바로 옆에서 스스로 곡예하게 두면(좁은 통로, 3.8 m 차체)
실패하기 쉽다. 대신 관제가 '옆으로 비켜서 지나가는 경유점'을 계산해 주고
Nav2 는 그 넓은 길을 평범하게 주행한다. 실제 물류 관제 방식이고 재현성이 높다.

    현재 위치 ──▶ P1(옆으로 비킴) ──▶ P2(앞 차량 통과) ──▶ 목표

사용:
    source /opt/ros/humble/setup.bash
    python3 detour.py                 # F02 를 17,5 로 (F03 을 우회)
    python3 detour.py 17 5            # 목표 지정
"""
import sys
import math
import rclpy
from rclpy.node import Node
from rclpy.action import ActionClient
from nav2_msgs.action import NavigateToPose
from geometry_msgs.msg import PoseStamped
from tf2_ros import Buffer, TransformListener

MOVER = ("sim_f02", "SIM_F02")     # 움직일 차량 (네임스페이스, 프레임)
BLOCKER = "SIM_F03"                # 앞을 막고 있는 차량
SIDE_OFFSET = 3.0                  # 옆으로 비키는 거리 (m)
PASS_MARGIN = 4.0                  # 앞 차량 앞뒤로 확보할 여유 (m)
# 맵에서 실제로 3.8x1.9 차체가 다닐 수 있는 구간은 y 2.0~7.0 (x 3~17) 뿐이다.
# y 7 위쪽은 랙이라 x 13.5~17 좁은 통로만 열려 있다. 우회로를 이 밖으로 잡으면
# 경로가 없어서 무조건 실패한다.
Y_LIMIT = (2.2, 6.8)


def yaw_to_quat(yaw):
    return (0.0, 0.0, math.sin(yaw / 2.0), math.cos(yaw / 2.0))


def pose(x, y, yaw=0.0):
    p = PoseStamped()
    p.header.frame_id = "map"
    p.pose.position.x = float(x)
    p.pose.position.y = float(y)
    qx, qy, qz, qw = yaw_to_quat(yaw)
    p.pose.orientation.z = qz
    p.pose.orientation.w = qw
    return p


class Detour(Node):
    def __init__(self, gx, gy):
        super().__init__("detour")
        from rclpy.parameter import Parameter
        self.set_parameters([Parameter("use_sim_time", Parameter.Type.BOOL, True)])
        self.gx, self.gy = gx, gy
        self.buf = Buffer()
        TransformListener(self.buf, self)
        self.ac = ActionClient(self, NavigateToPose,
                               f"/{MOVER[0]}/navigate_to_pose")

    def clear_costmaps(self):
        """유령 마킹 제거. 순간이동 리셋 뒤에는 반드시 필요하다."""
        from nav2_msgs.srv import ClearEntireCostmap
        ns = MOVER[0]
        for cm in ("global", "local"):
            srv = f"/{ns}/{cm}_costmap/clear_entirely_{cm}_costmap"
            cli = self.create_client(ClearEntireCostmap, srv)
            if cli.wait_for_service(timeout_sec=3.0):
                fut = cli.call_async(ClearEntireCostmap.Request())
                rclpy.spin_until_future_complete(self, fut, timeout_sec=3.0)
                print(f"  costmap 청소: {cm}")
            else:
                print(f"  costmap 청소 실패(서비스 없음): {cm}")
        import time
        t0 = time.time()
        while time.time() - t0 < 2.0:      # 새 관측이 다시 쌓일 시간
            rclpy.spin_once(self, timeout_sec=0.1)

    def pos(self, frame):
        try:
            t = self.buf.lookup_transform("map", f"{frame}_base_link",
                                          rclpy.time.Time())
            return (t.transform.translation.x, t.transform.translation.y)
        except Exception:
            return None

    def run(self):
        import time
        t0 = time.time()
        me = other = None
        while time.time() - t0 < 8.0:
            rclpy.spin_once(self, timeout_sec=0.2)
            me = self.pos(MOVER[1])
            other = self.pos(BLOCKER)
            if me and other:
                break
        if not me or not other:
            print("차량 TF 를 못 받음 (clock / Nav2 확인)")
            return

        print(f"{MOVER[1]} {me[0]:.1f},{me[1]:.1f}  "
              f"막는 차 {BLOCKER} {other[0]:.1f},{other[1]:.1f}  "
              f"목표 {self.gx},{self.gy}")

        # 진행 방향 (+x 인지 -x 인지)
        forward = 1.0 if self.gx >= me[0] else -1.0

        # 비킬 방향: 맵에서 여유가 큰 쪽
        up = min(other[1] + SIDE_OFFSET, Y_LIMIT[1])
        down = max(other[1] - SIDE_OFFSET, Y_LIMIT[0])
        side_y = up if (Y_LIMIT[1] - other[1]) >= (other[1] - Y_LIMIT[0]) else down
        print(f"우회 방향: y={side_y:.1f} 로 비킴")

        # 경유점: 막는 차 앞에서 비키고, 지나친 뒤 목표로
        p1 = pose(other[0] - forward * PASS_MARGIN, side_y, 0.0 if forward > 0 else math.pi)
        p2 = pose(other[0] + forward * PASS_MARGIN, side_y, 0.0 if forward > 0 else math.pi)
        p3 = pose(self.gx, self.gy, 0.0 if forward > 0 else math.pi)

        for i, p in enumerate([p1, p2, p3], 1):
            print(f"  경유{i}: ({p.pose.position.x:.1f}, {p.pose.position.y:.1f})")

        # 순간이동 리셋을 반복하면 costmap 에 예전 위치의 마킹이 유령으로 남아
        # 차량이 어디 있든 '충돌'로 판정된다. 보내기 전에 지운다.
        self.clear_costmaps()

        if not self.ac.wait_for_server(timeout_sec=5.0):
            print("navigate_to_pose 서버 없음 (Nav2 확인)")
            return

        # navigate_through_poses 는 쓰지 않는다. 그 BT 의 RemovePassedGoals 노드가
        # robot_base_frame 을 네임스페이스 없이 "base_link" 로 조회해서
        # "No Transform available ... base_link" 로 통째로 실패한다.
        # 경유점을 하나씩 navigate_to_pose 로 보내면 그 노드를 타지 않는다.
        for i, p in enumerate([p1, p2, p3], 1):
            print(f">>> 경유{i} ({p.pose.position.x:.1f}, "
                  f"{p.pose.position.y:.1f}) 로 주행")
            goal = NavigateToPose.Goal()
            goal.pose = p
            fut = self.ac.send_goal_async(goal)
            rclpy.spin_until_future_complete(self, fut)
            gh = fut.result()
            if gh is None or not gh.accepted:
                print("  목표 거부됨 - 중단")
                return
            res = gh.get_result_async()
            rclpy.spin_until_future_complete(self, res)
            status = res.result().status
            print(f"  status={status} " + ("(성공)" if status == 4 else "(실패)"))
            if status != 4:
                print("  이 구간에서 실패 - 중단")
                return
        print(">>> 우회 완료")


def main():
    gx = float(sys.argv[1]) if len(sys.argv) > 1 else 17.0
    gy = float(sys.argv[2]) if len(sys.argv) > 2 else 5.0
    rclpy.init()
    node = Detour(gx, gy)
    try:
        node.run()
    except KeyboardInterrupt:
        pass
    finally:
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
