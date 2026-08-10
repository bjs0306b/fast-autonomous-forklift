#!/usr/bin/env python3
"""Report where a costmap marks obstacles, relative to the vehicle.

RViz answers this faster when someone is sitting in front of it, but a
headless check is what lets the marking and the clearing be compared as
numbers rather than as an impression of a picture. That matters most for the
clearing test: "the blob looks smaller" is not an answer, "37 cells then 0
cells" is.

    python3 tools/costmap_probe.py                       # local
    python3 tools/costmap_probe.py --global              # what the planner sees

Costmaps go out as OccupancyGrid, which is rescaled to 0..100 -- 100 is
lethal, 99 is inscribed. The raw 0..255 costmap values (253/254) never appear
on this topic.
"""

import argparse
import math
import sys
import time

import rclpy
from nav_msgs.msg import OccupancyGrid
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile, QoSReliabilityPolicy
from tf2_ros import Buffer, TransformListener

LETHAL = 100
INSCRIBED = 99


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--global", dest="use_global", action="store_true")
    parser.add_argument("--radius", type=float, default=2.0,
                        help="차량에서 이 거리 안의 셀만 나열 (m)")
    parser.add_argument("--timeout", type=float, default=20.0)
    args = parser.parse_args()

    topic = ("/global_costmap/costmap" if args.use_global
             else "/local_costmap/costmap")

    rclpy.init()
    node = Node("costmap_probe")
    # Costmaps are latched, so a late subscriber still gets the current grid.
    qos = QoSProfile(depth=1)
    qos.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
    qos.reliability = QoSReliabilityPolicy.RELIABLE

    received = []
    node.create_subscription(OccupancyGrid, topic, received.append, qos)
    buffer = Buffer()
    TransformListener(buffer, node)

    deadline = time.monotonic() + args.timeout
    while rclpy.ok() and time.monotonic() < deadline and not received:
        rclpy.spin_once(node, timeout_sec=0.5)

    if not received:
        print(f" {topic} 수신 없음")
        node.destroy_node()
        rclpy.shutdown()
        return 1

    grid = received[-1]
    info = grid.info
    print(f" {topic}")
    print(f"   격자 {info.width}x{info.height} @ {info.resolution:.3f} m"
          f" · 프레임 {grid.header.frame_id}")

    counts = {}
    for value in grid.data:
        counts[value] = counts.get(value, 0) + 1
    lethal_total = counts.get(LETHAL, 0)
    print(f"   치명 {lethal_total}개 · 내접 {counts.get(INSCRIBED, 0)}개"
          f" · 미지 {counts.get(-1, 0)}개")

    # The listener starts empty, so a lookup right after the first grid
    # arrives fails with "frame does not exist" even though the transform is
    # being published. Spin until it turns up.
    transform = None
    deadline = time.monotonic() + 5.0
    while rclpy.ok() and time.monotonic() < deadline and transform is None:
        rclpy.spin_once(node, timeout_sec=0.2)
        try:
            transform = buffer.lookup_transform(
                grid.header.frame_id, "base_link", rclpy.time.Time()
            )
        except Exception:
            transform = None

    try:
        if transform is None:
            raise RuntimeError("base_link 변환을 5초 안에 못 받음")
    except Exception as error:
        print(f"   TF 없음, 차량 기준 좌표 생략: {error}")
        node.destroy_node()
        rclpy.shutdown()
        return 0

    base_x = transform.transform.translation.x
    base_y = transform.transform.translation.y
    qz = transform.transform.rotation.z
    qw = transform.transform.rotation.w
    yaw = math.atan2(2.0 * qw * qz, 1.0 - 2.0 * qz * qz)

    rows = []
    for index, value in enumerate(grid.data):
        if value < LETHAL:
            continue
        world_x = info.origin.position.x + (index % info.width + 0.5) * info.resolution
        world_y = info.origin.position.y + (index // info.width + 0.5) * info.resolution
        dx = world_x - base_x
        dy = world_y - base_y
        # Rotate into the vehicle frame so "forward" means forward, not +X of
        # whatever odom happened to start as.
        forward = dx * math.cos(-yaw) - dy * math.sin(-yaw)
        lateral = dx * math.sin(-yaw) + dy * math.cos(-yaw)
        distance = math.hypot(dx, dy)
        if distance <= args.radius:
            rows.append((distance, forward, lateral))

    rows.sort()
    print(f"   차량 {args.radius:.1f} m 이내 치명 셀 {len(rows)}개")
    for distance, forward, lateral in rows[:12]:
        side = "좌" if lateral > 0 else "우"
        print(f"      거리 {distance:.2f} m · 앞 {forward:+.2f} · "
              f"{side} {abs(lateral):.2f}")
    if len(rows) > 12:
        print(f"      ... 외 {len(rows) - 12}개")

    node.destroy_node()
    rclpy.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
