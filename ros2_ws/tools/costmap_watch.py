#!/usr/bin/env python3
"""Watch a costmap sector live while an obstacle is placed and removed.

Counting the whole grid does not answer the clearing question: walls and
furniture dominate the total, and a box appearing or vanishing moves the count
by a few percent that is indistinguishable from noise. Watching one sector in
front of the vehicle and printing it every second makes the change obvious --
place the box, the number jumps; take it away, it must fall back.

    python3 tools/costmap_watch.py --near 0.5 --far 1.2 --half-width 0.4

Defaults cover the band where the ToF sensors see and the guard stops.
"""

import argparse
import math
import time

import rclpy
from nav_msgs.msg import OccupancyGrid
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile, QoSReliabilityPolicy
from tf2_ros import Buffer, TransformListener

LETHAL = 100


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--global", dest="use_global", action="store_true")
    parser.add_argument("--near", type=float, default=0.5)
    parser.add_argument("--far", type=float, default=1.2)
    parser.add_argument("--half-width", type=float, default=0.4)
    parser.add_argument("--seconds", type=float, default=60.0)
    args = parser.parse_args()

    topic = ("/global_costmap/costmap" if args.use_global
             else "/local_costmap/costmap")

    rclpy.init()
    node = Node("costmap_watch")
    qos = QoSProfile(depth=1)
    qos.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
    qos.reliability = QoSReliabilityPolicy.RELIABLE

    latest = {}
    node.create_subscription(
        OccupancyGrid, topic, lambda m: latest.__setitem__("grid", m), qos
    )
    buffer = Buffer()
    TransformListener(buffer, node)

    print(f" {topic}")
    print(f" 감시 구간: 앞 {args.near:.2f}~{args.far:.2f} m, "
          f"좌우 +-{args.half_width:.2f} m")
    print(" 상자를 놓고 빼면서 숫자를 보세요. Ctrl-C 로 종료")
    print()

    start = time.monotonic()
    baseline = None
    while rclpy.ok() and time.monotonic() - start < args.seconds:
        rclpy.spin_once(node, timeout_sec=0.2)
        grid = latest.get("grid")
        if grid is None:
            continue
        try:
            transform = buffer.lookup_transform(
                grid.header.frame_id, "base_link", rclpy.time.Time()
            )
        except Exception:
            continue

        info = grid.info
        base_x = transform.transform.translation.x
        base_y = transform.transform.translation.y
        qz = transform.transform.rotation.z
        qw = transform.transform.rotation.w
        yaw = math.atan2(2.0 * qw * qz, 1.0 - 2.0 * qz * qz)

        count = 0
        nearest = math.inf
        for index, value in enumerate(grid.data):
            if value < LETHAL:
                continue
            world_x = (info.origin.position.x
                       + (index % info.width + 0.5) * info.resolution)
            world_y = (info.origin.position.y
                       + (index // info.width + 0.5) * info.resolution)
            dx = world_x - base_x
            dy = world_y - base_y
            forward = dx * math.cos(-yaw) - dy * math.sin(-yaw)
            lateral = dx * math.sin(-yaw) + dy * math.cos(-yaw)
            if (args.near <= forward <= args.far
                    and abs(lateral) <= args.half_width):
                count += 1
                nearest = min(nearest, forward)

        if baseline is None:
            baseline = count
        marker = ""
        if count > baseline:
            marker = f"  +{count - baseline}  <== 늘었다"
        elif count < baseline:
            marker = f"  -{baseline - count}  <== 줄었다"
        near_text = f"{nearest:.2f} m" if math.isfinite(nearest) else "-"
        print(f"  {time.monotonic() - start:5.1f}s  치명 {count:3d}개 "
              f"· 최근접 {near_text}{marker}")
        baseline = count
        time.sleep(0.8)

    node.destroy_node()
    rclpy.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
