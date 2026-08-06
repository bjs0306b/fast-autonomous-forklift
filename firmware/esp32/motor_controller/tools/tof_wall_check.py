#!/usr/bin/env python3
"""Check ToF distance accuracy against a flat wall.

Aim the sensor square at a flat wall, measure the distance from the optical
window to the wall with a tape, and run this. Repeat at a few distances --
0.5, 1.0 and 2.0 m covers the range obstacle avoidance actually uses.

    python3 tools/tof_wall_check.py --topic /tof/left/points --distance 1.0

Two numbers come out, and they fail differently.

The centre zones give the distance error: the part that matters for stopping
in the right place. Accept +-5%.

The forward component x across the whole grid gives the flatness. A flat wall
square to the sensor sits at the same x in every zone no matter the angle,
because the extra range off-axis is exactly cancelled by the cosine. If x
grows toward the edges the configured field of view is too narrow; if it
shrinks, too wide -- but only down to a floor. cos(19.7 deg) is 0.9415, so no
angle model can push the outermost x below -5.9%; more than that is the
sensor reading short at oblique incidence, which no setting will fix.

Pass --pitch with the mounting angle. A sensor tilted down 3 deg at 70 mm has
its optical axis in the floor by 1.34 m, so past that its centre zones measure
the floor and only the upward rows still reach the wall. The wall-plane
distance handles that; the centre-zone range does not.
"""

import argparse
import math
import statistics
import struct
import sys

import rclpy
from rclpy.node import Node
from sensor_msgs.msg import PointCloud2

CENTRE_HALF_ANGLE_DEG = 6.0
ACCEPT_ERROR_PERCENT = 5.0


class WallCheck(Node):
    def __init__(self, topic, samples):
        super().__init__("tof_wall_check")
        self.samples = samples
        self.clouds = []
        self.create_subscription(PointCloud2, topic, self._on_cloud, 10)

    def _on_cloud(self, message):
        if len(self.clouds) < self.samples:
            self.clouds.append([
                struct.unpack_from("<fff", message.data, index
                                   * message.point_step)
                for index in range(message.width * message.height)
            ])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--topic", default="/tof/left/points")
    parser.add_argument("--distance", type=float, required=True,
                        help="광학창에서 벽까지의 실측 수직 거리 (m)")
    parser.add_argument("--samples", type=int, default=25)
    parser.add_argument("--pitch", type=float, default=0.0,
                        help="센서가 아래로 기운 각도(도). sensors.yaml 의 "
                             "tof_pitch_deg 와 같은 값을 준다")
    args = parser.parse_args()

    rclpy.init()
    node = WallCheck(args.topic, args.samples)
    print(f"{args.topic} 에서 {args.samples}개 수집 중...")
    while rclpy.ok() and len(node.clouds) < args.samples:
        rclpy.spin_once(node, timeout_sec=1.0)
    clouds = list(node.clouds)
    node.destroy_node()
    rclpy.shutdown()

    pitch = math.radians(args.pitch)
    wall_distances = []
    centre_ranges = []
    forward_by_bearing = {}
    forward_by_elevation = {}
    for cloud in clouds:
        for x, y, z in cloud:
            distance = math.sqrt(x * x + y * y + z * z)
            if distance <= 0.0:
                continue
            azimuth = math.degrees(math.atan2(y, x))
            elevation = math.degrees(math.atan2(z, math.hypot(x, y)))
            # Distance to the wall plane, not along the optical axis. A
            # sensor pitched down by 3 deg at 70 mm has its axis in the floor
            # by 1.34 m, so beyond that the centre zones are looking at the
            # floor and only the upward rows still reach a wall. Reading the
            # centre there measures the floor and calls it a wall error.
            wall_distances.append(math.cos(pitch) * x + math.sin(pitch) * z)
            if (abs(azimuth) < CENTRE_HALF_ANGLE_DEG
                    and abs(elevation) < CENTRE_HALF_ANGLE_DEG):
                centre_ranges.append(distance)
            # Cross-sections, not projections. Taking a median over the whole
            # other axis mixes that axis's own droop into every point of the
            # profile, which makes one axis's error look like both.
            if abs(elevation) < CENTRE_HALF_ANGLE_DEG:
                forward_by_bearing.setdefault(
                    round(azimuth, 1), []).append(x)
            if abs(azimuth) < CENTRE_HALF_ANGLE_DEG:
                forward_by_elevation.setdefault(
                    round(elevation, 1), []).append(x)

    print()
    print("=" * 60)
    if not wall_distances:
        print(" 유효 반사가 없습니다.")
        print(" 벽을 정면으로 향하게 하고, 지면 필터가 벽을 지우지 않는지 "
              "확인하세요.")
        print("=" * 60)
        return 1

    measured = statistics.median(wall_distances)
    error = measured - args.distance
    percent = error / args.distance * 100.0

    print(f" 유효 {len(wall_distances)}점 (중 중앙 존 {len(centre_ranges)}점) "
          f"· 실측 {args.distance:.3f} m · 피치 {args.pitch:+.1f}°")
    print(f" 벽면거리 중앙값 {measured:.3f} m   오차 {error * 1000:+.0f} mm "
          f"({percent:+.1f}%)")
    print(f" 표준편차 {statistics.pstdev(wall_distances) * 1000:.1f} mm")
    verdict = "합격" if abs(percent) <= ACCEPT_ERROR_PERCENT else "** 불합격 **"
    print(f" 기준 +-{ACCEPT_ERROR_PERCENT:.0f}%  ->  {verdict}")

    print()
    print(" 방위별 전방거리 x  (고도 |el|<6도 단면)")
    bearings = sorted(forward_by_bearing)
    for bearing in bearings:
        values = forward_by_bearing[bearing]
        median = statistics.median(values)
        deviation = (median - args.distance) / args.distance * 100.0
        print(f"   {bearing:+6.1f}°  {median:6.3f} m  {deviation:+6.1f}%"
              f"   n={len(values)}")

    # Splitting the same x by elevation separates the two candidate causes. A
    # field of view that is set wrong shows up on one axis only, because that
    # is the axis the angle was applied to. A droop on both axes at once is
    # radial, and that is the sensor's own behaviour off-axis, not a setting.
    print()
    print(" 고도별 전방거리 x  (방위 |az|<6도 단면)")
    elevations = sorted(forward_by_elevation)
    for elevation in elevations:
        values = forward_by_elevation[elevation]
        median = statistics.median(values)
        deviation = (median - args.distance) / args.distance * 100.0
        print(f"   {elevation:+6.1f}°  {median:6.3f} m  {deviation:+6.1f}%"
              f"   n={len(values)}")

    if len(bearings) >= 4:
        edge = [forward_by_bearing[bearings[0]],
                forward_by_bearing[bearings[-1]]]
        edge_median = statistics.median(
            [value for group in edge for value in group])
        tilt = (edge_median - measured) / measured * 100.0
        print()
        print(f" 가장자리 x 가 중앙보다 {tilt:+.1f}%")
        if abs(tilt) < 3.0:
            print("   -> 화각 설정이 벽과 모순되지 않습니다")
        elif tilt > 0:
            print("   ** 가장자리가 멉니다 -> tof_fov_deg 가 실제보다 좁습니다")
        else:
            print("   ** 가장자리가 가깝습니다 -> tof_fov_deg 가 실제보다 넓습니다")
    print("=" * 60)
    return 0 if abs(percent) <= ACCEPT_ERROR_PERCENT else 1


if __name__ == "__main__":
    raise SystemExit(main())
