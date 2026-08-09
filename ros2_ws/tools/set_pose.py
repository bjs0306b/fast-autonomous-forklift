#!/usr/bin/env python3
"""Tell the stack where the vehicle actually is, without RViz.

자세 보정은 지금까지 RViz 의 2D Pose Estimate 로만 할 수 있었다. 그런데 RViz 는
노트북에서 띄우고, 노트북이 오린 토픽을 못 보면 **자세를 맞출 방법이 아예
없어진다.** 지도와 목업을 맞추려는 참에 그게 막히면 그 뒤가 전부 멈춘다.

이 도구는 같은 `/initialpose` 를 명령줄에서 쏜다. RViz 가 하는 일과 똑같고,
`map_odom_publisher` 는 어느 쪽이 보냈는지 구분하지 않는다.

    python3 tools/set_pose.py 0.55 0.45          # 방향은 현재값 유지
    python3 tools/set_pose.py 0.55 0.45 90       # 방향 90도(도 단위)
    python3 tools/set_pose.py --show             # 지금 자세만 본다

⚠️ **좌표는 실물 미터다.** 심 좌표(x10)를 그대로 넣으면 목업 밖이 된다 --
   변환은 MQTT 경계에서 한 번만 한다.

⚠️ 각도는 **도(degree)** 로 받는다. MQTT 규격은 라디안이지만, 사람이 줄자와
   각도기로 재서 넣는 값이라 여기서는 도가 덜 헷갈린다. 내부에서 변환한다.
"""

import argparse
import math
import sys
import time

from geometry_msgs.msg import PoseWithCovarianceStamped
import rclpy
from rclpy.node import Node
from tf2_ros import Buffer, TransformListener


class SetPose(Node):
    def __init__(self) -> None:
        super().__init__("set_pose")
        self._publisher = self.create_publisher(
            PoseWithCovarianceStamped, "/initialpose", 10)
        self._buffer = Buffer()
        TransformListener(self._buffer, self)

    def current(self, timeout=15.0):
        """map->base_link 를 기다렸다 돌려준다. TF 는 붙는 데 시간이 걸린다."""
        deadline = time.monotonic() + timeout
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(self, timeout_sec=0.05)
            try:
                t = self._buffer.lookup_transform(
                    "map", "base_link", rclpy.time.Time())
            except Exception:
                continue
            z, w = t.transform.rotation.z, t.transform.rotation.w
            return (t.transform.translation.x, t.transform.translation.y,
                    math.atan2(2 * w * z, 1 - 2 * z * z))
        return None

    def send(self, x: float, y: float, yaw: float) -> None:
        message = PoseWithCovarianceStamped()
        message.header.frame_id = "map"
        message.header.stamp = self.get_clock().now().to_msg()
        message.pose.pose.position.x = float(x)
        message.pose.pose.position.y = float(y)
        message.pose.pose.orientation.z = math.sin(yaw / 2.0)
        message.pose.pose.orientation.w = math.cos(yaw / 2.0)
        # RViz 가 쓰는 것과 같은 대각 공분산. map_odom_publisher 는 안 보지만,
        # AMCL 로 바꿔 달 때 0 이면 특이행렬이 되어 발산한다.
        message.pose.covariance[0] = 0.25
        message.pose.covariance[7] = 0.25
        message.pose.covariance[35] = 0.068
        # 구독자가 붙을 시간을 준다 -- 한 번만 쏘면 디스커버리 전에 사라진다.
        for _ in range(10):
            self._publisher.publish(message)
            rclpy.spin_once(self, timeout_sec=0.05)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("x", nargs="?", type=float, help="실물 m")
    parser.add_argument("y", nargs="?", type=float, help="실물 m")
    parser.add_argument("yaw_deg", nargs="?", type=float, default=None,
                        help="도 단위. 생략하면 현재 방향을 유지한다")
    parser.add_argument("--show", action="store_true", help="현재 자세만 출력")
    args = parser.parse_args()

    rclpy.init()
    node = SetPose()
    try:
        before = node.current()
        if before is None:
            print("map->base_link 를 못 받았다 -- 스택이 떠 있는지 확인할 것")
            return 1
        print(f"현재  ({before[0]:.3f}, {before[1]:.3f}) m  "
              f"{math.degrees(before[2]):+.1f}°   "
              f"= 심 ({before[0]*10:.2f}, {before[1]*10:.2f})")
        if args.show:
            return 0
        if args.x is None or args.y is None:
            print("좌표를 주거나 --show 를 쓸 것")
            return 2

        yaw = (math.radians(args.yaw_deg) if args.yaw_deg is not None
               else before[2])
        node.send(args.x, args.y, yaw)
        time.sleep(1.0)

        after = node.current(timeout=5.0)
        if after is None:
            print("보냈지만 자세를 다시 못 읽었다")
            return 1
        moved = math.hypot(after[0] - args.x, after[1] - args.y)
        print(f"보정  ({after[0]:.3f}, {after[1]:.3f}) m  "
              f"{math.degrees(after[2]):+.1f}°   "
              f"= 심 ({after[0]*10:.2f}, {after[1]*10:.2f})")
        if moved > 0.05:
            print(f"⚠️ 요청한 곳에서 {moved*100:.0f} cm 벗어나 있다 -- "
                  "map_odom_publisher 가 안 떠 있을 수 있다")
            return 1
        return 0
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    sys.exit(main())
