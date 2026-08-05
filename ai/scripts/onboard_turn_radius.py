"""실효 회전반경 측정 (S15P11A304-152).

**왜 필요한가.** 설계가 쓰는 최소 회전반경 **537mm** 가 실물과 안 맞는다. 2026-08-05
재접근 실주행에서 최대 조향(28°)으로 후진하며 잰 값이 **중앙값 1200mm**(표본 12개,
724~2158)로 2.2배였다. 이 값 하나 위에 상수 네 개가 얹혀 있다:

    ALIGN_ENTER_MM · RUNWAY_PER_LATERAL · STEER_RADIUS_MM · retreat_step_for()

그날 종일 본 "ALIGN 구간이 모자라 미정렬로 진입 거리에 닿는다" 가 전부 여기서
설명될 수 있다. 그래서 **전진 반경을 같은 방법으로 다시 잰다.**

**왜 정렬 루프 로그로는 안 되나.** 거기서는 조향이 매 프레임 바뀌므로 "이 조향에서
반경이 얼마" 가 안 나온다. 고정 조향으로 따로 재야 한다.

**재는 법.** 경로 길이를 직접 못 재므로(카메라는 파렛트까지 거리만 준다) 두 구간으로
나눠 각각을 따로 잰다:

    ① 직진 구간 — 조향 0. `distance_mm` 시계열 기울기 = **실측 속도 v**
    ② 선회 구간 — 조향 고정. `yaw_deg` 시계열 기울기 = **실측 요레이트 ω**

    R = v / ω

⚠️ **속도를 명령값으로 쓰면 안 된다.** `linear.x` 는 PWM 퍼센트로 매핑될 뿐이라
명령 0.06 이 실제 0.15 m/s 다(S15P11A304-198). 그래서 ①을 매번 같이 잰다.

⚠️ **회귀는 최소제곱이다.** 요각은 정지 상태에서도 픽셀 양자화로 ±8.8° 튀므로
양 끝점 두 개로 기울기를 내면 노이즈가 그대로 답이 된다.

사용법 (젯슨):

    PYTHONPATH=src:$PYTHONPATH python3 scripts/onboard_turn_radius.py \\
        --engine ~/trt_test/onboard_s640_ep116_fp16.engine \\
        --plugin ~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so \\
        --camera 0 --rotate180

⚠️ 파렛트를 **정면으로** 두고 60~80cm 앞에서 시작한다. 선회 중 파렛트가 화면 밖으로
나가면 그 구간은 표본이 모자라 실패로 끝난다 — `--turn-s` 를 줄인다.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from control.fork_servo import MAX_ANGULAR, RETREAT_MAX_ANGULAR, STEER_RADIUS_MM
from perception.fork_align import TargetTracker
from perception.trt_detector import TrtDetector

FORK_CENTER_X = 670.0
FOCAL_PX = 1277.7


def _slope(samples: list[tuple[float, float]]) -> float | None:
    """최소제곱 기울기. 표본이 모자라거나 시간이 안 흐르면 None."""
    n = len(samples)
    if n < 5:
        return None
    mean_t = sum(t for t, _ in samples) / n
    mean_v = sum(v for _, v in samples) / n
    num = sum((t - mean_t) * (v - mean_v) for t, v in samples)
    den = sum((t - mean_t) ** 2 for t, _ in samples)
    if den <= 0.0:
        return None
    return num / den


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="실효 회전반경 측정")
    ap.add_argument("--engine", required=True)
    ap.add_argument("--plugin", required=True)
    ap.add_argument("--camera", type=int, default=0)
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--rotate180", action="store_true")
    ap.add_argument("--fork-center-x", type=float, default=FORK_CENTER_X)
    ap.add_argument("--focal-px", type=float, default=FOCAL_PX)
    ap.add_argument("--cmd-topic", default="/cmd_vel")
    ap.add_argument("--speed", type=float, default=0.06,
                    help="전진 속도 명령(m/s). ALIGN 과 같은 조건으로 재려면 0.06")
    ap.add_argument("--angular", type=float, default=None,
                    help="선회 구간 조향(rad/s). 기본은 그 방향의 최대값 "
                         f"(전진 {MAX_ANGULAR}, 후진 {RETREAT_MAX_ANGULAR})")
    ap.add_argument("--straight-s", type=float, default=2.0,
                    help="속도를 재는 직진 구간(초)")
    ap.add_argument("--turn-s", type=float, default=2.0,
                    help="요레이트를 재는 선회 구간(초). ⚠️ 길면 파렛트가 "
                         "화면 밖으로 나간다")
    ap.add_argument("--reverse", action="store_true",
                    help="후진으로 잰다. 2026-08-05 재접근 실측(1200mm)과 대조용")
    ap.add_argument("--dry-run", action="store_true",
                    help="계산만 하고 /cmd_vel 을 안 낸다")
    a = ap.parse_args(argv)

    speed = -abs(a.speed) if a.reverse else abs(a.speed)
    limit = RETREAT_MAX_ANGULAR if a.reverse else MAX_ANGULAR
    angular = a.angular if a.angular is not None else limit

    publisher = node = rclpy = twist_cls = None
    if not a.dry_run:
        import rclpy as _rclpy
        from geometry_msgs.msg import Twist
        rclpy = _rclpy
        rclpy.init()
        node = rclpy.create_node("turn_radius")
        publisher = node.create_publisher(Twist, a.cmd_topic, 10)
        twist_cls = Twist
        print(f"발행: {a.cmd_topic}", flush=True)
    else:
        print("⚠️ dry-run — /cmd_vel 을 내보내지 않는다", flush=True)

    cap = cv2.VideoCapture(a.camera)
    if not cap.isOpened():
        print(f"카메라 {a.camera}를 열 수 없습니다", flush=True)
        return 1
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, a.width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, a.height)
    got_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    print(f"카메라 {a.camera}: {got_w}x{int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))}",
          flush=True)

    detector = TrtDetector(a.engine, a.plugin, class_names=("pallet", "hole"),
                           rotate180=a.rotate180)
    tracker = TargetTracker()

    def publish(linear_x: float, angular_z: float) -> None:
        if publisher is None:
            return
        msg = twist_cls()
        msg.linear.x = float(linear_x)
        msg.angular.z = float(angular_z)
        publisher.publish(msg)

    for _ in range(15):          # 자동 노출 안정화
        cap.read()

    distances: list[tuple[float, float]] = []
    yaws: list[tuple[float, float]] = []
    started = time.perf_counter()
    print(f"직진 {a.straight_s:.1f}초(속도 측정) → 선회 {a.turn_s:.1f}초"
          f"(요레이트 측정) · v={speed:+.2f} w={angular:+.2f}", flush=True)

    try:
        while True:
            now = time.perf_counter()
            elapsed = now - started
            turning = elapsed >= a.straight_s
            if elapsed >= a.straight_s + a.turn_s:
                break

            publish(speed, angular if turning else 0.0)

            ok, frame = cap.read()
            if not ok:
                print("프레임 캡처 실패 — 정지", flush=True)
                break
            target = tracker.update(detector.detect(frame))
            if target is None:
                continue
            error = target.error(image_width=got_w,
                                 fork_center_x=a.fork_center_x,
                                 focal_px=a.focal_px)
            if error is None or error.distance_mm is None:
                continue

            # 구간별로 다른 값을 모은다. 직진 구간의 요각·선회 구간의 거리는
            # 쓰지 않는다 — 섞으면 두 기울기가 서로를 오염시킨다.
            if turning:
                if error.yaw_deg is not None:
                    yaws.append((elapsed, error.yaw_deg))
            else:
                distances.append((elapsed, error.distance_mm))

            mark = "선회" if turning else "직진"
            yaw_text = ("요 없음" if error.yaw_deg is None
                        else f"요 {error.yaw_deg:+6.1f}°")
            print(f"[{elapsed:5.2f}s] {mark}  {error.distance_mm:6.0f}mm  {yaw_text}",
                  flush=True)
    except KeyboardInterrupt:
        print("\n중단됨", flush=True)
    finally:
        # 어떤 경로로 끝나든 정지 명령을 낸다.
        publish(0.0, 0.0)
        cap.release()
        if node is not None:
            node.destroy_node()
            rclpy.shutdown()

    # `distance_mm` 은 가까울수록 작아지므로 전진이면 기울기가 음수다.
    d_slope = _slope(distances)
    y_slope = _slope(yaws)
    print(f"\n표본: 직진 {len(distances)}개 · 선회 {len(yaws)}개", flush=True)
    if d_slope is None or y_slope is None:
        print("⚠️ 표본이 모자라 계산 못 함 — 파렛트가 화면 안에 있었는지 확인한다",
              flush=True)
        return 2

    v_mps = abs(d_slope) / 1000.0
    yaw_rate = abs(y_slope)              # deg/s
    print(f"실측 속도    {v_mps:.3f} m/s   (명령 {abs(speed):.2f})", flush=True)
    print(f"실측 요레이트 {yaw_rate:.1f} °/s  (명령 {abs(angular):.2f} rad/s "
          f"= {abs(angular) * 57.3:.1f} °/s)", flush=True)
    if yaw_rate < 1.0:
        print("⚠️ 요레이트가 거의 0이다 — 조향이 안 들어갔다. 브리지·서보를 본다",
              flush=True)
        return 2

    radius_mm = v_mps * 1000.0 / (yaw_rate / 57.2958)
    print(f"\n**실효 회전반경 {radius_mm:.0f}mm**  "
          f"(설계값 {STEER_RADIUS_MM:.0f}mm 의 {radius_mm / STEER_RADIUS_MM:.1f}배)",
          flush=True)
    # 명령대로 돌았다면 요레이트가 명령 각속도와 같아야 한다. 크게 작으면 조향이
    # 명령만큼 안 들어간 것이므로 **제어가 아니라 서보·링키지를 봐야 한다.**
    achieved = yaw_rate / (abs(angular) * 57.2958)
    print(f"명령 대비 실제 회전 {achieved * 100:.0f}%", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
