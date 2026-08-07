#!/usr/bin/env python3
"""Derive the guard's safety distances from the measured body and steering.

The thresholds in config/obstacle_avoidance.yaml were picked by hand and then
argued about. They do not have to be: the footprint, the wheelbase and the
steering limit are all measured, and together they say exactly how much room
the vehicle needs to stop and to turn.

    python3 tools/geometry_limits.py
    python3 tools/geometry_limits.py --speed 0.08

⚠️ base_link is the **front drive axle** centre (ros2_ws/README.md,
   docs/센서-측정-칼리브레이션.md §1). orin-pose-spec.md says rear axle and is
   wrong; that disagreement is tracked in docs/isaac-sim-연동-인수인계.md §2.3.
   Every number here is meaningless if that origin moves.

The rear axle steers, so the tail swings outward through a turn -- the widest
part of the swept path is the outer *rear* corner, not the front. A clearance
figure taken from the front alone lets the tail hit something the check said
was clear.
"""

import argparse
import math
import os

import yaml

WORKSPACE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TELEOP = os.path.join(WORKSPACE, "src", "forklift_teleop", "config",
                      "teleop.yaml")
GUARD = os.path.join(WORKSPACE, "src", "forklift_teleop", "config",
                     "obstacle_avoidance.yaml")
NAV2 = os.path.join(WORKSPACE, "nav2_params.yaml")


def load():
    with open(TELEOP) as handle:
        teleop = yaml.safe_load(handle)["uart_teleop_bridge"]["ros__parameters"]
    with open(GUARD) as handle:
        guard = yaml.safe_load(handle)["obstacle_avoidance"]["ros__parameters"]
    with open(NAV2) as handle:
        nav2 = yaml.safe_load(handle)
    local = nav2["local_costmap"]["local_costmap"]["ros__parameters"]
    footprint = yaml.safe_load(local["footprint"])
    return teleop, guard, local, footprint


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--speed", type=float, default=None,
                        help="제동거리 계산에 쓸 주행 속도 (기본 max_linear_mps)")
    parser.add_argument("--reaction", type=float, default=None,
                        help="반응 시간 s (기본 1/control_rate + command_timeout)")
    args = parser.parse_args()

    teleop, guard, local, footprint = load()

    front = max(point[0] for point in footprint)
    rear = -min(point[0] for point in footprint)
    half_width = max(abs(point[1]) for point in footprint)
    padding = float(local.get("footprint_padding", 0.0))

    wheelbase = float(teleop["wheelbase_m"])
    steer_limit = math.radians(float(teleop["rear_steering_limit_deg"]))
    speed = args.speed if args.speed is not None else float(
        teleop["max_linear_mps"]
    )

    # One control period to notice, plus the window the firmware waits before
    # its own watchdog would cut the motor. Being late is what a stopping
    # distance is for.
    rate = float(guard["control_rate_hz"])
    reaction = args.reaction if args.reaction is not None else (
        1.0 / rate + float(guard["command_timeout_sec"])
    )
    braking = speed * reaction

    # Rear-wheel steering: the front axle does not turn, so the instantaneous
    # centre of rotation sits on the front axle line -- through base_link.
    radius = wheelbase / math.tan(steer_limit)

    # Every point on a rigid body traces a circle about that centre. The one
    # furthest from it sets the outside of the swept band.
    outer = math.hypot(rear + padding, radius + half_width + padding)
    inner = radius - half_width - padding
    tail_swing = outer - radius

    print("측정값 (base_link = 전륜 구동축 중심)")
    print(f"  footprint  앞 {front:.3f} · 뒤 {rear:.3f} · 반폭 {half_width:.3f}"
          f" · 패딩 {padding:.3f} m")
    print(f"  축거 {wheelbase:.3f} m · 후륜 조향한계 "
          f"{math.degrees(steer_limit):.0f}°")
    print(f"  후륜(조향)축 x = {-wheelbase:+.3f} · 조향축 뒤 돌출 "
          f"{rear - wheelbase:.3f} m")
    print()
    print(f"최소 회전반경  R = 축거/tan(조향한계) = {radius:.3f} m")
    print(f"  휩쓰는 띠  안쪽 {inner:.3f} ~ 바깥 {outer:.3f} m")
    print(f"  ⚠️ 꼬리 휨(tail swing) {tail_swing:.3f} m "
          f"— 회전 바깥쪽에 이만큼 더 필요하다")
    print()
    print(f"제동거리  {speed:.3f} m/s x {reaction:.2f} s = {braking:.3f} m")
    print(f"  (반응 = 제어주기 1/{rate:.0f} s + 명령 타임아웃 "
          f"{guard['command_timeout_sec']} s)")
    print()

    rows = [
        ("stop_distance_m", front + padding + braking,
         guard["stop_distance_m"], "앞끝 + 패딩 + 제동"),
        ("rear_stop_distance_m", rear + padding + braking,
         guard["rear_stop_distance_m"], "뒤끝 + 패딩 + 제동"),
        ("minimum_turn_clearance_m", outer,
         guard["minimum_turn_clearance_m"], "바깥 휩쓸이 반경 전체"),
        ("avoidance_engage_distance_m", front + padding + braking * 2.0,
         guard["avoidance_engage_distance_m"], "정지거리의 두 배 여유"),
    ]
    print(f"{'항목':<30}{'유도값':>9}{'현재값':>9}   판정 · 근거")
    for name, derived, current, why in rows:
        verdict = "부족" if current < derived else "여유"
        print(f"  {name:<28}{derived:9.3f}{float(current):9.3f}   "
              f"{verdict} · {why}")
    print()
    print("'부족' 은 실제로 필요한 것보다 늦게 반응한다는 뜻이다. '여유' 는")
    print("안전하지만, 2 m 폭 통로에서는 과하면 통과 자체가 막힌다.")


if __name__ == "__main__":
    raise SystemExit(main())
