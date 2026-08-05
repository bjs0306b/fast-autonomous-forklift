"""포크 정렬 ROS2 노드 — 카메라 → 검출 → 타깃 선택 → 제어 → `/cmd_vel` (S15P11A304-152).

`onboard_live.py`가 검출까지 관통한 그 경로에 타깃 선택(143)과 제어 루프(152)를 이어
붙인 것이다. 젯슨에서 돈다.

    # 젯슨에서, ai/ 에서
    export ROS_DOMAIN_ID=100
    PYTHONPATH=src:$PYTHONPATH python3 scripts/onboard_fork_align_node.py \
        --engine ~/trt_test/onboard_s640_ep116_fp16.engine \
        --plugin ~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so \
        --camera 0 --rotate180 --dry-run

⚠️ **`PYTHONPATH=src:$PYTHONPATH` 다 — `PYTHONPATH=src` 가 아니다.** 맨 앞만 쓰면 ROS
경로를 통째로 덮어써 `ModuleNotFoundError: rclpy` 가 난다. 종전에 이 docstring 이
`PYTHONPATH=src` 로 적혀 있어 2026-08-04 에 그대로 따라 하다 두 번 밟았다.

⚠️ **`ROS_DOMAIN_ID=100` 이 필요하다**(`docs/deploy/ros-domain.md`). 안 맞으면
`/cmd_vel` 구독자가 0이라 **차가 안 움직이는데 에러도 안 난다.**

준비·함정·반복 시험 절차는 `docs/ai/onboard-fork-align-runbook.md` 참조.

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
from perception.fork_align import TargetTracker, YawSmoother
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
    ap.add_argument("--align-speed", type=float, default=None,
                    help="ALIGN 구간 전진 속도(m/s). 안 주면 fork_servo 기본값. "
                         "⚠️ 0.02 미만은 drive 35% 아래라 차가 멎고 스스로 못 "
                         "출발한다 (S15P11A304-198)")
    ap.add_argument("--k-lateral", type=float, default=None,
                    help="좌우 오차 게인. 안 주면 fork_servo 기본값")
    ap.add_argument("--k-lateral-rate", type=float, default=None,
                    help="좌우 오차 **변화율**에 걸리는 감쇠 게인. 기본 0(꺼짐). "
                         "ALIGN 한계진동이 감쇠 부재 때문인지 시험할 때 켠다 — "
                         "먼저 --k-lateral 0.10 으로 게인 문제인지부터 가른다")
    ap.add_argument("--insert-margin", type=float, default=None,
                    help="진입 목표에서 빼는 여유(mm). 줄일수록 깊이 들어간다. "
                         "⚠️ 한 번에 많이 줄이면 파렛트를 민다 — 한 단계씩")
    ap.add_argument("--lateral-tolerance", type=float, default=None,
                    help="진입 허용 좌우 오차(기본 0.25). ⚠️ **튜닝 값이 아니라 "
                         "시험용 손잡이다** — 작게 주면 정상 정렬도 미정렬로 판정돼 "
                         "재접근(154)이 확실히 발동한다. 실주행 기본값으로 쓰지 말 것")
    ap.add_argument("--max-retries", type=int, default=None,
                    help="진입 거리에서 미정렬일 때 물러나 재접근하는 횟수 "
                         "(S15P11A304-154, 기본 2). 0 이면 종전처럼 바로 ABORT")
    ap.add_argument("--retreat-target", type=float, default=None,
                    help="후진 목표 거리(mm, 기본 700 = ALIGN 진입 거리). "
                         "⚠️ 줄이면 재접근 때 정렬 구간이 그만큼 짧아져 같은 이유로 "
                         "또 실패한다")
    ap.add_argument("--retreat-max-s", type=float, default=None,
                    help="후진 시간 상한(초, 기본 4.0 ≈ 71cm). 목표가 아니라 "
                         "**안전 상한**이다 — 거리 판정이 죽었을 때만 걸린다. "
                         "⚠️ 뒤는 카메라가 안 본다")
    ap.add_argument("--retreat-steer", type=float, default=None,
                    help="후진 중 조향 세기. **1.0 = 최대 조향까지 쓴다**(기본 0 = "
                         "곧게 물러남). ⚠️ **부호 미검증** — 1.0 으로 돌려 후진 구간 "
                         "yaw_deg 절댓값이 줄면 맞고, 커지면 -1.0 으로 뒤집는다")
    ap.add_argument("--yaw-deg-tolerance", type=float, default=None,
                    help="진입을 허가할 요각 상한(도, 기본 10). ⚠️ 이게 없던 동안 "
                         "요각 -58.5° 인 채로 진입이 허가돼 포크가 비스듬히 스쳤다")
    ap.add_argument("--stall-window", type=float, default=None,
                    help="이 시간(초) 동안 거리가 안 변하면 '멎었다'로 중단한다 "
                         "(기본 3.0). 0 이면 끈다 — 차를 손으로 밀며 시험할 때만")
    ap.add_argument("--yaw-window", type=int, default=5,
                    help="요각 시간 평활 창(프레임). 1 이면 평활 없음(생값). "
                         "정지 상태에서도 요각이 ±8.8° 튀는 픽셀 양자화 노이즈를 "
                         "줄인다 — 대신 창의 절반만큼 반응이 늦다")
    ap.add_argument("--subscriber-wait-s", type=float, default=3.0,
                    help="시작 시 /cmd_vel 구독자를 기다리는 시간(초). 0 이면 안 "
                         "기다린다. ⚠️ 구독자가 없으면 명령이 허공으로 가는데 "
                         "로그는 정상으로 찍힌다 — 그래서 여기서 먼저 끊는다")
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

        # ⚠️ **구독자가 없으면 명령이 허공으로 간다 — 그런데 로그는 정상으로 찍힌다.**
        # 2026-08-05 에 이 실패를 세 번 만났다(브리지 사망 · UART 링크 이상 ·
        # protocol.py 예외로 프로세스 종료). 매번 60~90초를 정상처럼 돌다가 끝났고,
        # 원인이 로그 어디에도 안 남았다. 여기서 먼저 끊는다.
        for _ in range(int(a.subscriber_wait_s / 0.1)):
            if publisher.get_subscription_count() > 0:
                break
            time.sleep(0.1)
        if publisher.get_subscription_count() == 0:
            print(f"❌ {a.cmd_topic} 구독자가 없다 — 모터 브리지가 죽었다.\n"
                  "   확인:  pgrep -cf \"lib/forklift_teleop/uart_teleop\"\"_bridge\"\n"
                  "   1 이 아니면 teleop_uart.launch.py 를 다시 띄운다.",
                  flush=True)
            node.destroy_node()
            rclpy.shutdown()
            return 5
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
    servo_kwargs = {}
    if a.align_speed is not None:
        servo_kwargs["align_speed"] = a.align_speed
    if a.k_lateral is not None:
        servo_kwargs["k_lateral"] = a.k_lateral
    if a.insert_margin is not None:
        servo_kwargs["insert_margin_mm"] = a.insert_margin
    if a.k_lateral_rate is not None:
        servo_kwargs["k_lateral_rate"] = a.k_lateral_rate
    if a.lateral_tolerance is not None:
        servo_kwargs["lateral_tolerance"] = a.lateral_tolerance
    if a.max_retries is not None:
        servo_kwargs["max_retries"] = a.max_retries
    if a.retreat_target is not None:
        servo_kwargs["retreat_target_mm"] = a.retreat_target
    if a.retreat_max_s is not None:
        servo_kwargs["retreat_max_s"] = a.retreat_max_s
    if a.retreat_steer is not None:
        servo_kwargs["retreat_steer_gain"] = a.retreat_steer
    if a.yaw_deg_tolerance is not None:
        servo_kwargs["yaw_deg_tolerance"] = a.yaw_deg_tolerance
    if a.stall_window is not None:
        servo_kwargs["stall_window_s"] = a.stall_window
    servo = ForkServo(**servo_kwargs)
    smoother = YawSmoother(a.yaw_window)
    if servo_kwargs:
        # 기본값과 다른 값으로 돌고 있다는 것을 화면에 남긴다 — 나중에 로그만
        # 보고 "그때 무슨 값이었지" 를 되짚을 수 있어야 한다.
        print("설정 덮어씀: "
              + ", ".join(f"{k}={v}" for k, v in servo_kwargs.items()),
              flush=True)

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
    reported_retreats = 0
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
            raw_yaw_deg = error.yaw_deg if error is not None else None
            # 제어도 로그도 **평활된 값**을 본다. 생값과 섞어 쓰면 나중에 로그를
            # 보고 "제어가 무엇을 봤나" 를 되짚을 수 없다.
            error = smoother.update(error)
            cmd = servo.step(error, dt)
            publish(cmd.linear_x, cmd.angular_z)

            if error is None:
                detail = "타깃 없음"
            else:
                detail = (f"lat {error.lateral_ratio:+.2f} yaw {error.yaw_signal:+.2f} "
                          f"폭 {error.approach_px:5.0f}px")
                if error.yaw_deg is not None:
                    detail += f" ({error.yaw_deg:+.1f}° {error.distance_mm:.0f}mm)"
                    # 평활 전 값도 같이 남긴다 — 노이즈가 얼마나 줄었는지 로그만
                    # 보고 판단할 수 있어야 한다.
                    if raw_yaw_deg is not None and a.yaw_window > 1:
                        detail += f" [생 {raw_yaw_deg:+.1f}°]"
            print(f"[{frames:4d}] {1/dt if dt else 0:4.1f}fps  {cmd.phase.value:8s} "
                  f"v {cmd.linear_x:+.2f} w {cmd.angular_z:+.2f}  {detail}  {cmd.reason}",
                  flush=True)

            # 후진이 실제로 무엇을 했는지(물러난 거리·요각 변화·실효 반경).
            # 모델과 크게 다르면 조향이 명령대로 안 들어간다는 뜻이라, 매번 눈에
            # 띄는 자리에 남긴다.
            while len(servo.retreat_reports) > reported_retreats:
                print("    ↩ " + servo.retreat_reports[reported_retreats], flush=True)
                reported_retreats += 1

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

    print(f"프레임 {frames}장 · 기록 {len(servo.episode.samples)}건 "
          f"· 재접근 {servo.retries}회", flush=True)

    # 종료 코드는 **성공한 것만 0**이다. 종전에 SEARCH 도 0 이었는데, 그러면
    # *파렛트를 한 번도 못 보고 제한 시간을 넘긴 실행*이 성공으로 보고된다 —
    # 154 의 성공률 집계가 조용히 부풀려진다. 원인별로 나눠 사람이 볼 곳을 가른다.
    #
    #   0  DONE    진입 완료 (재접근을 거쳤어도 성공은 성공이다 — 횟수는 위에 찍힌다)
    #   2  ABORT   재접근을 소진했거나 후진 자체가 실패 — `outcome` 을 본다
    #              misaligned_at_insert / retreat_timeout / lost_while_retreating
    #   3  SEARCH  타깃을 못 찾고 종료 — 조명·파렛트 배치·검출을 본다
    #   4  STALLED 명령은 나가는데 차가 안 움직였다 — **제어가 아니라 아래를 본다**
    #
    # 4 를 2 와 가른 이유: 멎음은 정렬 실패가 아니라 **하드웨어 실패**라 사람이 볼
    # 곳이 완전히 다르다. 2026-08-05 에 이걸 안 갈라서 세 번 다 "정렬이 왜 안 되지"
    # 로 시간을 태웠다. 실제 원인은 브리지 사망과 UART 링크였다.
    if servo.episode.outcome == "stalled":
        print("⚠️ 차가 멎었다 — 브리지(pgrep uart_teleop_bridge) · 배터리 전압 · "
              "UART 배선을 본다. 제어 파라미터는 원인이 아니다", flush=True)
        return 4
    if servo.phase is Phase.DONE:
        return 0
    if servo.phase is Phase.SEARCH:
        print("⚠️ 파렛트를 찾지 못한 채 끝났다 — 실패로 집계한다", flush=True)
        return 3
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
