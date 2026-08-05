"""조향 훑기 — 명령을 단계별로 바꿔가며 **바퀴가 어디까지 따라오나**를 본다.

> ✅ **아래 "왜"의 증상은 2026-08-05에 원인이 밝혀져 고쳐졌다** — 펌웨어
> `SERVO_MIN/MAX_PULSE_US` 가 1000~2000µs 였고 MG996R 은 500~2500µs 가 180°라
> 서보가 명령의 절반만 돌았다. 지금은 500~2500, 뒷바퀴 실각도 **36°**, 중립 **9000**,
> `rear_steering_limit_deg: 36.0` 이다. 이 스크립트는 진단 도구로 남긴 것이고,
> **아래 본문의 8°·28°·±30° 는 고치기 전 상태를 설명하는 기록**이다.
> 다시 쓸 때는 `--center-cdeg` 기본값이 현재 `teleop.yaml` 과 맞는지 확인할 것.

**왜.** 2026-08-05 실측에서 최대 조향을 명령했는데 뒷바퀴가 **8°** 밖에 안 꺾였다
(명령 28°). 실효 회전반경도 1200~1450mm 로 설계값 537mm 의 2~3배였고, 세 숫자가
`tan(8°)/tan(28°) = 25%` 로 정확히 맞물린다. 남은 질문은 **어디서 안 따라오기
시작하나** 다:

- 작은 각도부터 **비례해서 따라오다가** 어느 지점부터 안 늘면 → 그 지점이 링키지·
  서보의 물리 한계다
- 처음부터 **비례가 3.7:1 로 눌려 있으면** → 링키지 기하가 통째로 줄이는 것이다

⚠️ **명령 각속도(`angular_z`)는 조향각이 아니다.** `map_twist` 가 곡률로 바꾼다:

    곡률 = angular_z / linear_x,   뒷바퀴각 = atan(축간거리 × 곡률)

그래서 **같은 `angular_z` 도 속도에 따라 다른 각도**가 된다. `v=0.02` 에서는
`ω=-0.1` 이 이미 35.8° 를 요구해 한계(28°)에 걸린다 — 거기서부터 -1.0 까지는
전부 같은 값이라 훑어도 변화가 없다. 그래서 이 스크립트는 **각 단계에서 노리는
바퀴각과 서보 명령값을 같이 찍는다.** 눈으로 본 것과 그 숫자를 대조하면 된다.

사용법 (젯슨, 앞바퀴를 띄워 놓고):

    # 변화가 보이는 구간
    PYTHONPATH=src:$PYTHONPATH python3 scripts/steer_sweep.py \\
        --start -0.005 --stop -0.06 --step -0.005 --hold 2.0

    # 요청한 구간(전 구간 포화 — 확인용)
    PYTHONPATH=src:$PYTHONPATH python3 scripts/steer_sweep.py \\
        --start -0.1 --stop -1.0 --step -0.01 --hold 0.5

⚠️ 앞바퀴(구동륜)를 띄우지 않으면 차가 굴러간다. `linear.x` 가 0이면 브리지가 조향을
중립으로 되돌리므로(데드밴드 0.01) **명령을 살려두려면 구동이 돌아야 한다.**
"""

from __future__ import annotations

import argparse
import math
import time


def rear_angle_deg(linear_x: float, angular_z: float, wheelbase_m: float,
                   limit_deg: float) -> tuple[float, bool]:
    """이 명령이 노리는 뒷바퀴 각도(도)와 **한계에 걸렸는지**.

    `map_twist` 와 같은 식이다. 여기서 따로 계산하는 이유는, 브리지가 잘라낸 뒤의
    값만 보면 "원래 얼마를 요구했는지" 가 안 보이기 때문이다.
    """
    if abs(linear_x) < 1e-9:
        return 0.0, False
    curvature = angular_z / linear_x
    wanted = math.degrees(math.atan(wheelbase_m * curvature))
    clipped = max(-limit_deg, min(limit_deg, wanted))
    return clipped, abs(wanted) > limit_deg


def servo_cdeg(angle_deg: float, limit_deg: float, center: int,
               lo: int, hi: int) -> int:
    """`map_twist` 의 서보 출력. 부호 규약(음의 뒷바퀴각 → 큰 서보값)까지 같게 맞췄다."""
    ratio = min(1.0, abs(angle_deg) / limit_deg) if limit_deg > 0 else 0.0
    direction = -1 if angle_deg > 0.0 else 1
    span = (hi - center) if direction > 0 else (center - lo)
    return int(center + direction * round(ratio * span))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="조향 훑기")
    ap.add_argument("--cmd-topic", default="/cmd_vel")
    ap.add_argument("--speed", type=float, default=0.02,
                    help="구동 명령(m/s). ⚠️ 0 이면 브리지가 조향을 중립으로 "
                         "되돌린다(데드밴드 0.01) — 앞바퀴를 띄워두고 굴린다")
    ap.add_argument("--start", type=float, default=-0.005)
    ap.add_argument("--stop", type=float, default=-0.06)
    ap.add_argument("--step", type=float, default=-0.005)
    ap.add_argument("--hold", type=float, default=2.0,
                    help="각 단계를 유지하는 시간(초). 눈으로 보고 잴 만큼 준다")
    ap.add_argument("--rate", type=float, default=20.0,
                    help="발행 주기(Hz). 브리지 워치독이 0.5초라 그보다 빨라야 한다")
    # 기본값은 teleop.yaml 과 같다(2026-08-05 펄스 범위 수정 이후 값).
    # 그쪽을 바꿨으면 여기도 같이 준다.
    ap.add_argument("--wheelbase", type=float, default=0.144)
    ap.add_argument("--limit-deg", type=float, default=36.0)
    ap.add_argument("--center-cdeg", type=int, default=9000)
    ap.add_argument("--min-cdeg", type=int, default=5400)
    ap.add_argument("--max-cdeg", type=int, default=12600)
    ap.add_argument("--dry-run", action="store_true",
                    help="발행 없이 표만 찍는다 — 어느 구간이 포화인지 먼저 볼 때")
    a = ap.parse_args(argv)

    if a.step == 0.0:
        print("--step 이 0이면 끝나지 않는다", flush=True)
        return 1
    if (a.stop - a.start) / a.step < 0:
        print("--step 의 부호가 --start → --stop 방향과 반대다", flush=True)
        return 1

    steps: list[float] = []
    value = a.start
    while (value <= a.stop + 1e-9) if a.step > 0 else (value >= a.stop - 1e-9):
        steps.append(round(value, 4))
        value += a.step

    publisher = node = rclpy = twist_cls = None
    if not a.dry_run:
        import rclpy as _rclpy
        from geometry_msgs.msg import Twist
        rclpy = _rclpy
        rclpy.init()
        node = rclpy.create_node("steer_sweep")
        publisher = node.create_publisher(Twist, a.cmd_topic, 10)
        twist_cls = Twist
        print(f"발행: {a.cmd_topic} · {len(steps)}단계 × {a.hold:.1f}초 "
              f"= 약 {len(steps) * a.hold:.0f}초", flush=True)
    else:
        print(f"⚠️ dry-run — 발행 없음 · {len(steps)}단계", flush=True)

    def publish(linear_x: float, angular_z: float) -> None:
        if publisher is None:
            return
        msg = twist_cls()
        msg.linear.x = float(linear_x)
        msg.angular.z = float(angular_z)
        publisher.publish(msg)

    print(f"\n{'angular':>9} {'노리는 각도':>11} {'서보':>7}   비고", flush=True)
    period = 1.0 / a.rate
    saturated_from: float | None = None
    try:
        for omega in steps:
            angle, clipped = rear_angle_deg(a.speed, omega, a.wheelbase,
                                            a.limit_deg)
            cdeg = servo_cdeg(angle, a.limit_deg, a.center_cdeg,
                              a.min_cdeg, a.max_cdeg)
            note = ""
            if clipped:
                note = "한계 포화 — 여기부터는 아무리 키워도 같다"
                if saturated_from is None:
                    saturated_from = omega
            print(f"{omega:>9.3f} {angle:>10.1f}° {cdeg:>7d}   {note}", flush=True)

            if a.dry_run:
                continue
            until = time.perf_counter() + a.hold
            while time.perf_counter() < until:
                publish(a.speed, omega)
                time.sleep(period)
    except KeyboardInterrupt:
        print("\n중단됨", flush=True)
    finally:
        # 어떤 경로로 끝나든 정지·중립으로 되돌린다.
        publish(0.0, 0.0)
        if node is not None:
            node.destroy_node()
            rclpy.shutdown()

    if saturated_from is not None:
        print(f"\n⚠️ {saturated_from:+.3f} 부터 한계 포화다. 그 뒤 단계는 서보 명령이 "
              f"모두 같으므로, 바퀴가 안 변하는 것이 정상이다.", flush=True)
    print("바퀴가 '노리는 각도' 를 따라오는지 대조한다. 작은 각도부터 비례가 "
          "안 맞으면 링키지 기하가 줄이는 것이다.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
