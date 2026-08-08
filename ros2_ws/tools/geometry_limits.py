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


def handover(teleop, guard, local, footprint):
    """Print everything a simulator needs to model this vehicle.

    Read from the live config rather than copied into a document. The moment
    a spec sheet carries its own numbers there are two sources of truth, and
    the copy is wrong from the first change nobody propagates -- which has
    already happened here more than once (max_linear_mps moved and the fork
    insertion silently started over-pushing the pallet).

    Regenerate with `tools/geometry_limits.py --handover` rather than editing
    the output by hand.
    """
    front = max(point[0] for point in footprint)
    rear = -min(point[0] for point in footprint)
    half_width = max(abs(point[1]) for point in footprint)
    padding = float(local.get("footprint_padding", 0.0))
    wheelbase = float(teleop["wheelbase_m"])
    steer_limit = math.radians(float(teleop["rear_steering_limit_deg"]))
    radius = wheelbase / math.tan(steer_limit)
    # 패딩을 포함해 낸다 -- 가드의 minimum_turn_clearance_m 가 그 값이라,
    # 여기서 뺀 값을 내면 같은 항목이 두 숫자를 갖게 된다.
    tail_swing = math.hypot(rear + padding,
                            radius + half_width + padding) - radius

    print("# 실차 제원 — tools/geometry_limits.py --handover 로 생성")
    print("#")
    print("# ⚠️ 손으로 고치지 말 것. 설정이 바뀌면 다시 돌려서 갈아끼운다.")
    print()
    print("## 좌표 규약")
    print("  base_link      전륜 구동축 중심, 바닥에서 30 mm 위 (REP-103)")
    print(f"  후륜(조향)축   x = {-wheelbase:+.3f} m")
    print("  yaw            +x 가 0, 반시계 +, 라디안 (−π ~ +π)")
    print()
    print("## 치수 (base_link 기준, m)")
    print(f"  앞끝 {front:+.3f} · 뒤끝 {-rear:+.3f} · 반폭 {half_width:.3f}"
          f" · 전장 {front + rear:.3f} · 전폭 {half_width * 2:.3f}")
    print(f"  축거 {wheelbase:.3f}")
    print()
    print("## 조향 (후륜 조향)")
    print(f"  최대 조향각      {math.degrees(steer_limit):.0f}°")
    print(f"  최소 회전반경    {radius:.3f} m   (= 축거 / tan(조향각))")
    print(f"  꼬리 휨          {tail_swing:.3f} m  (패딩 {padding:.3f} 포함)")
    print("    회전 시 바깥 뒷모서리가 회전반경보다 이만큼 더 나간다.")
    print("    후륜 조향차의 특징이라 시뮬에서도 반드시 재현해야 한다.")
    print()
    print("## 속도")
    print(f"  구동계 최대      {float(teleop['max_linear_mps']):.2f} m/s"
          f"  (duty {float(teleop['max_drive_percent']):.0f}% 에서 실측)")
    print("  안정 최저        0.078 m/s  (그 아래로는 유지 못 함)")
    print(f"  명령 상한        nav2_params.yaml velocity_smoother max_velocity")
    print()
    print("## 안전거리 (실측 기하에서 유도)")
    print(f"  전방 정지        {float(guard['stop_distance_m']):.2f} m")
    print(f"  후방 정지        {float(guard['rear_stop_distance_m']):.2f} m")
    print(f"  회전 여유        {float(guard['minimum_turn_clearance_m']):.2f} m"
          f"  (꼬리 휨 포함)")
    print()
    print("## 축척")
    print("  시뮬 = 실물 x 10 (2x3 m 목업 <-> 20x30 m 창고).")
    print("  위치만 곱한다. 각도는 축척과 무관하므로 그대로 보낸다.")
    print()
    print("## 이 값들이 사는 곳")
    print("  치수·조향·속도    ros2_ws/src/forklift_teleop/config/teleop.yaml")
    print("  footprint         ros2_ws/nav2_params.yaml")
    print("  안전거리          .../config/obstacle_avoidance.yaml")
    print("  좌표 규약         docs/센서-측정-칼리브레이션.md §1")
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--speed", type=float, default=None,
                        help="제동거리 계산에 쓸 주행 속도 (기본 max_linear_mps)")
    parser.add_argument("--reaction", type=float, default=None,
                        help="반응 시간 s (기본 1/control_rate + command_timeout)")
    parser.add_argument("--handover", action="store_true",
                        help="시뮬 차량 모델에 넣을 제원 일체를 출력")
    args = parser.parse_args()

    teleop, guard, local, footprint = load()
    if args.handover:
        return handover(teleop, guard, local, footprint)

    front = max(point[0] for point in footprint)
    rear = -min(point[0] for point in footprint)
    half_width = max(abs(point[1]) for point in footprint)
    padding = float(local.get("footprint_padding", 0.0))

    wheelbase = float(teleop["wheelbase_m"])
    steer_limit = math.radians(float(teleop["rear_steering_limit_deg"]))
    speed = args.speed if args.speed is not None else float(
        teleop["max_linear_mps"]
    )

    # ⚠️ 예전에는 여기에 command_timeout_sec(0.5) 을 더했다. 그건 **명령이
    #    끊겼을 때** 브리지가 기다리는 시간이지, 가드가 멈추기로 했을 때의
    #    지연이 아니다 -- 그때 가드는 0 을 능동적으로 보낸다. 3배 이상 보수적인
    #    값이었고, 그 때문에 속도를 못 올리고 조향각까지 묶고 있었다.
    #
    # 실제 사슬은 각 20 Hz 인 세 단계다: 가드 -> drive_mux -> 브리지. 거기에
    # 감속에 걸리는 시간을 얹어 여유를 둔다.
    STAGES = 3
    DECELERATION_MARGIN_SEC = 0.10
    rate = float(guard["control_rate_hz"])
    reaction = args.reaction if args.reaction is not None else (
        STAGES / rate + DECELERATION_MARGIN_SEC
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
    print(f"  (반응 = 20 Hz 세 단계 {STAGES/rate:.2f} s + 감속 여유 "
          f"{DECELERATION_MARGIN_SEC:.2f} s)")
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
