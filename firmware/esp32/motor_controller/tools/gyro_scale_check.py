#!/usr/bin/env python3
"""Verify the gyro scale factor with a stop-and-go 4 x 90 degree rotation.

Integration does not care about the speed profile, so there is no need to turn
at a constant rate: stop, think, and turn again. What matters is that the total
angle is accurate and that the rate never clips the +-250 dps full scale.

Put the sensor on a sheet of paper with a ruled cross on it, line one edge up
with a line, and turn until it meets the next line. That is exactly 90 degrees
without timing anything.

    python3 tools/gyro_scale_check.py
"""

import math
import re
import select
import sys
import time
from pathlib import Path

PACKAGE_ROOT = (
    Path(__file__).resolve().parents[4] / "ros2_ws" / "src" / "forklift_teleop"
)
sys.path.insert(0, str(PACKAGE_ROOT))

import serial

from forklift_teleop.sensor_protocol import ImuFrame, parse_sensor_line

CONFIG_H = Path(__file__).resolve().parents[1] / "main" / "config.h"
DATASHEET_SENSITIVITY = 131.0    # LSB per dps at +-250 dps
FULL_SCALE_DPS = 250.0
SATURATION_MARGIN_DPS = 245.0
SEGMENT_COUNT = 4
SEGMENT_TARGET_DEG = 90.0
TOLERANCE_PERCENT = 5.0


def read_firmware_sensitivity():
    """Read the constant out of config.h instead of hardcoding it.

    A stale copy here silently rescales every reading by the ratio of the two
    constants, which is exactly how a bogus pi/2 factor appeared once already.
    """
    match = re.search(
        r"#define\s+IMU_GYRO_SENSITIVITY_LSB_DPS\s+([0-9.]+)f?",
        CONFIG_H.read_text(encoding="utf-8"),
    )
    if not match:
        raise SystemExit(f"{CONFIG_H}에서 IMU_GYRO_SENSITIVITY_LSB_DPS 없음")
    return float(match.group(1))


FIRMWARE_SENSITIVITY = read_firmware_sensitivity()


def enter_pressed():
    ready, _, _ = select.select([sys.stdin], [], [], 0)
    if not ready:
        return False
    sys.stdin.readline()
    return True


class Accumulator:
    def __init__(self):
        self.firmware_deg = 0.0
        self.datasheet_deg = 0.0
        self.peak_dps = 0.0
        self.saturated = 0
        self.samples = 0

    def add(self, frame, dt):
        dps_firmware = frame.gyro_z_mdps / 1000.0
        raw_lsb = dps_firmware * FIRMWARE_SENSITIVITY
        dps_datasheet = raw_lsb / DATASHEET_SENSITIVITY

        self.firmware_deg += dps_firmware * dt
        self.datasheet_deg += dps_datasheet * dt
        self.peak_dps = max(self.peak_dps, abs(dps_datasheet))
        if abs(raw_lsb) >= SATURATION_MARGIN_DPS * DATASHEET_SENSITIVITY:
            self.saturated += 1
        self.samples += 1


def main():
    port = serial.Serial()
    port.port = "/dev/ttyACM0"
    port.baudrate = 115200
    port.timeout = 0
    port.dtr = False
    port.rts = False
    port.open()

    print("=" * 62)
    print(" 자이로 스케일 판정 — 90도 x 4회 끊어 돌리기")
    print("=" * 62)
    print()
    print(" 준비: 종이에 십자선(+)을 긋고 보드 한 변을 선에 맞춥니다.")
    print("       다음 선에 맞을 때까지 돌리면 정확히 90도입니다.")
    print()
    print(" 속도는 신경 쓰지 마세요. 멈췄다 가도 됩니다.")
    print(" 90도를 1초보다 빠르게만 돌리지 않으면 포화되지 않습니다.")
    print()
    print(f" 현재 펌웨어 감도: {FIRMWARE_SENSITIVITY} LSB/dps")
    print(" 포트 열림. ESP32 리셋 + 포크 호밍을 기다립니다 (약 21초)...")
    sys.stdout.flush()

    buffer = bytearray()
    previous = None
    total = Accumulator()
    segment = Accumulator()
    segments = []
    started = False
    last_draw = 0.0
    deadline = time.monotonic() + 120.0

    while time.monotonic() < deadline:
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
            if not isinstance(frame, ImuFrame):
                continue

            if not started:
                previous = frame
                continue

            dt = (frame.mcu_time_us - previous.mcu_time_us) / 1e6
            previous = frame
            if dt <= 0.0 or dt > 0.5:
                continue
            total.add(frame, dt)
            segment.add(frame, dt)

        if not started:
            if previous is not None:
                print()
                print(">>> IMU 수신 확인. 보드를 시작 선에 맞춘 뒤 Enter.")
                sys.stdout.flush()
                while not enter_pressed():
                    if port.in_waiting:
                        buffer.extend(port.read(port.in_waiting))
                        buffer = bytearray(buffer[-4096:])
                    time.sleep(0.02)
                started = True
                deadline = time.monotonic() + 600.0
                print(f">>> 1/{SEGMENT_COUNT} 구간 시작. 90도 돌린 뒤 Enter.")
                sys.stdout.flush()
            time.sleep(0.02)
            continue

        if enter_pressed():
            segments.append(segment)
            segment = Accumulator()
            if len(segments) >= SEGMENT_COUNT:
                break
            print(f"\n>>> {len(segments) + 1}/{SEGMENT_COUNT} 구간. "
                  f"90도 더 돌린 뒤 Enter.")
            sys.stdout.flush()

        now = time.monotonic()
        if now - last_draw > 0.15:
            warning = "  << 너무 빠름!" if segment.peak_dps > 200.0 else ""
            sys.stdout.write(
                f"\r  이번 구간 {segment.firmware_deg:+7.1f}deg | "
                f"누적 {total.firmware_deg:+7.1f}deg | "
                f"피크 {segment.peak_dps:5.1f}dps{warning}   "
            )
            sys.stdout.flush()
            last_draw = now

        time.sleep(0.005)

    port.close()

    if not segments:
        print("\n측정된 구간이 없습니다.")
        return 1

    print("\n")
    print("=" * 62)
    print(f" 구간별 — 펌웨어가 실제 발행하는 값 "
          f"(감도 {FIRMWARE_SENSITIVITY})")
    for index, item in enumerate(segments, start=1):
        flag = "  포화!" if item.saturated else ""
        print(f"   {index}구간: {item.firmware_deg:+7.1f} deg "
              f"(목표 {SEGMENT_TARGET_DEG:.0f}) "
              f"피크 {item.peak_dps:5.1f} dps{flag}")

    spread = [item.firmware_deg for item in segments]
    print(f"   구간 편차: {max(spread) - min(spread):.1f} deg "
          f"(크면 스케일이 아니라 바이어스 흡수를 의심)")

    target_deg = SEGMENT_TARGET_DEG * len(segments)
    error_percent = total.firmware_deg / target_deg * 100 - 100
    print()
    print(f" 총 회전 목표: {target_deg:.0f} deg "
          f"({math.radians(target_deg):.3f} rad)")
    print()
    print(f"   측정값: {total.firmware_deg:+.1f} deg "
          f"({math.radians(total.firmware_deg):+.3f} rad)  "
          f"오차 {error_percent:+.1f}%   [허용 +-{TOLERANCE_PERCENT:.0f}%]")
    if abs(FIRMWARE_SENSITIVITY - DATASHEET_SENSITIVITY) > 0.01:
        print(f"   참고: datasheet 값 {DATASHEET_SENSITIVITY}이면 "
              f"{total.datasheet_deg:+.1f} deg "
              f"({total.datasheet_deg / target_deg * 100 - 100:+.1f}%)")
    print()
    print(f" 피크 {total.peak_dps:.1f} dps / 풀스케일 {FULL_SCALE_DPS:.0f} dps"
          f" · 포화 {total.saturated}샘플 · {total.samples}샘플")

    if total.saturated:
        print()
        print(" ** 포화 발생 — 이 측정은 무효입니다. 더 천천히 재측정하세요.")
    print("=" * 62)
    return 0 if abs(error_percent) <= TOLERANCE_PERCENT else 1


if __name__ == "__main__":
    raise SystemExit(main())
