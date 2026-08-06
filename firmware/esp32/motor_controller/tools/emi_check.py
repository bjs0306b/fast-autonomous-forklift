#!/usr/bin/env python3
"""Watch the sensor buses for interference while the motors run.

Every other calibration step has been taken with the vehicle standing still.
This is the one that says whether the wiring survives the stepper and the drive
motor, and it is the only way to tell a marginal harness from a good one before
it shows up as an obstacle that was not there.

Read it as a rate, not a total. What matters is whether the counters move when
a motor starts and stop moving when it stops -- a fixed offset from bring-up
means nothing.

    python3 tools/emi_check.py --seconds 180

Telemetry is on USB (/dev/ttyACM0) and drive commands are on UART1
(/dev/ttyTHS1), so this runs alongside teleop. Stop the ROS bridge first
though: it holds the same USB port.

Suggested run, roughly a minute each:
  1. stand still            -- the baseline every later phase is judged against
  2. stepper only           -- the fork lift, the noisiest thing on the vehicle
  3. drive motor only
  4. both, driving around
"""

import argparse
import sys
import time
from pathlib import Path

PACKAGE_ROOT = (
    Path(__file__).resolve().parents[4] / "ros2_ws" / "src" / "forklift_teleop"
)
sys.path.insert(0, str(PACKAGE_ROOT))

import serial

from forklift_teleop.sensor_protocol import (
    EncoderFrame,
    ImuFrame,
    ImuStatusFrame,
    TofStatusFrame,
    parse_sensor_line,
)

SERIAL_PORT = "/dev/ttyACM0"
SENSOR_NAMES = {0: "left", 1: "right"}


def open_port():
    port = serial.Serial()
    port.port = SERIAL_PORT
    port.baudrate = 115200
    port.timeout = 0
    port.dtr = False
    port.rts = False
    port.open()
    return port


class Counters:
    def __init__(self):
        self.tof = {}
        self.imu_dropped = 0
        self.parse_errors = 0
        self.encoder_errors = 0
        self.imu_gaps = 0
        self.last_imu_sequence = None

    def observe(self, frame):
        if isinstance(frame, TofStatusFrame):
            self.tof[frame.sensor_id] = (frame.read_errors,
                                         frame.data_ready_errors)
        elif isinstance(frame, ImuStatusFrame):
            self.imu_dropped = frame.dropped
        elif isinstance(frame, EncoderFrame):
            self.encoder_errors = frame.read_errors
        elif isinstance(frame, ImuFrame):
            # A gap in the sequence is a frame the MCU sent and the host lost;
            # interference on the USB line shows up here rather than in I2C.
            if self.last_imu_sequence is not None:
                step = frame.sequence - self.last_imu_sequence
                if step > 1:
                    self.imu_gaps += step - 1
            self.last_imu_sequence = frame.sequence

    def snapshot(self):
        return {
            "tof": dict(self.tof),
            "imu_dropped": self.imu_dropped,
            "parse_errors": self.parse_errors,
            "encoder_errors": self.encoder_errors,
            "imu_gaps": self.imu_gaps,
        }


def totals(snapshot):
    tof_total = sum(read + ready for read, ready in snapshot["tof"].values())
    return (tof_total + snapshot["imu_dropped"] + snapshot["parse_errors"]
            + snapshot["encoder_errors"] + snapshot["imu_gaps"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seconds", type=float, default=180.0)
    parser.add_argument("--interval", type=float, default=5.0)
    args = parser.parse_args()

    port = open_port()
    counters = Counters()
    buffer = bytearray()

    print(f"{SERIAL_PORT} 열림. ESP32 리셋과 포크 호밍(약 22초)을 기다립니다...")
    print("그 뒤부터 카운터를 표시합니다. 모터를 단계별로 돌려보세요.")
    sys.stdout.flush()

    start = time.monotonic()
    baseline = None
    previous = None
    last_report = 0.0
    printed_header = False

    while time.monotonic() - start < args.seconds:
        waiting = port.in_waiting
        if waiting:
            buffer.extend(port.read(waiting))
        while b"\n" in buffer:
            line, _, remainder = buffer.partition(b"\n")
            buffer = bytearray(remainder)
            try:
                frame = parse_sensor_line(bytes(line))
            except ValueError:
                counters.parse_errors += 1
                continue
            if frame is not None:
                counters.observe(frame)

        elapsed = time.monotonic() - start
        if counters.tof and elapsed - last_report >= args.interval:
            last_report = elapsed
            snapshot = counters.snapshot()
            if baseline is None:
                baseline = snapshot
                previous = snapshot
                print()
                print("기준선을 잡았습니다. 이후 값은 기준선 대비 증가분입니다.")

            if not printed_header:
                printed_header = True
                print()
                print("  시각   좌 I2C  우 I2C  IMU유실  IMU결번  엔코더  "
                      "파싱   이번구간")
                print("  " + "-" * 62)

            def delta(current, older, sensor_id):
                new = current["tof"].get(sensor_id, (0, 0))
                old = older["tof"].get(sensor_id, (0, 0))
                return (new[0] + new[1]) - (old[0] + old[1])

            since_start = totals(snapshot) - totals(baseline)
            since_last = totals(snapshot) - totals(previous)
            flag = "  <== 증가" if since_last else ""
            print(f"  {elapsed:5.0f}s  {delta(snapshot, baseline, 0):6d}  "
                  f"{delta(snapshot, baseline, 1):6d}  "
                  f"{snapshot['imu_dropped'] - baseline['imu_dropped']:7d}  "
                  f"{snapshot['imu_gaps'] - baseline['imu_gaps']:7d}  "
                  f"{snapshot['encoder_errors'] - baseline['encoder_errors']:6d}  "
                  f"{snapshot['parse_errors'] - baseline['parse_errors']:5d}  "
                  f"{since_last:6d}{flag}")
            sys.stdout.flush()
            previous = snapshot

        time.sleep(0.005)

    port.close()

    print()
    print("=" * 66)
    if baseline is None:
        print(" @TFS 를 받지 못했습니다. 펌웨어와 ToF 초기화를 확인하세요.")
        print("=" * 66)
        return 1

    final = counters.snapshot()
    grown = totals(final) - totals(baseline)
    print(f" 기준선 이후 총 증가 {grown}건")
    for sensor_id, name in SENSOR_NAMES.items():
        new = final["tof"].get(sensor_id, (0, 0))
        old = baseline["tof"].get(sensor_id, (0, 0))
        print(f"   ToF {name:5}: I2C 에러 +{(new[0]+new[1])-(old[0]+old[1])}")
    print(f"   IMU 유실 +{final['imu_dropped'] - baseline['imu_dropped']} · "
          f"결번 +{final['imu_gaps'] - baseline['imu_gaps']} · "
          f"엔코더 +{final['encoder_errors'] - baseline['encoder_errors']} · "
          f"파싱 +{final['parse_errors'] - baseline['parse_errors']}")
    print()
    if grown == 0:
        print(" 판정: 증가 없음. 배선이 모터 노이즈를 견딥니다.")
    else:
        print(" 판정: 증가가 있습니다. 어느 구간에서 늘었는지 위 표를 보세요.")
        print("   모터를 켤 때만 늘면 EMI 입니다. 대응은 순서대로:")
        print("     1. 센서 배선을 모터/스텝퍼 배선에서 떼어 놓는다")
        print("     2. 두 배선이 교차해야 하면 직각으로 지나가게 한다")
        print("     3. I2C 풀업을 낮춘다 (상승 시간이 짧아져 잡음에 강해진다)")
        print("     4. 그래도 남으면 TOF_I2C_CLOCK_HZ 를 낮춘다")
    print("=" * 66)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
