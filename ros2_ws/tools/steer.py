#!/usr/bin/env python3
"""Turn the steering servo from a ROS topic, and show what actually went out.

    python3 tools/steer.py 30              # 후륜 30도 (좌), 3초
    python3 tools/steer.py -- -45          # 우측 45도 (음수는 -- 뒤에)
    python3 tools/steer.py --sweep         # -58 -> +58 -> -58 왕복
    python3 tools/steer.py --watch         # 아무것도 안 보내고 보기만

⚠️ **조향은 곡률이지 각도가 아니다.** 브리지가 받는 것은 Twist 뿐이고,
   후륜각은 `atan(wheelbase · angular.z / linear.x)` 로 나온다. 그래서 각도만
   지정할 수는 없고 **속도가 반드시 함께 나간다** -- 차가 굴러간다. 서보만 보고
   싶으면 뒷바퀴를 바닥에서 띄우고 돌릴 것.

⚠️ **`/cmd_vel_align` 으로 보내고 drive_mux 를 ALIGN 으로 돌린다.** 처음에는
   `/cmd_vel_safe` 에 직접 썼는데, 그건 **가드의 출력 토픽**이라 가드와 이
   도구가 같은 토픽에 함께 쓰게 된다. 20 Hz 두 발행자가 번갈아 닿으면서
   조향이 0 과 요청값 사이를 오갔고, 로그만 보면 "가끔 안 먹는다" 로 보였다.
   중재기를 통과시키면 ALIGN 인 동안 nav2 명령은 아예 안 나간다.

   끝날 때 NAV 로 되돌린다. 중간에 죽으면 ALIGN 인 채 남으므로, 그때는
   `ros2 topic pub -1 /drive/mode std_msgs/String "data: NAV"` 로 돌릴 것.

보내는 값과 **실제로 UART 로 나간 값**(`/teleop/command`)을 나란히 찍는다. 둘이
갈리는 지점이 곧 원인이다 -- 2026-08-08 에 조향이 안 먹은 것은 브리지가 50°
초과에서 ValueError 로 죽어서였고, 그 다음은 가드가 각속도를 0.35 rad/s 로
자르고 있어서였다. 둘 다 밖에서는 똑같이 "서보가 안 움직인다" 로 보였다.
"""

import argparse
import json
import math
import sys
import time

from geometry_msgs.msg import Twist
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile
from std_msgs.msg import String

WHEELBASE_M = 0.155
# protocol.py 의 봉투(3200~14800, 중립 9000)와 같은 ±58°.
LIMIT_DEG = 58.0


def _mps(value):
    """엔코더가 아직 없으면 0 이 아니라 '모름' 이다."""
    return "모름" if value is None else f"{value:+.3f} m/s"


class Steer(Node):
    def __init__(self, topic: str) -> None:
        super().__init__("steer")
        self._sent = self.create_publisher(Twist, topic, 10)
        self._seen = None
        self.create_subscription(
            String, "/teleop/command",
            lambda m: setattr(self, "_seen", m.data), 10)
        latched = QoSProfile(depth=1)
        latched.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
        self._mode = self.create_publisher(String, "/drive/mode", latched)

    def take_the_wheel(self) -> None:
        self._mode.publish(String(data="ALIGN"))
        for _ in range(10):                     # 중재기가 받을 시간을 준다
            rclpy.spin_once(self, timeout_sec=0.05)

    def give_it_back(self) -> None:
        self._mode.publish(String(data="NAV"))
        for _ in range(10):
            rclpy.spin_once(self, timeout_sec=0.05)

    def hold(self, degrees: float, speed: float, seconds: float) -> None:
        """degrees 를 만드는 곡률로 seconds 동안 명령한다."""
        command = Twist()
        command.linear.x = speed
        command.angular.z = speed * math.tan(math.radians(degrees)) / WHEELBASE_M
        end = time.monotonic() + seconds
        while rclpy.ok() and time.monotonic() < end:
            self._sent.publish(command)
            rclpy.spin_once(self, timeout_sec=0.05)
        self.report(degrees, command)

    def report(self, asked_deg: float, command: Twist) -> None:
        if self._seen is None:
            print(f"  요청 {asked_deg:+6.1f}° (v={command.linear.x:+.2f} "
                  f"w={command.angular.z:+.2f}) → /teleop/command 없음 "
                  f"-- 브리지가 죽었거나 안 떠 있다")
            return
        out = json.loads(self._seen)
        print(f"  요청 {asked_deg:+6.1f}° → 실제 {out['rearSteeringDeg']:+6.1f}° "
              f"(cdeg {out['steeringCdeg']}) · 듀티 {out['drivePercent']:+4d}% "
              f"· 엔코더 {_mps(out['measuredMps'])}")

    def stop(self) -> None:
        for _ in range(6):
            self._sent.publish(Twist())
            rclpy.spin_once(self, timeout_sec=0.05)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("degrees", nargs="?", type=float,
                        help=f"후륜 조향각, 좌가 +, ±{LIMIT_DEG:.0f} 까지")
    parser.add_argument("--sweep", action="store_true", help="양끝까지 왕복")
    parser.add_argument("--watch", action="store_true",
                        help="보내지 않고 /teleop/command 만 본다")
    parser.add_argument("--speed", type=float, default=0.20,
                        help="함께 나가는 선속도 (기본 0.20)")
    parser.add_argument("--hold", type=float, default=3.0, help="유지 시간(초)")
    parser.add_argument("--topic", default="/cmd_vel_align")
    args = parser.parse_args()

    rclpy.init()
    node = Steer(args.topic)
    try:
        if not args.watch:
            node.take_the_wheel()
        if args.watch:
            print("/teleop/command 를 본다 (Ctrl-C 로 종료)")
            last = None
            while rclpy.ok():
                rclpy.spin_once(node, timeout_sec=0.2)
                if node._seen and node._seen != last:
                    last = node._seen
                    out = json.loads(last)
                    print(f"  조향 {out['rearSteeringDeg']:+6.1f}° "
                          f"(cdeg {out['steeringCdeg']}) · "
                          f"듀티 {out['drivePercent']:+4d}% · "
                          f"엔코더 {_mps(out['measuredMps'])}")
            return 0

        if args.sweep:
            steps = [0, 20, 40, LIMIT_DEG, 40, 20, 0,
                     -20, -40, -LIMIT_DEG, -40, -20, 0]
            print(f"왕복 (속도 {args.speed:.2f} m/s, 각 {args.hold:.1f}초) "
                  f"-- 바퀴를 띄워 두었는지 확인할 것")
            for degrees in steps:
                node.hold(float(degrees), args.speed, args.hold)
            return 0

        if args.degrees is None:
            print("각도를 주거나 --sweep / --watch 를 쓸 것")
            return 2
        if abs(args.degrees) > LIMIT_DEG:
            print(f"±{LIMIT_DEG:.0f}° 를 넘는다 -- 봉투 밖이라 브리지가 거부한다")
            return 2
        node.hold(args.degrees, args.speed, args.hold)
        return 0
    except KeyboardInterrupt:
        return 0
    finally:
        node.stop()
        if not args.watch:
            node.give_it_back()
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    sys.exit(main())
