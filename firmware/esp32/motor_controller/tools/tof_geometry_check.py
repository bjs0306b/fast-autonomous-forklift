#!/usr/bin/env python3
"""Confirm the ToF field of view and axis signs from a published cloud.

These are properties of the sensor and of how its zone grid maps onto space,
not of where the sensor ends up on the vehicle, so they can be settled on a
bench long before anything is bolted down. Set the RViz fixed frame to the
sensor frame and no base_link transform is needed either.

Nothing needs to be held in the air. Raising a target onto a stand puts the
stand in the field of view too, and since this picks the nearest return it
would measure the stand. A side object settles azimuth sign and transpose; the
floor -- a target of known size, position and reflectance -- settles the
elevation sign.

  1. Aim the sensor at open space, optical axis level.
  2. Stand a light-coloured object on the floor 1 m ahead and 250 mm to one
     side, which is the 14.06 deg zone centre. Keep it 50-80 mm wide: one zone
     spans 98 mm at 1 m, so anything wider straddles zones and blurs the
     bearing, while anything under 20 mm reflects too little to read. Nothing
     may sit nearer than the target.

    python3 tools/tof_geometry_check.py --topic /tof/left/points \\
        --true-azimuth 14.06

     Bearing near zero but elevation near 14 deg means the axes are
     transposed, not merely inverted.
  3. For the elevation sign, clear the space, set tof_ground_filter to false so
     the floor is not already discarded, and run:

    python3 tools/tof_geometry_check.py --topic /tof/left/points --check-floor

Reported angles are quantised to zone centres, so they carry +-2.8 deg. That
settles signs and transpose but is too coarse to pin tof_fov_deg down.
"""

import argparse
import math
import re
import struct
import sys
from pathlib import Path

import rclpy
from rclpy.node import Node
from sensor_msgs.msg import PointCloud2

SENSORS_YAML = (
    Path(__file__).resolve().parents[4]
    / "ros2_ws" / "src" / "forklift_teleop" / "config" / "sensors.yaml"
)


def read_configured_fov():
    """Read tof_fov_deg from the config the bridge actually uses."""
    match = re.search(
        r"tof_fov_deg:\s*([0-9.]+)",
        SENSORS_YAML.read_text(encoding="utf-8"),
    )
    return float(match.group(1)) if match else None


def cloud_points(message):
    return [
        struct.unpack_from("<fff", message.data, index * message.point_step)
        for index in range(message.width * message.height)
    ]


class GeometryCheck(Node):
    def __init__(self, topic, samples):
        super().__init__("tof_geometry_check")
        self.samples = samples
        self.clouds = []
        self.create_subscription(PointCloud2, topic, self._on_cloud, 10)

    def _on_cloud(self, message):
        if len(self.clouds) < self.samples:
            self.clouds.append(cloud_points(message))


def zone_index(angle_deg, fov_deg):
    """Invert a zone-centre angle back to its row or column index."""
    step = fov_deg / 8.0
    return max(0, min(7, int(round(angle_deg / step + 3.5))))


def render_grid(points, fov_deg):
    """Draw the 8x8 zone grid as the sensor sees it, in centimetres.

    The published cloud carries no zone indices, but every direction is
    quantised to a zone centre, so the indices invert exactly. Seeing the grid
    is the only way to tell a target apart from a fixed self-obstruction --
    both are just "the nearest return" otherwise.
    """
    grid = [[None] * 8 for _ in range(8)]
    for x, y, z in points:
        azimuth = math.degrees(math.atan2(y, x))
        elevation = math.degrees(math.atan2(z, math.hypot(x, y)))
        column = 7 - zone_index(azimuth, fov_deg)   # +Y (left) drawn on left
        row = 7 - zone_index(elevation, fov_deg)    # +Z (up) drawn on top
        grid[row][column] = math.sqrt(x * x + y * y + z * z) * 100.0

    print("        <- 좌(+Y)                      우(-Y) ->")
    for row in range(8):
        cells = " ".join(
            "  . " if grid[row][col] is None else f"{grid[row][col]:4.0f}"
            for col in range(8)
        )
        marker = "위(+Z)" if row == 0 else ("아래(-Z)" if row == 7 else "      ")
        print(f"  {marker:>8} {cells}")
    print("        (단위 cm, '.' = 유효 반사 없음)")


def collect(node, samples):
    """Gather a fresh batch of clouds, discarding whatever came before."""
    node.clouds = []
    while rclpy.ok() and len(node.clouds) < samples:
        rclpy.spin_once(node, timeout_sec=1.0)
    return list(node.clouds)


def grid_of(clouds, fov_deg):
    """Median range per zone, keyed by the drawn (row, column)."""
    cells = {}
    for cloud in clouds:
        for x, y, z in cloud:
            azimuth = math.degrees(math.atan2(y, x))
            elevation = math.degrees(math.atan2(z, math.hypot(x, y)))
            key = (7 - zone_index(elevation, fov_deg),
                   7 - zone_index(azimuth, fov_deg))
            cells.setdefault(key, []).append(math.sqrt(x * x + y * y + z * z))
    return {
        key: sorted(values)[len(values) // 2]
        for key, values in cells.items()
    }


def diff_cells(background, target, threshold):
    """Zones the target occupies that the background did not.

    Subtracting a background beats hunting for the target by eye. A cluttered
    bench puts walls and cabling in the same grid, and picking the target out
    of that has produced contradictory readings; what newly blocks a zone, or
    blocks it closer than before, is unambiguous.
    """
    changed = {}
    for key, distance in target.items():
        previous = background.get(key)
        if previous is None or distance < previous - threshold:
            changed[key] = distance
    return changed


def describe(points, min_range=0.0):
    """Azimuth and elevation of the nearest return, which is the target."""
    points = [
        p for p in points
        if math.sqrt(sum(v * v for v in p)) >= min_range
    ]
    if not points:
        return None
    nearest = min(points, key=lambda p: math.sqrt(sum(v * v for v in p)))
    x, y, z = nearest
    return {
        "range": math.sqrt(x * x + y * y + z * z),
        "azimuth": math.degrees(math.atan2(y, x)),
        "elevation": math.degrees(math.atan2(z, math.hypot(x, y))),
        "point": nearest,
    }


def report_diff(background, target, fov_deg, args):
    changed = diff_cells(background, target, args.diff_threshold)

    print()
    print("=" * 60)
    print(" 배경을 뺀 뒤 새로 막힌 존")
    print()
    print("        <- 좌(+Y)                      우(-Y) ->")
    for row in range(8):
        cells = " ".join(
            "  . " if (row, col) not in changed
            else f"{changed[(row, col)] * 100:4.0f}"
            for col in range(8)
        )
        marker = "위(+Z)" if row == 0 else ("아래(-Z)" if row == 7 else "      ")
        print(f"  {marker:>8} {cells}")
    print("        (단위 cm)")
    print()

    if not changed:
        print(" 달라진 존이 없습니다. 물체가 반사를 못 내거나 배경과 같은 "
              "거리입니다.")
        print(" 더 밝고 큰 판을, 배경보다 확실히 앞에 두세요.")
        print("=" * 60)
        return 1

    step = fov_deg / 8.0
    azimuths = [(7 - col + 0.5 - 4) * step for row, col in changed]
    elevations = [(7 - row + 0.5 - 4) * step for row, col in changed]
    azimuth = sum(azimuths) / len(azimuths)
    elevation = sum(elevations) / len(elevations)
    ranges = sorted(changed.values())

    print(f" 물체 존 {len(changed)}칸 · 거리 중앙값 "
          f"{ranges[len(ranges) // 2]:.2f} m")
    print(f" 중심 방위 {azimuth:+.1f}°, 중심 고도 {elevation:+.1f}°")

    if args.true_azimuth is not None:
        print()
        print(f" 방위 실제값 {args.true_azimuth:+.1f}° vs 측정 {azimuth:+.1f}°")
        if abs(elevation) > abs(azimuth):
            print("   ** 옆으로 옮겼는데 고도로 나타납니다 -> "
                  "tof_transpose_zones 를 true 로")
        elif azimuth * args.true_azimuth < 0:
            print("   ** 부호가 반대입니다 -> tof_azimuth_sign 을 뒤집으세요")
        else:
            print("   -> 방위 부호 정상, 전치 아님")
    print("=" * 60)
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--topic", default="/tof/left/points")
    parser.add_argument("--samples", type=int, default=20)
    parser.add_argument("--true-azimuth", type=float, default=None,
                        help="목표물의 실제 방위각(도). 왼쪽이 +")
    parser.add_argument("--true-elevation", type=float, default=None,
                        help="목표물의 실제 고도각(도). 위쪽이 +")
    parser.add_argument("--diff", action="store_true",
                        help="배경을 먼저 찍고 물체를 놓아 차이만 본다. "
                             "어수선한 곳에서 유일하게 믿을 수 있는 방법")
    parser.add_argument("--diff-threshold", type=float, default=0.10,
                        help="배경보다 이만큼(m) 가까워야 물체로 친다")
    parser.add_argument("--min-range", type=float, default=0.0,
                        help="이보다 가까운 반사는 무시한다 (m). 차체 자체 "
                             "가림이 목표물보다 가까울 때 쓴다")
    parser.add_argument("--check-floor", action="store_true",
                        help="바닥 반사로 고도 부호를 판정한다. 앞을 비우고 "
                             "바닥만 보이게 한 뒤 실행")
    parser.add_argument("--mount-height", type=float, default=0.070,
                        help="바닥에서 광학창까지 (m). sensors.yaml 의 "
                             "tof_mount_height_m 과 같아야 한다")
    args = parser.parse_args()

    configured_fov = read_configured_fov()

    rclpy.init()
    node = GeometryCheck(args.topic, args.samples)

    if args.diff:
        input("물체를 치우고 Enter — 배경을 찍습니다: ")
        background = grid_of(collect(node, args.samples), configured_fov or 45.0)
        print(f"  배경 {len(background)}칸 기록")
        input("물체를 놓고 Enter: ")
        target = grid_of(collect(node, args.samples), configured_fov or 45.0)
        node.destroy_node()
        rclpy.shutdown()
        return report_diff(background, target, configured_fov or 45.0, args)

    print(f"{args.topic} 에서 {args.samples}개 수집 중...")
    while rclpy.ok() and len(node.clouds) < args.samples:
        rclpy.spin_once(node, timeout_sec=1.0)

    counts = [len(cloud) for cloud in node.clouds]
    node_clouds = list(node.clouds)
    readings = [describe(cloud, args.min_range) for cloud in node.clouds]
    readings = [item for item in readings if item]

    node.destroy_node()
    rclpy.shutdown()

    print()
    print("=" * 60)
    if not readings:
        if args.min_range:
            print(f" {args.min_range:.2f} m 밖에 유효 점이 없습니다.")
        else:
            print(" 유효 점이 없습니다. 렌즈 가림이나 상태 필터를 확인하세요.")
        render_grid(node_clouds[-1] if node_clouds else [],
                    configured_fov or 45.0)
        return 1

    print(f" 유효 점 수: 평균 {sum(counts) / len(counts):.1f} / 64")
    print()
    render_grid(node_clouds[-1], configured_fov or 45.0)
    print()
    azimuths = sorted(item["azimuth"] for item in readings)
    elevations = sorted(item["elevation"] for item in readings)
    ranges = sorted(item["range"] for item in readings)
    median_azimuth = azimuths[len(azimuths) // 2]
    median_elevation = elevations[len(elevations) // 2]

    print(f" 최근접 반사 (중앙값): 거리 {ranges[len(ranges) // 2]:.3f} m, "
          f"방위 {median_azimuth:+.1f}°, 고도 {median_elevation:+.1f}°")
    print(f" 설정된 tof_fov_deg: {configured_fov}")

    if args.true_azimuth is not None:
        print()
        print(f" 방위 실제값 {args.true_azimuth:+.1f}° "
              f"vs 측정 {median_azimuth:+.1f}°")
        if median_azimuth * args.true_azimuth < 0:
            print("   ** 부호가 반대입니다 -> tof_azimuth_sign 을 뒤집으세요")
        elif abs(median_azimuth) > 0.5 and configured_fov:
            implied = configured_fov * (args.true_azimuth / median_azimuth)
            print(f"   -> 함의되는 tof_fov_deg = {implied:.1f}")

    if args.true_elevation is not None:
        print()
        print(f" 고도 실제값 {args.true_elevation:+.1f}° "
              f"vs 측정 {median_elevation:+.1f}°")
        if median_elevation * args.true_elevation < 0:
            print("   ** 부호가 반대입니다 -> tof_elevation_sign 을 뒤집으세요")
        elif abs(median_elevation) > 0.5 and configured_fov:
            implied = configured_fov * (args.true_elevation / median_elevation)
            print(f"   -> 함의되는 tof_fov_deg = {implied:.1f}")

    if args.check_floor:
        print()
        print(" 바닥 판정 (고도 부호)")
        # The floor is a target whose size, position and reflectance are all
        # already known, so it needs no stand and cannot wobble. Rows below the
        # optical axis must strike it, and must therefore come out below the
        # sensor -- a floor above the sensor is not a measurement, it is a
        # flipped sign.
        below = above = 0
        deepest = 0.0
        for cloud in node_clouds:
            for x, y, z in cloud:
                if z < -0.01:
                    below += 1
                    deepest = min(deepest, z)
                elif z > 0.01:
                    above += 1

        print(f"   센서보다 아래 점 {below}개 · 위 점 {above}개")
        print(f"   가장 낮은 점 {deepest:.3f} m "
              f"(장착 높이 {args.mount_height:.3f} m)")
        if below == 0:
            print("   ** 아래쪽 점이 없습니다. 바닥이 보이게 앞을 비우세요.")
        elif deepest < -(args.mount_height * 1.5):
            print("   ** 장착 높이보다 훨씬 아래입니다 — mount_height 확인")
        elif below > above:
            print("   -> 바닥이 아래로 나옵니다. 고도 부호 정상")
        else:
            print("   ** 위쪽 점이 더 많습니다 -> tof_elevation_sign 뒤집기")

    print("=" * 60)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
