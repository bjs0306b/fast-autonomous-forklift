#!/usr/bin/env python3
"""Measure the standing yaw-rate noise that the EKF needs as yaw_rate_stddev.

Run with the vehicle stationary but the motors POWERED. A bench figure taken
with everything off has no vibration in it and comes out several times too
small, and feeding that to the EKF tells it the IMU is nearly perfect, so it
stops trusting the lidar odometry it was meant to be corrected by. Leaving the
parameter at zero is the same mistake taken to its limit.

    python3 tools/yaw_noise_check.py --seconds 60

Report both numbers it prints: the standing deviation is what goes into
sensors.yaml, and the drift over the window says whether the bias tracker is
keeping up.
"""

import argparse
import math
import statistics
import sys

import rclpy
from rclpy.node import Node
from sensor_msgs.msg import Imu

SETTLE_SEC = 3.0


class YawNoise(Node):
    def __init__(self, topic):
        super().__init__("yaw_noise_check")
        self.samples = []
        self.stamps = []
        self.create_subscription(Imu, topic, self._on_imu, 200)

    def _on_imu(self, message):
        stamp = message.header.stamp.sec + message.header.stamp.nanosec * 1e-9
        self.samples.append(message.angular_velocity.z)
        self.stamps.append(stamp)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--topic", default="/imu/data")
    parser.add_argument("--seconds", type=float, default=60.0)
    args = parser.parse_args()

    rclpy.init()
    node = YawNoise(args.topic)
    print(f"{args.topic} 에서 {args.seconds:.0f}초 수집합니다.")
    print("차량은 정지, 모터 전원은 켜진 상태여야 합니다. 건드리지 마세요.")
    sys.stdout.flush()

    start = node.get_clock().now().nanoseconds / 1e9
    while rclpy.ok():
        rclpy.spin_once(node, timeout_sec=0.5)
        if node.stamps and node.stamps[-1] - start >= args.seconds:
            break

    samples, stamps = list(node.samples), list(node.stamps)
    node.destroy_node()
    rclpy.shutdown()

    print()
    print("=" * 60)
    if len(samples) < 100:
        print(f" 표본이 {len(samples)}개뿐입니다. 브리지가 도는지 확인하세요.")
        return 1

    # The first seconds carry the settle after whatever was touched last.
    begin = stamps[0]
    kept = [
        value for value, stamp in zip(samples, stamps)
        if stamp - begin >= SETTLE_SEC
    ]
    span = stamps[-1] - begin
    rate = len(samples) / span if span else 0.0

    mean = statistics.fmean(kept)
    stddev = statistics.stdev(kept)
    peak = max(abs(value - mean) for value in kept)

    print(f" 표본 {len(kept)}개 · {span:.1f}초 · {rate:.1f} Hz")
    print()
    print(f" 평균      {mean:+.6f} rad/s   ({math.degrees(mean):+.3f} deg/s)")
    print(f" 표준편차  {stddev:.6f} rad/s   ({math.degrees(stddev):.3f} deg/s)")
    print(f" 최대편차  {peak:.6f} rad/s")
    print()
    print(f" -> sensors.yaml 의 yaw_rate_stddev: {stddev:.6f}")

    # A residual mean is bias the tracker has not taken out. It integrates
    # straight into heading error, so it matters more than the spread.
    drift_deg = math.degrees(mean) * 60.0
    print()
    print(f" 잔류 바이어스가 그대로 쌓이면 1분에 {drift_deg:+.2f}도")
    if abs(drift_deg) > 5.0:
        print("   ** 큽니다. 정지 판정 게이트가 회전을 흡수하는지 보세요")
    elif abs(drift_deg) > 1.0:
        print("   보통입니다. EKF 가 라이다로 잡아줍니다")
    else:
        print("   작습니다")

    if stddev < 1e-4:
        print()
        print(" ** 너무 작습니다. 모터 전원이 정말 켜져 있었습니까? **")
        print("    진동이 없는 값을 넣으면 EKF 가 IMU 를 과신합니다")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
