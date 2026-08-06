#!/usr/bin/env python3
"""Find the zones that always see the vehicle's own forks or chassis.

Self-occlusion cannot be filtered by range: the fork sits at a fixed distance,
so it looks like a perfectly good obstacle. What gives it away is that it never
moves. Point the sensor at open space, run this, and any zone that keeps
returning the same short distance is looking at the vehicle.

    python3 tools/tof_mask_learn.py --topic /tof/left/points --seconds 10

Paste the printed list into `tof_masked_zones_left` / `_right` in
`ros2_ws/src/forklift_teleop/config/sensors.yaml`.

Run it with the fork at travel height. Raising the lift takes the fork out of
view, so a mask learned high would miss it while driving.
"""

import argparse
import math
import struct
import sys
from collections import defaultdict

import rclpy
from rclpy.node import Node
from sensor_msgs.msg import PointCloud2

# The bridge drops invalid zones rather than padding them, so a cloud carries
# only the zones that returned something. Zones are recovered by bearing.
TOF_GRID_SIDE = 8


def cloud_points(message):
    return [
        struct.unpack_from("<fff", message.data, index * message.point_step)
        for index in range(message.width * message.height)
    ]


class MaskLearner(Node):
    def __init__(self, topic, fov_deg):
        super().__init__("tof_mask_learn")
        self.step = math.radians(fov_deg) / TOF_GRID_SIDE
        self.centre = (TOF_GRID_SIDE - 1) / 2.0
        self.samples = defaultdict(list)
        self.clouds = 0
        self.create_subscription(PointCloud2, topic, self._on_cloud, 10)

    def _zone_of(self, point):
        """Recover the grid index a point came from, via its bearing."""
        x, y, z = point
        if x <= 0.0:
            return None
        azimuth = math.atan2(y, x)
        elevation = math.atan2(z, math.hypot(x, y))
        column = round(self.centre - azimuth / self.step)
        row = round(self.centre - elevation / self.step)
        if not (0 <= row < TOF_GRID_SIDE and 0 <= column < TOF_GRID_SIDE):
            return None
        return row * TOF_GRID_SIDE + column

    def _on_cloud(self, message):
        self.clouds += 1
        for point in cloud_points(message):
            zone = self._zone_of(point)
            if zone is not None:
                self.samples[zone].append(
                    math.sqrt(sum(value * value for value in point))
                )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--topic", default="/tof/left/points")
    parser.add_argument("--seconds", type=float, default=10.0)
    parser.add_argument("--fov-deg", type=float, default=45.0,
                        help="sensors.yaml 의 tof_fov_deg 와 같아야 한다")
    parser.add_argument("--max-distance", type=float, default=0.25,
                        help="이보다 먼 반사는 자기 가림으로 보지 않는다")
    parser.add_argument("--max-spread", type=float, default=0.02,
                        help="이보다 덜 흔들리면 고정된 구조물로 본다")
    parser.add_argument("--min-hit-ratio", type=float, default=0.9,
                        help="이 비율 이상의 프레임에 나타나야 한다")
    args = parser.parse_args()

    rclpy.init()
    node = MaskLearner(args.topic, args.fov_deg)
    print(f"{args.topic} 에서 {args.seconds:.0f}초 관찰합니다.")
    print("센서 앞을 비우고, 포크는 travel 높이로 두세요.")
    sys.stdout.flush()

    start = node.get_clock().now()
    while rclpy.ok():
        rclpy.spin_once(node, timeout_sec=0.1)
        if (node.get_clock().now() - start).nanoseconds / 1e9 >= args.seconds:
            break

    clouds = node.clouds
    samples = dict(node.samples)
    node.destroy_node()
    rclpy.shutdown()

    print()
    print("=" * 62)
    if clouds == 0:
        print(" 클라우드를 못 받았습니다. 브리지가 떠 있는지 확인하세요.")
        return 1

    print(f" {clouds}개 프레임 관찰")
    masked = []
    for zone in sorted(samples):
        distances = samples[zone]
        hit_ratio = len(distances) / clouds
        spread = max(distances) - min(distances)
        mean = sum(distances) / len(distances)

        if (hit_ratio >= args.min_hit_ratio
                and mean <= args.max_distance
                and spread <= args.max_spread):
            masked.append(zone)
            print(f"   존 {zone:2}: {mean * 1000:6.0f} mm, 흔들림 "
                  f"{spread * 1000:4.0f} mm, {hit_ratio * 100:3.0f}% 프레임"
                  f"   -> 마스킹")

    print()
    if masked:
        print(" sensors.yaml 에 붙여넣으세요:")
        print(f"   tof_masked_zones_left: {masked}")
        print()
        print(" ** 눈으로 확인하세요. 가까운 벽이나 바닥을 가리키고 있었다면")
        print("    그건 자기 가림이 아니라 진짜 장애물입니다. **")
    else:
        print(" 자기 가림으로 보이는 존이 없습니다. 마스킹 불필요.")
    print("=" * 62)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
