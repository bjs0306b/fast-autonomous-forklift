#!/usr/bin/env python3
"""Drive one short leg of a mapping run and report what it bought.

Mapping in this mockup fails quietly in ways a goal result does not show. The
vehicle can sit commanded but stationary, the guard can hold it for something
beside it rather than ahead, slam_toolbox can start dropping scans under load
and stop extending the map at all. Each of those looks like "still working"
from the outside, so every leg prints the four numbers that tell them apart:
how far the wheels actually turned, what the guard decided, how much map came
back, and whether scans are being dropped.

    python3 tools/map_leg.py 0.6 0.0        # 앞으로 0.6 m
    python3 tools/map_leg.py 0.35 0.4       # 앞 0.35 · 좌 0.4

⚠️ 매핑 중에는 설정을 바꾸지 말 것. 스택을 다시 띄우면 slam_toolbox 가 지도를
   처음부터 다시 그린다 -- 한 번에 끝내야 한다.

⚠️ RViz 를 같이 띄우지 말 것. 코어 하나를 다 쓰고, 그 부하로 스캔이 버려지면
   map->odom 이 끊긴다.
"""
import json
import math
import subprocess
import sys
import time

import rclpy
from geometry_msgs.msg import Twist, TwistWithCovarianceStamped
from nav2_msgs.action import NavigateToPose
from nav_msgs.msg import OccupancyGrid
from rclpy.action import ActionClient
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile, QoSReliabilityPolicy
from std_msgs.msg import String
from tf2_ros import Buffer, TransformListener

FORWARD = float(sys.argv[1])
LATERAL = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
TIMEOUT = float(sys.argv[3]) if len(sys.argv) > 3 else 60.0
LOG = "/tmp/claude-1000/-home-orin/d49195e9-3e02-4923-bdb4-6f37fc0ef388/scratchpad/nav2.log"


def dropped_scans():
    try:
        out = subprocess.run(["grep", "-ca", "queue is full", LOG],
                             capture_output=True, text=True)
        return int(out.stdout.strip() or 0)
    except Exception:
        return -1


rclpy.init()
n = Node("map_leg")
buf = Buffer()
TransformListener(buf, n)
d = {"enc": 0.0, "safe": None, "st": None, "known": 0}
n.create_subscription(TwistWithCovarianceStamped, "/wheel/twist",
                      lambda m: d.__setitem__("enc", m.twist.twist.linear.x), 10)
n.create_subscription(Twist, "/cmd_vel_safe", lambda m: d.__setitem__("safe", m), 10)
n.create_subscription(String, "/obstacle_avoidance/status",
                      lambda m: d.__setitem__("st", m.data), 10)
q = QoSProfile(depth=1)
q.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
q.reliability = QoSReliabilityPolicy.RELIABLE
n.create_subscription(
    OccupancyGrid, "/map",
    lambda m: d.__setitem__("known", sum(1 for v in m.data if v >= 0)), q)

# Discovery here is slow and uneven; a script that starts before its
# subscriptions match records silence and reads it as a dead node.
deadline = time.monotonic() + 25.0
while rclpy.ok() and time.monotonic() < deadline:
    rclpy.spin_once(n, timeout_sec=0.1)
    if d["safe"] is not None and d["known"]:
        break
if d["safe"] is None:
    print("cmd_vel_safe 미연결 — 스택 확인 필요")
    sys.exit(1)

start_pose = None
deadline = time.monotonic() + 20.0
while rclpy.ok() and time.monotonic() < deadline and start_pose is None:
    rclpy.spin_once(n, timeout_sec=0.1)
    try:
        start_pose = buf.lookup_transform("map", "base_link", rclpy.time.Time())
    except Exception:
        start_pose = None
if start_pose is None:
    print("map -> base_link 없음 — SLAM 이 아직 첫 스캔을 못 맞췄다")
    sys.exit(1)

sx = start_pose.transform.translation.x
sy = start_pose.transform.translation.y
qz = start_pose.transform.rotation.z
qw = start_pose.transform.rotation.w
yaw = math.atan2(2 * qw * qz, 1 - 2 * qz * qz)
gx = sx + FORWARD * math.cos(yaw) - LATERAL * math.sin(yaw)
gy = sy + FORWARD * math.sin(yaw) + LATERAL * math.cos(yaw)
map0 = d["known"]
drops0 = dropped_scans()
print(f"시작 ({sx:+.3f},{sy:+.3f}) → 앞 {FORWARD} 좌 {LATERAL}"
      f" · 지도 {map0}셀 · 스캔버림 {drops0}\n")

client = ActionClient(n, NavigateToPose, "navigate_to_pose")
client.wait_for_server(timeout_sec=10.0)
goal = NavigateToPose.Goal()
goal.pose.header.frame_id = "map"
goal.pose.pose.position.x = gx
goal.pose.pose.position.y = gy
goal.pose.pose.orientation.z = math.sin(yaw / 2)
goal.pose.pose.orientation.w = math.cos(yaw / 2)

handle = None
for _ in range(4):
    for _ in range(20):
        rclpy.spin_once(n, timeout_sec=0.1)
    future = client.send_goal_async(goal)
    rclpy.spin_until_future_complete(n, future, timeout_sec=15.0)
    handle = future.result()
    if handle is not None and handle.accepted:
        break
    handle = None
if handle is None:
    print("목표 수락 실패")
    sys.exit(1)

result = handle.get_result_async()
begin = time.monotonic()
last = -1.0
peak = 0.0
while rclpy.ok() and time.monotonic() - begin < TIMEOUT:
    rclpy.spin_once(n, timeout_sec=0.05)
    peak = max(peak, abs(d["enc"]))
    now = time.monotonic() - begin
    if now - last >= 2.0:
        last = now
        try:
            t = buf.lookup_transform("map", "base_link", rclpy.time.Time())
            moved = math.hypot(t.transform.translation.x - sx,
                               t.transform.translation.y - sy)
        except Exception:
            moved = float("nan")
        s = d["safe"]
        act = json.loads(d["st"])["action"] if d["st"] else "-"
        print(f"  {now:5.1f}s 이동 {moved:.3f} 엔코더 {d['enc']:+.3f}"
              f" | cmd {s.linear.x:+.3f}/{s.angular.z:+.3f} | {act:<14}"
              f" 지도 {d['known']}")
    if result.done():
        break

if result.done():
    codes = {4: "성공", 5: "취소", 6: "중단"}
    verdict = codes.get(result.result().status, result.result().status)
else:
    handle.cancel_goal_async()
    time.sleep(1.0)
    verdict = "시간 초과 — 취소"

for _ in range(40):
    rclpy.spin_once(n, timeout_sec=0.05)
drops = dropped_scans()
print(f"\n{verdict}")
print(f"지도 {map0} → {d['known']}셀 ({(d['known'] - map0):+d})"
      f" · 최고 실측속도 {peak:.3f} m/s"
      f" · 스캔버림 {drops0} → {drops} ({drops - drops0:+d})")
if drops - drops0 > 20:
    print("⚠️ 스캔이 많이 버려졌다 — 부하를 줄이지 않으면 지도가 성기게 된다")
n.destroy_node()
rclpy.shutdown()
