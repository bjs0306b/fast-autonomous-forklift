"""포크 정렬 ROS2 노드 — 카메라 → 검출 → 타깃 선택 → 제어 → `/cmd_vel` (S15P11A304-152).

`onboard_live.py`가 검출까지 관통한 그 경로에 타깃 선택(143)과 제어 루프(152)를 이어
붙인 것이다. 젯슨에서 돈다.

    # 젯슨에서, ai/ 를 PYTHONPATH에 두고
    PYTHONPATH=src python3 scripts/onboard_fork_align_node.py \
        --engine ~/trt_test/onboard_s640_ep116_fp16.engine \
        --plugin ~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so \
        --camera 0 --rotate180 --dry-run

⚠️ **처음에는 반드시 `--dry-run`으로 돌린다.** 명령을 계산해 찍기만 하고 `/cmd_vel`을
내보내지 않는다. 부호가 반대면 지게차가 파렛트로 돌진한다 — 화면 로그로 좌우 부호부터
확인하고 나서 떼어낼 것.

⚠️ **`uart_teleop_bridge`는 500ms 무명령이면 자동 정지**한다. 그래서 정지 상태에서도
0 Twist를 계속 낸다 — 안 내면 브리지가 타임아웃으로 멈추는데, 그건 우리가 의도한
정지와 구분되지 않아 디버깅이 어려워진다.

⚠️ 제어 주기는 카메라·추론 속도가 정한다(**실측 22fps**). 고정 주기 타이머를 쓰지 않고
프레임이 오는 대로 돌며 실제 `dt`를 제어기에 넘긴다.
"""
from __future__ import annotations

import argparse
import time
from pathlib import Path

import cv2

from control.fork_servo import ForkServo, Phase
from perception.fork_align import TargetTracker
from perception.trt_detector import TrtDetector

FORK_CENTER_X = 670.0
"""포크 중심선의 화면 x (1280 폭 기준). **2026-08-04 실측.**

카메라가 포크 중심선에서 약 **7mm 오른쪽**에 달려 있다. 화면 중앙(640)이 아니다.

측정법: 포크를 구멍에서 빼고 **좌우로는 안 움직인 채 뒤로만** 물린 뒤
(= "이대로 직진하면 꽂히는" 정렬 상태) 진입면 중심 x 를 읽는다.
184프레임 중앙값 670.3, 흔들림 1.2px.
⚠️ 포크를 꽂은 채로는 못 잰다 — 포크가 구멍을 가려 검출이 0 이 된다.

⚠️ **이 값을 안 주고 640 을 쓰면 정렬이 영영 안 끝난다.** 진입면 반폭이 약 102px
이므로 30px 오프셋은 `lateral_ratio ≈ +0.30` 이고, 허용치는 0.12 다. 즉 **완벽히
정렬해도 항상 미정렬로 판정**돼 진입 거리에서 ABORT 한다. 게인을 아무리 만져도
안 고쳐지는 종류다.

카메라를 다시 달았으면 반드시 재측정한다."""

FOCAL_PX = 1277.7
"""가로 초점거리(px). 체커보드 캘리브레이션 값 — `docs/ai/measurement/camera-calibration.md`.

거리·요각 계산에 쓴다. 안 주면 화면 폭(px) 기준 상대값으로만 돌아간다."""


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="포크 정렬 제어 루프")
    ap.add_argument("--engine", required=True)
    ap.add_argument("--plugin", required=True)
    ap.add_argument("--camera", type=int, default=0)
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--rotate180", action="store_true")
    ap.add_argument("--fork-center-x", type=float, default=FORK_CENTER_X,
                    help=f"포크 중심선의 화면 x (기본 {FORK_CENTER_X:.0f}, 2026-08-04 실측). "
                         "카메라를 다시 달았으면 반드시 재측정한다")
    ap.add_argument("--focal-px", type=float, default=FOCAL_PX,
                    help=f"가로 초점거리(px) (기본 {FOCAL_PX:.1f}, 체커보드 캘리브레이션). "
                         "주면 요각을 도(deg)로, 거리를 mm로 낸다")
    ap.add_argument("--cmd-topic", default="/cmd_vel")
    ap.add_argument("--dry-run", action="store_true",
                    help="계산만 하고 /cmd_vel을 내보내지 않는다 (첫 실행은 반드시 이걸로)")
    ap.add_argument("--max-seconds", type=float, default=60.0)
    ap.add_argument("--save-dir", type=Path, help="ABORT/DONE 시 마지막 프레임 저장")
    a = ap.parse_args(argv)

    publisher = node = rclpy = None
    if not a.dry_run:
        import rclpy as _rclpy
        from geometry_msgs.msg import Twist
        rclpy = _rclpy
        rclpy.init()
        node = rclpy.create_node("fork_align")
        publisher = node.create_publisher(Twist, a.cmd_topic, 10)
        twist_cls = Twist
        print(f"발행: {a.cmd_topic}", flush=True)
    else:
        twist_cls = None
        print("⚠️ dry-run — /cmd_vel을 내보내지 않는다", flush=True)

    cap = cv2.VideoCapture(a.camera)
    if not cap.isOpened():
        print(f"카메라 {a.camera}를 열 수 없습니다. /dev/video* 확인", flush=True)
        return 1
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, a.width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, a.height)
    got_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    got_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    print(f"카메라 {a.camera}: {got_w}x{got_h}", flush=True)

    detector = TrtDetector(a.engine, a.plugin, class_names=("pallet", "hole"),
                           rotate180=a.rotate180)
    tracker = TargetTracker()
    servo = ForkServo()

    def publish(linear_x: float, angular_z: float) -> None:
        if publisher is None:
            return
        msg = twist_cls()
        msg.linear.x = float(linear_x)
        msg.angular.z = float(angular_z)
        publisher.publish(msg)

    for _ in range(15):        # 자동 노출 안정화
        cap.read()

    started = time.perf_counter()
    last = started
    frames = 0
    last_frame = None
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                print("프레임 캡처 실패 — 정지", flush=True)
                break
            last_frame = frame
            now = time.perf_counter()
            dt, last = now - last, now
            frames += 1

            target = tracker.update(detector.detect(frame))
            error = None
            if target is not None:
                error = target.error(image_width=got_w,
                                     fork_center_x=a.fork_center_x,
                                     focal_px=a.focal_px)
            cmd = servo.step(error, dt)
            publish(cmd.linear_x, cmd.angular_z)

            if error is None:
                detail = "타깃 없음"
            else:
                detail = (f"lat {error.lateral_ratio:+.2f} yaw {error.yaw_signal:+.2f} "
                          f"폭 {error.approach_px:5.0f}px")
                if error.yaw_deg is not None:
                    detail += f" ({error.yaw_deg:+.1f}° {error.distance_mm:.0f}mm)"
            print(f"[{frames:4d}] {1/dt if dt else 0:4.1f}fps  {cmd.phase.value:8s} "
                  f"v {cmd.linear_x:+.2f} w {cmd.angular_z:+.2f}  {detail}  {cmd.reason}",
                  flush=True)

            if servo.is_finished():
                print(f"\n종료: {cmd.phase.value} ({servo.episode.outcome})", flush=True)
                break
            if now - started > a.max_seconds:
                print("\n제한 시간 초과 — 정지", flush=True)
                break
    except KeyboardInterrupt:
        print("\n중단됨", flush=True)
    finally:
        # **어떤 경로로 끝나든 정지 명령을 낸다.** 예외로 빠져나가면서 마지막 속도가
        # 브리지에 남아 있으면 지게차가 계속 굴러간다(500ms 뒤 워치독이 잡긴 하지만
        # 그 사이에 파렛트를 들이받기 충분하다).
        publish(0.0, 0.0)
        cap.release()
        if node is not None:
            node.destroy_node()
            rclpy.shutdown()

    if a.save_dir and last_frame is not None:
        a.save_dir.mkdir(parents=True, exist_ok=True)
        out = a.save_dir / f"fork_align_{servo.phase.value}.jpg"
        cv2.imwrite(str(out), last_frame)
        print(f"마지막 프레임 → {out}", flush=True)

    print(f"프레임 {frames}장 · 기록 {len(servo.episode.samples)}건", flush=True)

    # 종료 코드는 **성공한 것만 0**이다. 종전에 SEARCH 도 0 이었는데, 그러면
    # *파렛트를 한 번도 못 보고 제한 시간을 넘긴 실행*이 성공으로 보고된다 —
    # 154 의 성공률 집계가 조용히 부풀려진다. 원인별로 나눠 사람이 볼 곳을 가른다.
    #
    #   0  DONE    진입 완료
    #   2  ABORT   진입 거리에서 미정렬 — 물러나 재접근(154)
    #   3  SEARCH  타깃을 못 찾고 종료 — 조명·파렛트 배치·검출을 본다
    if servo.phase is Phase.DONE:
        return 0
    if servo.phase is Phase.SEARCH:
        print("⚠️ 파렛트를 찾지 못한 채 끝났다 — 실패로 집계한다", flush=True)
        return 3
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
