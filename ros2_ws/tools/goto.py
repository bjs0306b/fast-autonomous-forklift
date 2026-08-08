#!/usr/bin/env python3
"""Drive to an absolute map coordinate, reporting what actually happened.

The demo names places, not offsets: the simulator says where a pallet is and
the vehicle goes there. tools/map_leg.py drives relative to wherever the
vehicle happens to be, which is right for mapping and wrong for this.

    python3 tools/goto.py 0.55 2.60            # map 좌표, 방향은 현재 유지
    python3 tools/goto.py 0.55 2.60 90         # 도착 방향 90도
    python3 tools/goto.py --lap                # 목업 한 바퀴

⚠️ 좌표는 **실물 미터**다. 시뮬 좌표를 그대로 넣으면 목업 밖이 된다 -- 변환은
   sim_task_receiver 가 MQTT 경계에서 한 번만 한다.

각 구간마다 네 가지를 찍는다: 엔코더가 실제로 돌았는지, 가드가 뭐라고 했는지,
탈출 노드가 끼어들었는지, 목표까지 남은 거리. 이 목업에서 주행은 조용히
실패한다 -- 명령만 받고 안 움직이거나, 옆의 것 때문에 멈추거나, 갇힌 채로
시간을 보낸다. 밖에서 보면 셋 다 "가는 중" 으로 보인다.
"""

import argparse
import json
import math
import sys
import time

import rclpy
from geometry_msgs.msg import Twist, TwistWithCovarianceStamped
from nav2_msgs.action import NavigateToPose
from rclpy.action import ActionClient
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile
from std_msgs.msg import String
from tf2_ros import Buffer, TransformListener

# 목업 2 x 3 m, 선반 두 줄(왼쪽 x 0.04~0.15 · 중앙 x 0.97~1.08, 둘 다 y 0.85~2.45).
# 통로 한가운데를 지나도록 잡았다. isaac_sim/nav2/maps 의 지도가 근거다.
LAP = [
    (0.55, 0.45, None),     # 왼쪽 통로 입구
    (0.55, 2.60, None),     # 왼쪽 통로 끝
    (1.50, 2.60, None),     # 위쪽을 가로질러
    (1.50, 0.45, None),     # 오른쪽 통로
    (0.55, 0.45, None),     # 시작점으로 -- 루프를 닫는다
]


class Goto(Node):
    def __init__(self) -> None:
        super().__init__("goto")
        self.d = {"enc": 0.0, "guard": None, "unstick": None}
        self.buffer = Buffer()
        TransformListener(self.buffer, self)
        self.create_subscription(
            TwistWithCovarianceStamped, "/wheel/twist",
            lambda m: self.d.__setitem__("enc", m.twist.twist.linear.x), 10)
        self.create_subscription(
            String, "/obstacle_avoidance/status",
            lambda m: self.d.__setitem__("guard", m.data), 10)
        latched = QoSProfile(depth=1)
        latched.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
        self.create_subscription(
            String, "/drive/unstick_status",
            lambda m: self.d.__setitem__("unstick", m.data), latched)
        self.client = ActionClient(self, NavigateToPose, "navigate_to_pose")

    def wait_ready(self, seconds=25.0):
        deadline = time.monotonic() + seconds
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(self, timeout_sec=0.1)
            # ⚠️ 가드 상태를 기다리면 **영원히 안 온다.** 가드는 /cmd_vel 이
            #    흐를 때만 발행하는데, 그건 주행이 시작된 뒤다. 여기서 기다리면
            #    출발 자체를 못 한다 (2026-08-08, SLAM 랩에서 4구간 전부 무응답).
            #    없는 동안은 주행 로그에 "-" 로 찍히고, 첫 명령과 함께 채워진다.
            if self.pose() is not None:
                return True
        return False

    def pose(self):
        try:
            t = self.buffer.lookup_transform(
                "map", "base_link", rclpy.time.Time())
        except Exception:
            return None
        z, w = t.transform.rotation.z, t.transform.rotation.w
        return (t.transform.translation.x, t.transform.translation.y,
                math.atan2(2 * w * z, 1 - 2 * z * z))

    def drive(self, x, y, yaw_deg, timeout):
        here = self.pose()
        yaw = math.radians(yaw_deg) if yaw_deg is not None else here[2]
        print(f"\n→ ({x:.2f}, {y:.2f}) yaw {math.degrees(yaw):+.0f}°   "
              f"현재 ({here[0]:.2f}, {here[1]:.2f}) "
              f"거리 {math.hypot(x - here[0], y - here[1]):.2f} m")

        goal = NavigateToPose.Goal()
        goal.pose.header.frame_id = "map"
        goal.pose.pose.position.x = float(x)
        goal.pose.pose.position.y = float(y)
        goal.pose.pose.orientation.z = math.sin(yaw / 2)
        goal.pose.pose.orientation.w = math.cos(yaw / 2)

        self.client.wait_for_server(timeout_sec=10.0)
        handle = None
        for _ in range(4):
            for _ in range(20):
                rclpy.spin_once(self, timeout_sec=0.1)
            future = self.client.send_goal_async(goal)
            rclpy.spin_until_future_complete(self, future, timeout_sec=15.0)
            handle = future.result()
            if handle is not None and handle.accepted:
                break
            handle = None
        if handle is None:
            print("   목표 수락 실패")
            return False

        result = handle.get_result_async()
        start = time.monotonic()
        last = -1.0
        seen_unstick = self.d["unstick"]
        while rclpy.ok() and time.monotonic() - start < timeout:
            rclpy.spin_once(self, timeout_sec=0.05)
            now = time.monotonic() - start
            if self.d["unstick"] != seen_unstick:
                seen_unstick = self.d["unstick"]
                print(f"   [탈출] {seen_unstick}")
            if now - last >= 3.0:
                last = now
                p = self.pose()
                action = (json.loads(self.d["guard"])["action"]
                          if self.d["guard"] else "-")
                print(f"   {now:5.1f}s ({p[0]:.2f}, {p[1]:.2f}) "
                      f"남은 {math.hypot(x - p[0], y - p[1]):.2f} m · "
                      f"엔코더 {self.d['enc']:+.3f} · {action}")
            if result.done():
                break

        if result.done():
            codes = {4: "성공", 5: "취소", 6: "중단"}
            verdict = codes.get(result.result().status, result.result().status)
        else:
            handle.cancel_goal_async()
            time.sleep(1.0)
            verdict = "시간 초과"
        p = self.pose()
        left = math.hypot(x - p[0], y - p[1])
        print(f"   {verdict} · 최종 ({p[0]:.2f}, {p[1]:.2f}) · 오차 {left:.2f} m")
        return left < 0.30


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("x", nargs="?", type=float)
    parser.add_argument("y", nargs="?", type=float)
    parser.add_argument("yaw_deg", nargs="?", type=float, default=None)
    parser.add_argument("--lap", action="store_true", help="목업 한 바퀴")
    parser.add_argument("--timeout", type=float, default=60.0)
    args = parser.parse_args()

    rclpy.init()
    node = Goto()
    if not node.wait_ready():
        print("가드나 map->base_link 를 못 받았다 -- 스택 확인")
        node.destroy_node()
        rclpy.shutdown()
        return 1

    points = LAP if args.lap else [(args.x, args.y, args.yaw_deg)]
    if points[0][0] is None:
        print("좌표를 주거나 --lap 을 쓸 것")
        node.destroy_node()
        rclpy.shutdown()
        return 2

    reached = 0
    for index, (x, y, yaw) in enumerate(points, 1):
        print(f"\n===== 구간 {index}/{len(points)} =====")
        reached += node.drive(x, y, yaw, args.timeout)
    print(f"\n도달 {reached}/{len(points)}")
    node.destroy_node()
    rclpy.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
