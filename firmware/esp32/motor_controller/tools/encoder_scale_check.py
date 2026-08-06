#!/usr/bin/env python3
"""Measure counts per wheel revolution by turning the wheel a whole number of
turns.

The datasheet cannot answer this. "JGA25-370" is sold with several gear ratios
and two encoder resolutions, and picking the wrong pair scales every distance
the encoder reports by a constant without ever looking wrong -- the robot just
believes it travelled further than it did. Turning the wheel by hand measures
the product directly, which is the only number the bridge needs.

    python3 tools/encoder_scale_check.py --turns 10

Lift the drive wheel clear of the floor, mark it, and turn it by hand in the
FORWARD direction for the number of turns given. Counts must come out positive;
negative means the two signal wires are swapped, which is worth fixing at the
connector so forward stays forward everywhere.
"""

import argparse
import math
import sys
import time
from pathlib import Path

PACKAGE_ROOT = (
    Path(__file__).resolve().parents[4] / "ros2_ws" / "src" / "forklift_teleop"
)
sys.path.insert(0, str(PACKAGE_ROOT))

import serial

from forklift_teleop.sensor_protocol import EncoderFrame, parse_sensor_line

SERIAL_PORT = "/dev/ttyACM0"


def open_port():
    port = serial.Serial()
    port.port = SERIAL_PORT
    port.baudrate = 115200
    port.timeout = 0
    port.dtr = False
    port.rts = False
    port.open()
    return port


def latest_count(port, buffer, seconds):
    """Read for a while and return the newest count seen, or None."""
    count = None
    errors = 0
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        waiting = port.in_waiting
        if waiting:
            buffer.extend(port.read(waiting))
        while b"\n" in buffer:
            line, _, remainder = buffer.partition(b"\n")
            buffer[:] = remainder
            try:
                frame = parse_sensor_line(bytes(line))
            except ValueError:
                continue
            if isinstance(frame, EncoderFrame):
                count = frame.count
                errors = frame.read_errors
        time.sleep(0.005)
    return count, errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--turns", type=float, default=10.0,
                        help="손으로 돌릴 바퀴 회전 수. 많을수록 정확하다")
    parser.add_argument("--diameter", type=float, default=0.060,
                        help="바퀴 지름 (m)")
    args = parser.parse_args()

    port = open_port()
    buffer = bytearray()
    print(f"{SERIAL_PORT} 열림. ESP32 리셋과 포크 호밍이 끝나기를 기다립니다...")
    sys.stdout.flush()

    # The port open resets the board; the encoder task starts after homing.
    start, _ = latest_count(port, buffer, 30.0)
    if start is None:
        print("@ENC 프레임이 없습니다. 엔코더 펌웨어가 올라갔는지 확인하세요.")
        port.close()
        return 1

    print(f"현재 카운트 {start}")
    input(f"바퀴를 전진 방향으로 정확히 {args.turns:g} 바퀴 돌린 뒤 Enter: ")

    end, errors = latest_count(port, buffer, 2.0)
    port.close()

    if end is None:
        print("측정 후 프레임을 받지 못했습니다.")
        return 1

    delta = end - start
    print()
    print("=" * 60)
    print(f" 카운트 {start} -> {end}   차이 {delta:+d}")

    if delta == 0:
        print(" ** 변화가 없습니다. 배선(D1/D0)과 커넥터를 확인하세요.")
        print("=" * 60)
        return 1

    if delta < 0:
        print(" ** 음수입니다. 전진인데 카운트가 줄었습니다.")
        print("    두 신호선을 바꿔 꽂으면 전진이 양수가 됩니다.")

    counts_per_rev = abs(delta) / args.turns
    circumference = math.pi * args.diameter
    print(f" 회전당 {counts_per_rev:.1f} counts")
    print(f" 바퀴 둘레 {circumference * 1000:.1f} mm "
          f"-> {circumference / counts_per_rev * 1000:.4f} mm/count")
    if errors:
        print(f" ** 펌웨어 읽기 오류 {errors}회")
    print()
    print(f" -> sensors.yaml 의 encoder_counts_per_wheel_rev: "
          f"{counts_per_rev:.1f}")
    print()
    print(" 11 또는 13 PPR x 4체배 x 감속비 로 나눠떨어지는지 보면 검산이 된다:")
    for ppr in (11, 13):
        ratio = counts_per_rev / (ppr * 4)
        print(f"   {ppr} PPR 가정 -> 감속비 1:{ratio:.2f}")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
