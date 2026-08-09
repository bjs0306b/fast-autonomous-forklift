#!/usr/bin/env python3
"""Find the steering angle above which the vehicle stops actually moving.

    python3 tools/steer_limit.py               # 20~55도를 훑는다
    python3 tools/steer_limit.py --speed 0.30
    python3 tools/steer_limit.py --angles 30 40 50

⚠️ **엔코더로 재면 안 된다.** 엔코더는 구동륜 회전을 재지 차체 이동을 재지
   않는다. 벽에 막혀 바퀴만 헛돌아도 0.4 m/s 로 찍힌다. 2026-08-09 에 그렇게
   "50도에서 0.397 m/s 로 굴러간다" 고 보고했는데 차는 서 있었다.
   `odom` 도 같은 엔코더에서 나오므로 TF 로도 못 가른다.

   **슬립과 무관한 측정은 라이다뿐이다.** 그래서 이 도구는 명령 전후의
   `/scan` 을 비교해 실제 이동을 낸다. 엔코더도 함께 찍는데, 그건 답이 아니라
   **엔코더와 라이다가 갈리는 지점을 보여 주기 위해서**다 -- 그 위가 헛도는
   구간이고, 거기가 곧 조향 상한이다.

⚠️ **가드가 STOP 을 걸면 듀티가 0 이 되어 "못 간다" 와 구분되지 않는다.**
   그래서 가드 판정을 함께 기록하고, STOP 이 섞인 단계는 측정 실패로 표시한다.
   2026-08-09 에 이걸 안 해서 무효한 표를 두 번 냈다.

⚠️ **앞뒤로 0.6 m 이상 트인 곳에 두고 돌릴 것.** 구석에서는 전 구간이 STOP 이라
   아무것도 못 잰다.

⚠️ **ALIGN 모드로 운전대를 가져온다.** 끝나면 NAV 로 돌려준다. 중간에 죽으면
   `ros2 topic pub -1 /drive/mode std_msgs/String "data: NAV"` 로 되돌릴 것.
"""

import argparse
import json
import math
import sys
import time

from geometry_msgs.msg import Twist, TwistWithCovarianceStamped
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile, qos_profile_sensor_data
from sensor_msgs.msg import LaserScan
from std_msgs.msg import String

WHEELBASE_M = 0.144


class SteerLimit(Node):
    def __init__(self) -> None:
        super().__init__("steer_limit")
        self.scan = None
        self.guard = None
        self.teleop = None
        self.encoder = 0.0
        self.create_subscription(
            LaserScan, "/scan", lambda m: setattr(self, "scan", m),
            qos_profile_sensor_data)
        self.create_subscription(
            String, "/obstacle_avoidance/status",
            lambda m: setattr(self, "guard", m.data), 10)
        self.create_subscription(
            String, "/teleop/command",
            lambda m: setattr(self, "teleop", m.data), 10)
        self.create_subscription(
            TwistWithCovarianceStamped, "/wheel/twist",
            lambda m: setattr(self, "encoder", m.twist.twist.linear.x), 10)
        self._command = self.create_publisher(Twist, "/cmd_vel_align", 10)
        latched = QoSProfile(depth=1)
        latched.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
        self._mode = self.create_publisher(String, "/drive/mode", latched)

    def mode(self, name: str) -> None:
        self._mode.publish(String(data=name))
        self.spin(0.3)

    def spin(self, seconds: float) -> None:
        deadline = time.monotonic() + seconds
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(self, timeout_sec=0.02)

    def action(self) -> str:
        if not self.guard:
            return "-"
        try:
            return json.loads(self.guard)["action"]
        except (json.JSONDecodeError, KeyError):
            return "-"

    def ranges(self):
        """전방·후방 섹터의 최단 거리. 이동을 재는 기준점이다."""
        scan = self.scan
        if scan is None:
            return None
        front, rear = [], []
        for index, value in enumerate(scan.ranges):
            if not math.isfinite(value) or not (
                    scan.range_min < value < scan.range_max):
                continue
            bearing = math.degrees(
                scan.angle_min + index * scan.angle_increment)
            bearing = (bearing + 180.0) % 360.0 - 180.0
            if abs(bearing) <= 12.0:
                front.append(value)
            elif abs(abs(bearing) - 180.0) <= 12.0:
                rear.append(value)
        if not front or not rear:
            return None
        return min(front), min(rear)

    def settle(self, seconds=1.0):
        """명령을 끊고 스캔이 안정될 때까지 기다린 뒤 거리를 읽는다."""
        for _ in range(int(seconds / 0.02)):
            self._command.publish(Twist())
            rclpy.spin_once(self, timeout_sec=0.02)
        return self.ranges()

    def drive(self, speed: float, curvature: float, seconds: float):
        """한 각도를 시험한다. (라이다 이동, 엔코더 최대, 듀티, 서보각, 가드)"""
        before = self.settle()
        peak_encoder = 0.0
        peak_duty = 0
        peak_steer = 0.0
        actions = {}

        command = Twist()
        command.linear.x = speed
        command.angular.z = speed * curvature
        deadline = time.monotonic() + seconds
        while rclpy.ok() and time.monotonic() < deadline:
            self._command.publish(command)
            rclpy.spin_once(self, timeout_sec=0.02)
            actions[self.action()] = actions.get(self.action(), 0) + 1
            peak_encoder = max(peak_encoder, abs(self.encoder))
            if self.teleop:
                report = json.loads(self.teleop)
                peak_duty = max(peak_duty, abs(report["drivePercent"]))
                peak_steer = max(peak_steer, abs(report["rearSteeringDeg"]))

        after = self.settle()
        moved = None
        if before and after:
            # 앞뒤 두 섹터의 변화를 평균한다. 한쪽만 보면 그 방향에 물체가
            # 들어오거나 빠질 때 이동으로 오인한다.
            moved = (abs(after[0] - before[0]) + abs(after[1] - before[1])) / 2.0
        return moved, peak_encoder, peak_duty, peak_steer, actions


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--angles", type=float, nargs="*",
                        default=[20.0, 25.0, 30.0, 35.0, 40.0, 45.0, 50.0, 55.0],
                        help="시험할 후륜 조향각(도)")
    parser.add_argument("--speed", type=float, default=0.30)
    parser.add_argument("--seconds", type=float, default=2.0)
    # 이만큼 움직였으면 "간다" 로 본다. 라이다 잡음(약 2 cm)보다 넉넉히 위.
    parser.add_argument("--moved-m", type=float, default=0.08)
    args = parser.parse_args()

    rclpy.init()
    node = SteerLimit()
    try:
        deadline = time.monotonic() + 20.0
        while time.monotonic() < deadline:
            rclpy.spin_once(node, timeout_sec=0.05)
            if node.scan is not None and node.action() not in ("-",
                                                              "SENSOR_TIMEOUT"):
                break
        if node.scan is None:
            print("/scan 을 못 받았다 -- 스택 확인")
            return 1

        start = node.ranges()
        if start is None:
            print("전방·후방 섹터에 유효한 빔이 없다")
            return 1
        print(f"시작 여유: 앞 {start[0]:.2f} m · 뒤 {start[1]:.2f} m")
        if start[0] < 0.6 or start[1] < 0.6:
            print("⚠️ 앞뒤로 0.6 m 이상 트인 곳에 두고 다시 할 것 -- "
                  "좁으면 전 구간이 STOP 이라 아무것도 못 잰다")

        node.mode("ALIGN")
        print(f"\n속도 {args.speed:.2f} m/s · 각도마다 {args.seconds:.1f}초\n")
        print("  후륜각   듀티   엔코더   라이다이동   가드          판정")
        limit = None
        for angle in args.angles:
            curvature = math.tan(math.radians(angle)) / WHEELBASE_M
            moved, encoder, duty, steer, actions = node.drive(
                args.speed, curvature, args.seconds)
            top = max(actions, key=actions.get) if actions else "-"

            if duty == 0 or "STOP" in actions:
                verdict = "측정불가(가드 STOP)"
            elif moved is None:
                verdict = "측정불가(스캔 없음)"
            elif moved >= args.moved_m:
                verdict = "간다"
                limit = angle
            elif encoder > 0.05:
                verdict = "⚠️ 바퀴만 돈다"
            else:
                verdict = "못 간다"

            shown = f"{moved:.2f} m" if moved is not None else "  -  "
            print(f"  {steer:5.1f}°  {duty:4d}%  {encoder:.3f}   {shown:>8}   "
                  f"{top:12s}  {verdict}")

            # 제자리로 되돌린다 -- 안 그러면 다음 단계가 벽 앞에서 시작한다
            node.drive(-args.speed * 0.8, 0.0, args.seconds * 0.8)

        print()
        if limit is None:
            print("어느 각도에서도 못 움직였다 -- 자리가 좁거나 구동계 문제다")
            return 1
        radius = WHEELBASE_M / math.tan(math.radians(limit))
        print(f"실제로 움직인 최대 조향각: {limit:.1f}°  (회전반경 {radius:.3f} m)")
        print("이 값을 세 곳에 **함께** 넣을 것:")
        print(f"  teleop.yaml   min_command_turning_radius_m      {radius:.2f}")
        print(f"  nav2_params   GridBased.minimum_turning_radius  {radius:.2f}")
        print(f"  nav2_params   FollowPath.regulated_linear_scaling_min_radius"
              f"  {radius:.2f}")
        return 0
    except KeyboardInterrupt:
        return 0
    finally:
        node.settle(0.3)
        node.mode("NAV")
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    sys.exit(main())
