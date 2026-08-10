#!/usr/bin/env python3
"""Reverse straight until there is room ahead again.

The guard stops for a wall ahead and then nothing moves: with the controller
producing no command the progress checker never runs, so nav2's own recovery
never fires. Backing out is the one thing that works, and straight reverse is
the one direction this vehicle can always start in.

    python3 tools/back_off.py                  # 0.6 m 확보될 때까지, 최대 4초
    python3 tools/back_off.py 0.8 6            # 0.8 m 확보 · 최대 6초
    python3 tools/back_off.py 0.8 6 left       # 물러난 뒤 좌측을 향하게

⚠️ **후진 중 조향은 전진과 반대다.** 요각속도는 v·곡률이고 후진은 v 가 음수라
   부호가 뒤집힌다 -- 가려는 쪽으로 꺾은 채 후진하면 오히려 반대로 돈다. 여기서는
   원하는 방향을 angular_z 로 그대로 주고, map_twist 가 부호가 있는 linear_x 로
   곡률을 내므로 조향은 알아서 반대로 잡힌다.

⚠️ **꺾인 채로는 정지에서 못 뜬다.** 그래서 먼저 곧게 후진해 굴러가게 만든 뒤
   조향을 준다. 사람이 차를 빼는 순서와 같다.

⚠️ 후방은 라이다만 본다. ToF 는 전방 전용이라 **뒤의 낮은 물체는 아무도 못
   본다.** rear_stop_distance_m 가 막아 주지만 그것도 라이다가 본 것에 한한다.
"""
import json
import math
import sys
import time

import rclpy
from geometry_msgs.msg import Twist, TwistWithCovarianceStamped
from rclpy.node import Node
from std_msgs.msg import String

WANT = float(sys.argv[1]) if len(sys.argv) > 1 else 0.6
LIMIT = float(sys.argv[2]) if len(sys.argv) > 2 else 4.0
TURN = sys.argv[3].lower() if len(sys.argv) > 3 else ""
SPEED = 0.12
YAW = 0.30
ROLLING = 0.02

rclpy.init()
n = Node("back_off")
pub = n.create_publisher(Twist, "/cmd_vel", 10)
d = {"enc": 0.0, "st": None, "safe": None}
n.create_subscription(TwistWithCovarianceStamped, "/wheel/twist",
                      lambda m: d.__setitem__("enc", m.twist.twist.linear.x), 10)
n.create_subscription(String, "/obstacle_avoidance/status",
                      lambda m: d.__setitem__("st", m.data), 10)
n.create_subscription(Twist, "/cmd_vel_safe", lambda m: d.__setitem__("safe", m), 10)

deadline = time.monotonic() + 20.0
while rclpy.ok() and time.monotonic() < deadline and d["st"] is None:
    pub.publish(Twist())
    rclpy.spin_once(n, timeout_sec=0.1)
if d["st"] is None:
    print("가드 상태 미수신")
    sys.exit(1)


def ahead():
    s = json.loads(d["st"])
    values = [v for v in (s["roofLidar"].get("center"),
                          s["frontTof"].get("path")) if v is not None]
    return min(values) if values else math.inf


def rear():
    return json.loads(d["st"])["roofLidar"].get("rear") or math.inf


print(f"전방 {ahead():.2f} → {WANT:.2f} m 확보 · 후방 {rear():.2f}\n")
command = Twist()
command.linear.x = -SPEED
start = time.monotonic()
travel = 0.0
prev = start
turning = False
while rclpy.ok() and time.monotonic() - start < LIMIT:
    # 곧게 떼어 낸 뒤에만 꺾는다. 정지 상태에서 꺾인 채로는 안 뜬다.
    if TURN and not turning and abs(d["enc"]) > ROLLING:
        turning = True
        command.angular.z = YAW if TURN.startswith("l") else -YAW
        print(f"  굴러가기 시작 -- {'좌' if TURN.startswith('l') else '우'}측을"
              f" 향하도록 조향")
    pub.publish(command)
    rclpy.spin_once(n, timeout_sec=0.02)
    now = time.monotonic()
    travel += abs(d["enc"]) * (now - prev)
    prev = now
    if ahead() >= WANT:
        break

command.linear.x = 0.0
command.angular.z = 0.0
for _ in range(40):
    pub.publish(command)
    rclpy.spin_once(n, timeout_sec=0.02)

status = json.loads(d["st"])
print(f"후진 {travel:.3f} m · 전방 {ahead():.2f} · 후방 {rear():.2f}"
      f" · 판정 {status['action']}")
print("확보됨" if ahead() >= WANT else "⚠️ 부족 — 뒤도 막혔거나 못 움직였다")
n.destroy_node()
rclpy.shutdown()
