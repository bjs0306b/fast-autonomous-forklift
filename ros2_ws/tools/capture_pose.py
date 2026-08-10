#!/usr/bin/env python3
"""Record where the vehicle is standing, under a name, for Isaac Sim.

Handing over "the pallet is about here" does not survive the trip. The way
to get a coordinate the simulator can use is to drive the real vehicle to
the spot and read the pose SLAM already knows.

    python3 tools/capture_pose.py pallet-a
    python3 tools/capture_pose.py --list
    python3 tools/capture_pose.py --frame odom start        # map 이 없을 때

Poses are written to config/field_poses.yaml in **real** coordinates, each
with its simulator equivalent alongside. The scale lives in one place --
isaac_sim/nav2/README.md says the sim world is ten times the mockup and that
the conversion happens only at the MQTT boundary -- so this file states both
rather than making the reader remember which one it is holding.
"""

import argparse
import math
import os
import sys
import time

import rclpy
from rclpy.node import Node
from tf2_ros import Buffer, TransformListener

# isaac_sim/nav2/README.md: 시뮬 세계 20x30 m, 실물 목업 2x3 m.
SIM_SCALE = 10.0

WORKSPACE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
POSE_FILE = os.path.join(
    WORKSPACE, "src", "forklift_teleop", "config", "field_poses.yaml"
)

HEADER = """# 실물 목업에서 실측한 좌표. tools/capture_pose.py 가 덧붙인다.
#
# 차량을 그 자리에 세우고 이름을 붙여 기록한 값이라, 자로 잰 값이나 눈대중과
# 달리 **SLAM 이 실제로 그 지점을 어떻게 보는지**가 들어 있다. Nav2 목표로
# 그대로 쓸 수 있는 것도 그래서다.
#
# ⚠️ real 이 원본이고 sim 은 x10 한 파생값이다 (isaac_sim/nav2/README.md).
#    둘이 어긋나면 real 을 믿을 것.
#
# frame: map 이면 SLAM 원점 기준, odom 이면 부팅 지점 기준이다. odom 은
#        재부팅하면 달라지므로 넘겨줄 좌표는 map 으로 잡아야 한다.
poses:
"""


def yaw_from(rotation):
    return math.atan2(
        2.0 * rotation.w * rotation.z,
        1.0 - 2.0 * rotation.z * rotation.z,
    )


def read_existing():
    if not os.path.exists(POSE_FILE):
        return {}
    import yaml
    with open(POSE_FILE) as handle:
        document = yaml.safe_load(handle) or {}
    return document.get("poses") or {}


def write(poses):
    lines = [HEADER]
    for name in sorted(poses):
        entry = poses[name]
        lines.append(f"  {name}:\n")
        lines.append(f"    frame: {entry['frame']}\n")
        lines.append(f"    captured: {entry['captured']}\n")
        lines.append(
            "    real: {{x: {x:.4f}, y: {y:.4f}, yaw: {yaw:.4f}}}\n".format(
                **entry["real"]
            )
        )
        lines.append(
            "    sim:  {{x: {x:.3f}, y: {y:.3f}, yaw: {yaw:.4f}}}\n".format(
                **entry["sim"]
            )
        )
    os.makedirs(os.path.dirname(POSE_FILE), exist_ok=True)
    with open(POSE_FILE, "w") as handle:
        handle.writelines(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("name", nargs="?", help="이 지점의 이름")
    parser.add_argument("--frame", default="map",
                        help="기준 프레임 (기본 map)")
    parser.add_argument("--list", action="store_true",
                        help="기록된 지점만 출력")
    parser.add_argument("--timeout", type=float, default=15.0)
    args = parser.parse_args()

    poses = read_existing()

    if args.list or not args.name:
        if not poses:
            print("기록된 지점이 없습니다.")
            return 0
        print(f"{'이름':<16} {'프레임':<6} "
              f"{'실물 x':>9} {'y':>9} {'yaw°':>8}   {'시뮬 x':>8} {'y':>8}")
        for name in sorted(poses):
            entry = poses[name]
            real, sim = entry["real"], entry["sim"]
            print(f"{name:<16} {entry['frame']:<6} "
                  f"{real['x']:9.3f} {real['y']:9.3f} "
                  f"{math.degrees(real['yaw']):8.1f}   "
                  f"{sim['x']:8.2f} {sim['y']:8.2f}")
        return 0

    rclpy.init()
    node = Node("capture_pose")
    buffer = Buffer()
    TransformListener(buffer, node)

    deadline = time.monotonic() + args.timeout
    transform = None
    while rclpy.ok() and time.monotonic() < deadline and transform is None:
        rclpy.spin_once(node, timeout_sec=0.2)
        try:
            transform = buffer.lookup_transform(
                args.frame, "base_link", rclpy.time.Time()
            )
        except Exception:
            transform = None

    if transform is None:
        print(f"'{args.frame}' -> base_link 변환을 {args.timeout:.0f}초 안에 "
              f"못 받았습니다.")
        if args.frame == "map":
            print("  SLAM 이 첫 스캔을 맞출 때까지 map 프레임이 없습니다.")
            print("  부하가 높으면 발행이 끊깁니다 -- RViz 를 끄고 다시 하거나,")
            print("  --frame odom 으로 임시 기록하세요(재부팅하면 무효).")
        node.destroy_node()
        rclpy.shutdown()
        return 1

    x = transform.transform.translation.x
    y = transform.transform.translation.y
    yaw = yaw_from(transform.transform.rotation)

    poses[args.name] = {
        "frame": args.frame,
        "captured": time.strftime("%Y-%m-%d %H:%M:%S"),
        "real": {"x": x, "y": y, "yaw": yaw},
        # yaw 는 축척과 무관하다 -- 각도는 크기를 바꿔도 그대로다.
        "sim": {"x": x * SIM_SCALE, "y": y * SIM_SCALE, "yaw": yaw},
    }
    write(poses)

    print(f"'{args.name}' 기록됨 (프레임 {args.frame})")
    print(f"  실물  x={x:+.4f}  y={y:+.4f}  yaw={math.degrees(yaw):+.1f}°")
    print(f"  시뮬  x={x * SIM_SCALE:+.3f}  y={y * SIM_SCALE:+.3f}  "
          f"yaw={math.degrees(yaw):+.1f}°")
    print(f"  -> {POSE_FILE}")

    node.destroy_node()
    rclpy.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
