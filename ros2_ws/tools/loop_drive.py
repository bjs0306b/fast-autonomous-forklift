#!/usr/bin/env python3
"""Drive a big loop from wherever the vehicle is, letting the guard steer.

    python3 tools/loop_drive.py                 # 반경 0.70 m, 좌회전 한 바퀴
    python3 tools/loop_drive.py --radius 0.9
    python3 tools/loop_drive.py --right --laps 2

지도도 목표도 쓰지 않는다. 일정 곡률로 계속 돌면서, 장애물은
`obstacle_avoidance` 가 붙이는 편향과 감속으로 피한다. nav2 가 경로를 못 내는
상황(지도 밖 목표·인플레이션으로 막힌 좁은 목업)에서도 "실제로 도는가" 를
볼 수 있는 것이 이 도구의 쓸모다.

⚠️ **ALIGN 모드로 운전대를 가져온다.** 그래야 nav2 명령과 섞이지 않는다.
   끝나면 NAV 로 돌려준다. 중간에 죽으면 ALIGN 인 채 남으므로
   `ros2 topic pub -1 /drive/mode std_msgs/String "data: NAV"` 로 되돌릴 것.

⚠️ **ALIGN 인 동안 자동 후진(unstick_node)은 나서지 않는다** -- 파렛 진입을
   방해하지 않으려고 그렇게 만들어 두었다. 그래서 막혔을 때 빼내는 일은 여기서
   직접 한다: 가드가 STOP 을 걸면 조향을 **반대로** 두고 후진한다. 가려는 쪽과
   반대로 꺾어 물러나야 다시 앞으로 갈 때 방향이 맞는다.

한 바퀴의 판정은 **회전각 누적**이다. 좌표가 아니라 yaw 를 적분하므로, 바퀴가
헛돌아 위치 추정이 틀어져도 "돌았다" 는 판정 자체는 오염되지 않는다.
"""

import argparse
import json
import math
import sys
import time

from geometry_msgs.msg import Twist, TwistWithCovarianceStamped
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile
from std_msgs.msg import String
from tf2_ros import Buffer, TransformListener


class LoopDrive(Node):
    def __init__(self) -> None:
        super().__init__("loop_drive")
        self.guard = None
        self.encoder = 0.0
        self._command = self.create_publisher(Twist, "/cmd_vel_align", 10)
        latched = QoSProfile(depth=1)
        latched.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
        self._mode = self.create_publisher(String, "/drive/mode", latched)
        self.create_subscription(
            String, "/obstacle_avoidance/status",
            lambda m: setattr(self, "guard", m.data), 10)
        self.create_subscription(
            TwistWithCovarianceStamped, "/wheel/twist",
            lambda m: setattr(self, "encoder", m.twist.twist.linear.x), 10)
        self.buffer = Buffer()
        TransformListener(self.buffer, self)

    def mode(self, name: str) -> None:
        self._mode.publish(String(data=name))
        for _ in range(10):
            rclpy.spin_once(self, timeout_sec=0.05)

    def action(self) -> str:
        if not self.guard:
            return "-"
        try:
            return json.loads(self.guard)["action"]
        except (json.JSONDecodeError, KeyError):
            return "-"

    def yaw(self):
        try:
            t = self.buffer.lookup_transform(
                "odom", "base_link", rclpy.time.Time())
        except Exception:
            return None
        z, w = t.transform.rotation.z, t.transform.rotation.w
        return math.atan2(2 * w * z, 1 - 2 * z * z)

    def send(self, linear: float, angular: float) -> None:
        command = Twist()
        command.linear.x = linear
        command.angular.z = angular
        self._command.publish(command)

    def stop(self) -> None:
        for _ in range(6):
            self._command.publish(Twist())
            rclpy.spin_once(self, timeout_sec=0.05)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--radius", type=float, default=0.70,
                        help="선회 반경 m (작을수록 깊게 꺾는다)")
    parser.add_argument("--speed", type=float, default=0.30)
    parser.add_argument("--right", action="store_true", help="우회전")
    parser.add_argument("--laps", type=float, default=1.0)
    parser.add_argument("--timeout", type=float, default=180.0)
    # 가드가 이만큼 연달아 STOP 을 걸면 갇힌 것으로 보고 직접 물러난다.
    parser.add_argument("--stuck-sec", type=float, default=2.0)
    args = parser.parse_args()

    sign = -1.0 if args.right else 1.0
    turn_rate = sign * args.speed / args.radius
    target = args.laps * 2 * math.pi

    rclpy.init()
    node = LoopDrive()
    # TF 버퍼가 채워질 때까지. 2초로 두었더니 스택이 멀쩡한데도 "못 받았다" 로
    # 끝났다 -- 붙는 데 그보다 오래 걸린다.
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline and node.yaw() is None:
        rclpy.spin_once(node, timeout_sec=0.05)
    if node.yaw() is None:
        print("odom->base_link 를 못 받았다 -- 스택 확인")
        node.destroy_node()
        rclpy.shutdown()
        return 1

    node.mode("ALIGN")
    print(f"반경 {args.radius:.2f} m · 속도 {args.speed:.2f} m/s · "
          f"{'우' if args.right else '좌'}회전 {args.laps:.1f}바퀴 "
          f"(각속도 {abs(turn_rate):.2f} rad/s)")

    turned = 0.0
    last = node.yaw()
    blocked_since = None
    backing_until = None
    escapes = 0
    start = time.monotonic()
    printed = -1.0

    try:
        while rclpy.ok() and time.monotonic() - start < args.timeout:
            rclpy.spin_once(node, timeout_sec=0.05)
            now = time.monotonic()
            current = node.yaw()
            if current is None:
                continue
            step = (current - last + math.pi) % (2 * math.pi) - math.pi
            turned += step
            last = current

            action = node.action()
            if backing_until is not None:
                if now < backing_until and action != "STOP_REAR":
                    # ⚠️ **조향은 중립이다.** 처음에는 "가려는 쪽과 반대로 꺾고
                    #    물러나면 방향이 맞아진다" 로 짰는데, 이 차는 꺾인 채로는
                    #    정지에서 못 뜬다(실측 100% 듀티까지 확인). 2026-08-08 에
                    #    그렇게 두었더니 35번 물러나려다 엔코더가 0 으로 굳었다.
                    #    방향은 물러난 뒤 앞으로 가며 맞춘다.
                    node.send(-0.15, 0.0)
                    continue
                backing_until = None
                blocked_since = None
                node.send(0.0, 0.0)

            if action == "STOP":
                if blocked_since is None:
                    blocked_since = now
                elif now - blocked_since >= args.stuck_sec:
                    escapes += 1
                    # 2.0 초로는 못 뺀다. 후진 시동 킥은 조향이 중립으로
                    # **자리잡은 뒤에야** 듀티를 올리므로, 그 대기시간을 빼면
                    # 실제로 미는 시간이 얼마 안 남는다 (2026-08-08: 2초 후진을
                    # 29번 반복하고도 엔코더가 0 이었다).
                    backing_until = now + 5.0
                    print(f"  [막힘] {escapes}번째 -- 반대로 꺾고 후진")
                    continue
                node.send(0.0, 0.0)
                continue

            blocked_since = None
            node.send(args.speed, turn_rate)

            if now - start - printed >= 4.0:
                printed = now - start
                print(f"  {printed:5.1f}s  누적 회전 {math.degrees(turned):+7.1f}° "
                      f"/ {math.degrees(target):.0f}° · 엔코더 {node.encoder:+.3f} "
                      f"· {action}")

            if abs(turned) >= target:
                print(f"\n한 바퀴 완료 · 누적 {math.degrees(turned):+.1f}° · "
                      f"{now - start:.1f}초 · 막힘 탈출 {escapes}회")
                return 0

        print(f"\n시간 초과 · 누적 {math.degrees(turned):+.1f}° "
              f"/ {math.degrees(target):.0f}° · 막힘 탈출 {escapes}회")
        return 1
    except KeyboardInterrupt:
        return 0
    finally:
        node.stop()
        node.mode("NAV")
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    sys.exit(main())
