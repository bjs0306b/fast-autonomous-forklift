#!/usr/bin/env python3
"""ToF and IMU bring-up check over the ESP32 USB telemetry link.

Opens the sensor uplink, which resets the ESP32 and runs one fork homing cycle,
then watches long enough for homing (~18s), IMU bias (~3s) and the two ToF
firmware uploads (~2.4s each) to finish.

    python3 tools/tof_probe.py [seconds]
"""

import re
import sys
import time
from pathlib import Path

# The frame parser lives with the ROS bridge that consumes the same frames.
PACKAGE_ROOT = (
    Path(__file__).resolve().parents[4] / "ros2_ws" / "src" / "forklift_teleop"
)
sys.path.insert(0, str(PACKAGE_ROOT))

import serial

from forklift_teleop.sensor_protocol import (
    TOF_STATUS_VALID,
    ImuFrame,
    ImuStatusFrame,
    TofFrame,
    parse_sensor_line,
)

SERIAL_PORT = "/dev/ttyACM0"
DURATION_SEC = float(sys.argv[1]) if len(sys.argv) > 1 else 60.0
SENSOR_NAMES = {0: "left", 1: "right"}
TOF_TARGET_HZ = 15
IMU_TARGET_HZ = 100


def open_port():
    port = serial.Serial()
    port.port = SERIAL_PORT
    port.baudrate = 115200
    port.timeout = 0
    # The USB Serial/JTAG peripheral maps DTR/RTS onto reset and download-mode
    # strapping, so both are deasserted before opening.
    port.dtr = False
    port.rts = False
    port.open()
    return port


def capture(port, duration):
    buffer = bytearray()
    imu_frames = []
    tof_frames = {sensor: [] for sensor in SENSOR_NAMES}
    tof_first_seen = {sensor: None for sensor in SENSOR_NAMES}
    logs = []
    parse_errors = 0

    start = time.monotonic()
    while time.monotonic() - start < duration:
        waiting = port.in_waiting
        if waiting:
            buffer.extend(port.read(waiting))

        while b"\n" in buffer:
            line, _, remainder = buffer.partition(b"\n")
            buffer = bytearray(remainder)
            now = time.monotonic() - start
            try:
                frame = parse_sensor_line(bytes(line))
            except ValueError:
                parse_errors += 1
                continue

            if isinstance(frame, ImuFrame):
                imu_frames.append(frame)
            elif isinstance(frame, TofFrame):
                if frame.sensor_id in tof_frames:
                    if tof_first_seen[frame.sensor_id] is None:
                        tof_first_seen[frame.sensor_id] = now
                    tof_frames[frame.sensor_id].append(frame)
            elif isinstance(frame, ImuStatusFrame):
                pass
            elif line.strip():
                logs.append((now, line.decode("ascii", "replace").strip()))

        time.sleep(0.005)

    return {
        "elapsed": time.monotonic() - start,
        "imu": imu_frames,
        "tof": tof_frames,
        "tof_first_seen": tof_first_seen,
        "logs": logs,
        "parse_errors": parse_errors,
    }


def report_logs(logs):
    print("=" * 66)
    print(" ToF / IMU 관련 로그")
    print("=" * 66)
    # Actuators are included on purpose: sensor wiring work has knocked the
    # motor bus over before, and that must not pass unnoticed.
    interesting = [
        (when, text) for when, text in logs
        if re.search(r"TOF|MPU6500|I2C|PCA9685|SERVO|DC_MOTOR|MOTOR_TASK",
                     text)
    ]
    if not interesting:
        print("  (관련 로그 없음)")
        return
    for when, text in interesting:
        print(f"[{when:6.2f}s] {text}")


def report_tof(result):
    print()
    print("=" * 66)
    print(" @TOF 프레임")
    print("=" * 66)
    any_frames = False

    for sensor_id, frames in result["tof"].items():
        name = SENSOR_NAMES[sensor_id]
        if not frames:
            print(f"  {name:5}: 프레임 없음")
            continue

        any_frames = True
        window = result["elapsed"] - result["tof_first_seen"][sensor_id]
        seq_span = frames[-1].sequence - frames[0].sequence
        received = len(frames) - 1
        print(f"  {name:5}: {len(frames)}개 · {len(frames) / window:.1f} Hz "
              f"(목표 {TOF_TARGET_HZ}) · 결번 {seq_span - received}")

        last = frames[-1]
        # Only status 5 carries a real measurement; invalid zones read 0 and
        # would drag the reported range down to nothing.
        valid = [
            last.distance_mm[zone] for zone in range(64)
            if last.status[zone] == TOF_STATUS_VALID
        ]
        if valid:
            ordered = sorted(valid)
            print(f"         유효 존 {len(valid)}/64 · 유효 거리 "
                  f"{min(valid)}~{max(valid)} mm "
                  f"(중앙값 {ordered[len(ordered) // 2]} mm)")
        else:
            print("         유효 존 0/64 — 렌즈 보호 필름 또는 가림 확인")

        histogram = {}
        for zone in range(64):
            histogram[last.status[zone]] = histogram.get(
                last.status[zone], 0) + 1
        summary = " ".join(
            f"{status}:{count}" for status, count in sorted(histogram.items())
        )
        print(f"         상태 분포 {summary}   (5=정상, 9=신뢰도 절반)")

    return any_frames


def report_imu(result):
    print()
    print("=" * 66)
    print(" IMU 무회귀")
    print("=" * 66)

    frames = result["imu"]
    # A sequence running backwards means the MCU restarted, which splits the
    # capture into segments that cannot be measured as one run.
    reboots = [
        index for index in range(1, len(frames))
        if frames[index].sequence < frames[index - 1].sequence
    ]

    if reboots:
        print(f"  ** ESP32 재부팅 {len(reboots)}회 감지 — 아래는 리셋을 "
              f"사이에 둔 조각들입니다 **")
        print("     부팅 중 리셋이면 전원·접촉 문제를 먼저 보세요.")

    if not frames:
        print("  @IMU 프레임 없음")
    else:
        boundaries = [0] + reboots + [len(frames)]
        for number in range(len(boundaries) - 1):
            chunk = frames[boundaries[number]:boundaries[number + 1]]
            if len(chunk) < 2:
                continue
            seq_span = chunk[-1].sequence - chunk[0].sequence
            span_us = chunk[-1].mcu_time_us - chunk[0].mcu_time_us
            rate = seq_span / (span_us / 1e6) if span_us else 0.0
            label = f"구간 {number + 1}" if len(boundaries) > 2 else "@IMU"
            print(f"  {label}: {len(chunk)}개 · MCU 레이트 {rate:.1f} Hz "
                  f"(목표 {IMU_TARGET_HZ}) · 결번 "
                  f"{seq_span - (len(chunk) - 1)}")

    print(f"  파싱 실패(CRC 등): {result['parse_errors']}")


def main():
    port = open_port()
    print(f"{SERIAL_PORT} 열림. ESP32 리셋 + 포크 호밍 + IMU 바이어스 + "
          f"ToF 초기화를 {DURATION_SEC:.0f}초 동안 지켜봅니다...")
    sys.stdout.flush()

    try:
        result = capture(port, DURATION_SEC)
    finally:
        port.close()

    print()
    report_logs(result["logs"])

    # I2C0 carries the motor HAT and the steering servo. Timeouts there are a
    # drive-system fault, not a sensor one, so they are called out separately.
    motor_bus_errors = sum(
        1 for _, text in result["logs"]
        if "i2c.master" in text and "timeout" in text
    )
    if motor_bus_errors:
        print()
        print(f"  ** I2C 타임아웃 {motor_bus_errors}회 — 모터 HAT/서보 버스"
              f"(I2C0, GPIO8/9)까지 영향받고 있는지 위 PCA9685 로그를 "
              f"확인하세요 **")

    any_frames = report_tof(result)
    report_imu(result)

    print()
    print("=" * 66)
    if any_frames:
        print(" 판정: ToF 프레임 수신 성공")
        print(" 다음 - 평평한 벽 0.5/1.0/2.0m 거리 정확도, RViz 기하 확인")
    else:
        print(" 판정: ToF 프레임 없음. 위 로그에서 순서대로 확인:")
        print("   1. 'answered neither 0x29 nor 0x2A' -> 배선 / SPI_I2C_N")
        print("   2. 'address did not stick'          -> SPI_I2C_N 플로팅")
        print("   3. 'sensor init failed'             -> 펌웨어 업로드, 풀업")
        print("   4. 'is being polled'                -> INT 배선")
    print("=" * 66)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
