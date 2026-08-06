#!/usr/bin/env python3
"""Tell which physical module is publishing as which ToF sensor, live.

Nothing in a frame says where a sensor sits. The index is decided by which LPn
line the firmware raises first, so a crossed harness silently mirrors
everything downstream -- topic, frame, static TF and mask list all follow the
index, and a mirrored pair cannot be corrected by a transform.

Read the mapping the running image holds out of the boot log first:

    TOF: mapping: left LPn=GPIO13 INT=GPIO1, right LPn=GPIO14 INT=GPIO2

Without that line the image is unknown, and a cover test cannot distinguish a
wrong mapping from a flash that never happened.

Then watch both sensors live and cover one module at a time. Cover the left,
confirm which row reacts; uncover; cover the right, confirm the other row
reacts. Doing both directions removes any doubt about which module was
touched -- one direction alone has been misread more than once.

    python3 tools/tof_identify.py --serial     # reads the USB link directly
    python3 tools/tof_identify.py              # reads the ROS topics

Serial mode needs the port free, so stop the bridge first. It tests the
firmware mapping alone. ROS mode tests the whole chain including the bridge.
"""

import argparse
import math
import struct
import sys
import time
from pathlib import Path

PACKAGE_ROOT = (
    Path(__file__).resolve().parents[4] / "ros2_ws" / "src" / "forklift_teleop"
)
sys.path.insert(0, str(PACKAGE_ROOT))

SERIAL_PORT = "/dev/ttyACM0"
TOPICS = (("left", "/tof/left/points"), ("right", "/tof/right/points"))
NAMES = ("left", "right")


def format_row(name, count, nearest_mm, median_mm):
    if count is None:
        return f"{name:>5}: 수신 없음                    "
    if count == 0:
        return f"{name:>5}: 유효   0/64  <== 완전히 가려짐"
    return (f"{name:>5}: 유효 {count:3d}/64  최근접 {nearest_mm:4d}mm  "
            f"중앙값 {median_mm:4d}mm")


def run_serial():
    import serial

    from forklift_teleop.sensor_protocol import (
        TOF_STATUS_VALID,
        TofFrame,
        parse_sensor_line,
    )

    port = serial.Serial()
    port.port = SERIAL_PORT
    port.baudrate = 115200
    port.timeout = 0
    # DTR/RTS map onto reset and download-mode strapping on the USB
    # Serial/JTAG peripheral, so both are deasserted before opening.
    port.dtr = False
    port.rts = False
    port.open()

    print(f"{SERIAL_PORT} 열림 (ESP32 리셋 + 포크 호밍). 부팅 후 표시됩니다.")
    print("한쪽 모듈을 가리고, 어느 줄이 반응하는지 보세요. Ctrl-C 로 종료")
    sys.stdout.flush()

    buffer = bytearray()
    latest = {0: None, 1: None}
    last_print = 0.0
    try:
        while True:
            waiting = port.in_waiting
            if waiting:
                buffer.extend(port.read(waiting))
            while b"\n" in buffer:
                line, _, remainder = buffer.partition(b"\n")
                buffer = bytearray(remainder)
                try:
                    frame = parse_sensor_line(bytes(line))
                except ValueError:
                    continue
                if isinstance(frame, TofFrame) and frame.sensor_id in latest:
                    latest[frame.sensor_id] = [
                        frame.distance_mm[zone] for zone in range(64)
                        if frame.status[zone] == TOF_STATUS_VALID
                    ]

            now = time.monotonic()
            if now - last_print >= 0.5:
                last_print = now
                cells = []
                for index, name in enumerate(NAMES):
                    valid = latest[index]
                    if valid is None:
                        cells.append(format_row(name, None, 0, 0))
                    elif not valid:
                        cells.append(format_row(name, 0, 0, 0))
                    else:
                        ordered = sorted(valid)
                        cells.append(format_row(
                            name, len(valid), ordered[0],
                            ordered[len(ordered) // 2]))
                print("   ".join(cells))
                sys.stdout.flush()
            time.sleep(0.005)
    except KeyboardInterrupt:
        pass
    finally:
        port.close()
    return 0


def run_ros():
    import rclpy
    from rclpy.node import Node
    from sensor_msgs.msg import PointCloud2

    class Identify(Node):
        def __init__(self):
            super().__init__("tof_identify")
            self.latest = {name: None for name, _ in TOPICS}
            for name, topic in TOPICS:
                self.create_subscription(
                    PointCloud2, topic,
                    lambda message, key=name: self._on_cloud(key, message), 10,
                )
            self.create_timer(0.5, self._report)

        def _on_cloud(self, name, message):
            self.latest[name] = sorted(
                int(round(1000.0 * math.sqrt(sum(
                    value * value for value in struct.unpack_from(
                        "<fff", message.data, index * message.point_step)))))
                for index in range(message.width * message.height)
            )

        def _report(self):
            cells = []
            for name, _ in TOPICS:
                ranges = self.latest[name]
                if ranges is None:
                    cells.append(format_row(name, None, 0, 0))
                elif not ranges:
                    cells.append(format_row(name, 0, 0, 0))
                else:
                    cells.append(format_row(
                        name, len(ranges), ranges[0],
                        ranges[len(ranges) // 2]))
            print("   ".join(cells))
            sys.stdout.flush()

    rclpy.init()
    node = Identify()
    print("한쪽 모듈을 가리고, 어느 줄이 반응하는지 보세요. Ctrl-C 로 종료")
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    node.destroy_node()
    rclpy.shutdown()
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", action="store_true",
                        help="USB 링크를 직접 읽는다. 브리지를 먼저 내려야 "
                             "하며, 펌웨어 매핑만 검증한다")
    args = parser.parse_args()
    return run_serial() if args.serial else run_ros()


if __name__ == "__main__":
    raise SystemExit(main())
