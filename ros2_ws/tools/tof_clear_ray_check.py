#!/usr/bin/env python3
"""Feed sensor_bridge a synthetic ToF frame and check the clearing rays.

The vehicle test cannot separate "the clearing ray was never emitted" from
"the costmap ignored it", and setting up a real box takes minutes per run.
Injecting frames directly answers the first question in a second.

    python3 tools/tof_clear_ray_check.py

A zone that keeps reporting nothing must turn into a point on the separate
clearing topic, which nav2 consumes through a marking:false source.
"""

import math
import struct
import sys

sys.path.insert(0, "src/forklift_teleop")

import rclpy

from forklift_teleop.protocol import frame_body
from forklift_teleop.sensor_bridge import SensorBridge
from forklift_teleop.sensor_protocol import TOF_ZONE_COUNT

rclpy.init()
node = SensorBridge()
marked, cleared = [], []
for publisher in node._tof_publishers:
    publisher.publish = marked.append
for publisher in node._tof_clear_publishers:
    publisher.publish = cleared.append

BOX_ZONE = 28          # 정면 근처 존 하나에 상자를 둔다
BOX_MM = 800


def payload(with_box):
    zones = []
    for index in range(TOF_ZONE_COUNT):
        if with_box and index == BOX_ZONE:
            zones.append((BOX_MM, 5))       # 유효 반사
        else:
            zones.append((0, 0))            # 측정했으나 아무것도 없음
    return "".join(f"{d:03X}{s:01X}" for d, s in zones)


def send(with_box, label):
    marked.clear(); cleared.clear()
    node._handle_line(frame_body(f"TOF,0,1,1,{payload(with_box)}"), 0.0)
    print(f"  {label:26} 장애물 {marked[0].width:2d}점 · "
          f"클리어광선 {cleared[0].width:2d}점")
    return marked[0].width, cleared[0].width


print(f"클리어 거리 {node._tof_clear_range} m · "
      f"지속 {node._tof_clear_persistence}프레임 · "
      f"빈 상태 {sorted(node._tof_empty_statuses)}\n")

print("상자 있음:")
for i in range(3):
    send(True, f"  프레임 {i+1}")

print("\n상자 치움 — 빈 존이 누적되어야 클리어 광선이 나온다:")
for i in range(4):
    near, far = send(False, f"  프레임 {i+1}")

print(f"\n판정: 상자 사라진 뒤 장애물 {near}점 (0 이어야 함), "
      f"클리어 광선 {far}점 (>0 이어야 함)")
print("  ->", "통과" if near == 0 and far > 0 else "** 실패 **")

node.destroy_node()
rclpy.shutdown()
