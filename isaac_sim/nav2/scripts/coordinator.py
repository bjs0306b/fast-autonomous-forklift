#!/usr/bin/env python3
"""관제(control tower) 코디네이터 — 짐받는 칸(bay)이 비었을 때만 차를 보낸다.

실시간 라이다 회피 대신, 관제가 차량 위치를 보고 goal 을 조율한다.
  - A(선점 차량)가 bay 에 있으면  -> B 는 대기점에서 멈춤(바이로 안 보냄)
  - A 가 bay 를 떠나면            -> B 에게 bay 로 가라고 명령

이게 실제 물류 관제가 하는 방식이고, 좁은 통로 억지 회피보다 안정적이다.

사용:
    source /opt/ros/humble/setup.bash
    python3 coordinator.py
"""
import math
import rclpy
from rclpy.node import Node
from rclpy.action import ActionClient
from nav2_msgs.action import NavigateToPose
from tf2_ros import Buffer, TransformListener

# ---- 시나리오 좌표 (필요하면 여기만 고치면 됨) ----
BAY = (17.0, 5.0)          # 짐 받는 칸
WAIT = (10.0, 5.0)         # B 가 기다릴 지점 (bay 앞)
OCCUPANT = "SIM_F03"       # bay 를 선점하는 차량 A
ARRIVER = "sim_f02"        # 도착하려는 차량 B (네임스페이스, 소문자)
ARRIVER_FRAME = "SIM_F02"  # B 의 TF 프레임 접두사
BAY_RADIUS = 2.5           # 이 거리 안이면 "bay 점유 중"


class Coordinator(Node):
    def __init__(self):
        super().__init__("coordinator")
        from rclpy.parameter import Parameter
        self.set_parameters([Parameter("use_sim_time", Parameter.Type.BOOL, True)])
        self.buf = Buffer()
        TransformListener(self.buf, self)
        self.ac = ActionClient(self, NavigateToPose, f"/{ARRIVER}/navigate_to_pose")
        self.state = None          # 'waiting' | 'going'
        self.get_logger().info("관제 시작 - bay 점유 감시 중")
        self.create_timer(2.0, self.tick)

    def pos(self, frame):
        try:
            t = self.buf.lookup_transform("map", f"{frame}_base_link",
                                          rclpy.time.Time())
            return (t.transform.translation.x, t.transform.translation.y)
        except Exception:
            return None

    def send(self, xy, tag):
        if not self.ac.wait_for_server(timeout_sec=2.0):
            self.get_logger().warn(f"{ARRIVER} 액션서버 없음")
            return
        g = NavigateToPose.Goal()
        g.pose.header.frame_id = "map"
        g.pose.pose.position.x = float(xy[0])
        g.pose.pose.position.y = float(xy[1])
        g.pose.pose.orientation.w = 1.0
        self.ac.send_goal_async(g)
        self.get_logger().info(f">>> B 를 {tag} {xy} 로 보냄")

    def tick(self):
        a = self.pos(OCCUPANT)
        if a is None:
            self.get_logger().info("A 위치 모름(TF 대기)")
            return
        dist = math.hypot(a[0] - BAY[0], a[1] - BAY[1])
        occupied = dist < BAY_RADIUS

        if occupied and self.state != "waiting":
            self.get_logger().info(
                f"bay 점유됨 (A가 {dist:.1f}m) -> B 대기점으로")
            self.send(WAIT, "대기점")
            self.state = "waiting"
        elif not occupied and self.state != "going":
            self.get_logger().info(
                f"bay 비었음 (A가 {dist:.1f}m) -> B 를 bay 로!")
            self.send(BAY, "bay")
            self.state = "going"


def main():
    rclpy.init()
    node = Coordinator()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
