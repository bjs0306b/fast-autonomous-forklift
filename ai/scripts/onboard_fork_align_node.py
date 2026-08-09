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
import math
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




REAR_SECTOR_DEG = (75.0, 105.0)
"""후방으로 보는 `laser_frame` 각도 구간(도).

⚠️ **라이다가 90° 돌아 달려 있다** — 0° 가 차체 앞이 아니라 왼쪽(base_link +Y)을
가리킨다(`lidar_odometry.launch.py` 의 `LIDAR_ROTATION`). 그래서 차체 뒤(180°)는
laser 기준 **+90°** 다.

2026-08-07 실측으로 확인했다: 뒤에만 벽이 있는 상태에서 laser +60~+120° 만 0.40m 로
나오고 나머지는 0.8~1.7m 였다. 부호를 추측하면 **가드가 엉뚱한 쪽을 보고도 조용히
정상처럼 동작한다.**

⚠️ 구간을 60~120 에서 **75~105 으로 좁혔다**(08-07). 넓게 두면 옆에 있는 물체까지
후방으로 세어 정상 후진을 자주 끊었다 — 실주행 한 판에서 5번 걸렸는데 실제로는
부딪힐 거리가 아니었다."""


def _rear_min_range(msg) -> float | None:
    """스캔 한 장에서 **뒤쪽 최소거리**(m). 유효한 점이 없으면 None.

    ⚠️ None 을 0 이나 큰 값으로 바꾸지 말 것 — 0 이면 영영 못 물러나고, 큰 값이면
    가드가 없는 것과 같다. 모르면 모른다고 해야 호출자가 정할 수 있다.
    """
    import math
    lo, hi = REAR_SECTOR_DEG
    best = None
    for i, r in enumerate(msg.ranges):
        if not (msg.range_min < r < msg.range_max):
            continue
        if math.isinf(r) or math.isnan(r):
            continue
        deg = math.degrees(msg.angle_min + i * msg.angle_increment)
        deg = (deg + 180.0) % 360.0 - 180.0
        if lo <= deg <= hi and (best is None or r < best):
            best = r
    return best



def _engagement_problem(error, servo, lateral_max: float) -> str | None:
    """들어올려도 되나 — **문제가 있으면 그 이유**, 없으면 None.

    진입은 개루프라 "갔다" 와 "들어갔다" 가 다르다. 마지막으로 본 자세가 진입 허용
    범위 밖이면 포크가 구멍이 아니라 파렛트 옆·앞에 걸쳐 있을 수 있고, 그 상태로
    들면 화물이 쏟아진다(2026-08-07 실제로 박스가 엎어졌다).
    """
    if error is None:
        return "마지막 자세를 못 봤다"
    if abs(error.lateral_ratio) > lateral_max:
        return (f"좌우 오차 {error.lateral_ratio:+.2f} "
                f"(들기 허용 {lateral_max:.2f})")
    if (error.yaw_deg is not None
            and abs(error.yaw_deg) > servo.yaw_deg_tolerance):
        return f"요각 {error.yaw_deg:+.1f}° (허용 {servo.yaw_deg_tolerance:.0f}°)"
    return None


def _lift_fork(node, rclpy, publisher, string_cls, status, timeout_s: float) -> bool:
    """진입이 끝난 뒤 포크를 들어올린다. 성공하면 True.

    ⚠️ **`/fork/status` 를 기다린다 — 명령만 던지고 끝내면 안 된다.** 상승은 시간이
    걸리고, 노드가 먼저 죽으면 올라가는 중인지 걸렸는지 아무도 모른다. 브리지는
    `RUNNING` 을 거쳐 `DONE` 을 준다(`unmanned_mission` 과 같은 규약).
    """
    import json
    print("포크 상승 명령(UP)", flush=True)
    status.clear()
    publisher.publish(string_cls(data="UP"))
    deadline = time.monotonic() + timeout_s
    seen_running = False
    while rclpy.ok() and time.monotonic() < deadline:
        rclpy.spin_once(node, timeout_sec=0.1)
        while status:
            raw = status.pop(0)
            try:
                state = str(json.loads(raw).get("state", "")).upper()
            except (ValueError, AttributeError):
                continue
            if state == "RUNNING":
                seen_running = True
            elif state == "ERROR":
                print(f"❌ 포크 상승 실패: {raw}", flush=True)
                return False
            elif state == "DONE" and seen_running:
                print("포크 상승 완료", flush=True)
                return True
    print("⚠️ 포크 상승이 시간 안에 안 끝났다 — STOP 을 보낸다", flush=True)
    publisher.publish(string_cls(data="STOP"))
    return False


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
                         "ALIGN 한계진동이 감쇠 부재 때문인지 시험할 때 켠다. "
                         "⚠️ 진동 관측은 조향이 8°이던 시절(08-05 펄스 수정 전) 것이고, "
                         "지금은 정렬이 끝나므로 더 안 판다. 정렬이 다시 안 끝나면 "
                         "그때 --k-lateral 0.10 으로 게인 문제인지 먼저 가른다")
    ap.add_argument("--insert-margin", type=float, default=None,
                    help="진입 목표에서 빼는 여유(mm). 줄일수록 깊이 들어간다. "
                         "⚠️ 한 번에 많이 줄이면 파렛트를 민다 — 한 단계씩")
    ap.add_argument("--lateral-tolerance", type=float, default=None,
                    help="진입 허용 좌우 오차(기본 0.25). ⚠️ **튜닝 값이 아니라 "
                         "시험용 손잡이다** — 작게 주면 정상 정렬도 미정렬로 판정돼 "
                         "재접근(154)이 확실히 발동한다. 실주행 기본값으로 쓰지 말 것")
    ap.add_argument("--max-retries", type=int, default=None,
                    help="진입 거리에서 미정렬일 때 물러나 재접근하는 횟수 "
                         "(S15P11A304-154, 기본 20). 0 이면 종전처럼 바로 ABORT. "
                         "⚠️ 한 걸음이 80~120mm 라 걸음 수가 많아야 한다 — "
                         "실질 예산은 --max-seconds 다")
    ap.add_argument("--retreat-target", type=float, default=None,
                    help="거리를 **모를 때만** 쓰는 후진 목표(mm, 기본 600). "
                         "거리를 알면 틀어진 만큼 계산한다(80~120mm 로 절삭)")
    ap.add_argument("--retreat-max-s", type=float, default=None,
                    help="후진 시간 상한(초, 기본 5.0). 목표가 아니라 "
                         "**안전 상한**이다 — 거리 판정이 죽었을 때만 걸린다. "
                         "⚠️ 뒤는 카메라가 안 본다")
    ap.add_argument("--retreat-steer", type=float, default=None,
                    help="후진 중 조향 세기. **1.0 = 최대 조향, 음수 = 방향 반전** "
                         "(기본 -1.0). ⚠️ 부호는 2026-08-05 실측으로 확정했다 — "
                         "+1.0 은 lat 이 +0.33 → +4.8 로 발산했다. 0 이면 곧게 물러난다")
    ap.add_argument("--yaw-deg-tolerance", type=float, default=None,
                    help="진입을 허가할 요각 상한(도, 기본 10). ⚠️ 이게 없던 동안 "
                         "요각 -58.5° 인 채로 진입이 허가돼 포크가 비스듬히 스쳤다")
    ap.add_argument("--contact-guard", type=float, default=None,
                    help="이 거리(mm) 안쪽에서 요각이 허용치를 넘으면 진입 거리까지 "
                         "가지 않고 **닿기 전에 물러난다**(기본 320, 0 이면 끔). "
                         "포크 끝이 카메라보다 90mm 앞이라 비스듬하면 파렛트를 친다")
    ap.add_argument("--retreat-step-max", type=float, default=None,
                    help="후진 한 걸음 상한(mm, 기본 120). 파렛트가 크게 돌아 있으면 "
                         "짧은 걸음으로는 같은 자리로 돌아와 각이 안 준다 — 크게 주면 "
                         "다른 방위에서 접근한다. ⚠️ 뒤 공간이 그만큼 필요하다")
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
    ap.add_argument("--odom-topic", default="/odometry/filtered",
                    help="주행거리를 재는 오도메트리(nav_msgs/Odometry). 빈 문자열이면 끔. "
                         "⚠️ **이게 있으면 속도 상수를 안 쓴다** — 진입·후진이 시간이 "
                         "아니라 실제 이동거리로 끝난다. 바닥이 바뀌어도 다시 잴 것이 "
                         "없다. 라이다 오도메트리(rf2o)+EKF 로 나온다")
    ap.add_argument("--encoder-topic", default="/wheel/twist",
                    help="구동 바퀴 엔코더 속도 토픽. 여기서 **실제 이동거리**를 적분해 "
                         "개루프 진입·후진을 거리로 닫는다. 빈 문자열이면 끔(시간×속도상수 "
                         "추정으로 되돌아간다 — 그 상수는 바닥마다 2배 넘게 흔들린다)")
    ap.add_argument("--rear-guard-m", type=float, default=0.22,
                    help="후진 중 뒤가 이 거리(m)보다 가까우면 **후진을 멈춘다**. "
                         "0 이면 끔. 상단 라이다(/scan)를 쓴다 — 카메라·전방 ToF 는 "
                         "뒤를 못 본다. ⚠️ /scan 이 없으면 가드가 없는 것과 같다")
    ap.add_argument("--scan-topic", default="/scan",
                    help="후방 가드가 쓰는 LaserScan 토픽")
    ap.add_argument("--lift-lateral-max", type=float, default=0.35,
                    help="이 좌우 오차 이내일 때만 포크를 든다(기본 0.35 ≈ 20mm). "
                         "⚠️ **진입 허용치와 같게 두지 말 것** — 진입은 '들어갈 수 "
                         "있다', 들기는 '제대로 물렸다' 로 기준이 다르다. 같게 뒀다가 "
                         "lat +0.67 인 채로 들어 박스가 무너졌다(2026-08-07)")
    ap.add_argument("--lift-after-insert", action="store_true",
                    help="진입에 성공하면 포크를 들어올린다(/fork/command 에 UP). "
                         "⚠️ 진입이 얕으면 화물이 미끄러진다 — 물림을 눈으로 확인한 "
                         "뒤 쓰는 것이 안전하다")
    ap.add_argument("--lift-timeout-s", type=float, default=15.0,
                    help="포크 상승 완료(/fork/status DONE)를 기다리는 시간(초)")
    ap.add_argument("--save-dir", type=Path, help="ABORT/DONE 시 마지막 프레임 저장")
    a = ap.parse_args(argv)

    publisher = node = rclpy = None
    fork_publisher = fork_string_cls = None
    fork_status: list[str] = []
    # 후방 가드가 본 뒤쪽 최소거리(m). None 이면 아직 스캔이 없다.
    rear_min: list[float | None] = [None]
    # 엔코더 누적 주행거리(m, 단조증가). None 이면 엔코더가 없다.
    travel = {"m": None, "t": None, "live": False, "xy": None}
    if not a.dry_run:
        import rclpy as _rclpy
        from geometry_msgs.msg import Twist
        rclpy = _rclpy
        rclpy.init()
        node = rclpy.create_node("fork_align")
        publisher = node.create_publisher(Twist, a.cmd_topic, 10)
        twist_cls = Twist
        if a.odom_topic:
            from nav_msgs.msg import Odometry

            def _on_odom(msg) -> None:
                # 위치 변화량의 크기를 쌓는다. **회전만 해도 조금 쌓이지만**, 우리가
                # 재는 구간(직선 진입·직선 후진)에서는 거의 순수 병진이다.
                p = msg.pose.pose.position
                prev = travel["xy"]
                travel["xy"] = (p.x, p.y)
                if prev is None:
                    return
                d = math.hypot(p.x - prev[0], p.y - prev[1])
                if d > 0.0:
                    travel["live"] = True
                travel["m"] = (travel["m"] or 0.0) + d

            node.create_subscription(Odometry, a.odom_topic, _on_odom, 10)
            print(f"오도메트리: {a.odom_topic}", flush=True)
        elif a.encoder_topic:
            from geometry_msgs.msg import TwistWithCovarianceStamped

            def _on_encoder(msg) -> None:
                # ⚠️ **0 만 오는 엔코더는 없는 것으로 친다.** 2026-08-07 실물에서
                # 차가 움직이는 동안에도 `/wheel/twist` 가 계속 0.0 이었다(배선·펌웨어
                # 문제). 그대로 적분하면 "이동거리 0" 이 계속 참이라 진입·후진이
                # **한 프레임 만에 끝난 것처럼** 보이지 않고 조용히 시간 추정으로
                # 떨어지는데, 로그만 봐서는 어느 쪽이 동작 중인지 알 수 없다.
                # **부호를 버리고 크기만 쌓는다.** 앞뒤 어느 쪽이든 "얼마나 움직였나"
                # 만 필요하고, 진입·후진은 각자 시작점을 따로 기억한다.
                now = time.monotonic()
                prev = travel["t"]
                travel["t"] = now
                if prev is None:
                    return
                v = abs(float(msg.twist.twist.linear.x))
                if v > 0.0:
                    travel["live"] = True
                if not travel["live"]:
                    return
                travel["m"] = (travel["m"] or 0.0) + v * (now - prev)

            node.create_subscription(TwistWithCovarianceStamped, a.encoder_topic,
                                     _on_encoder, 10)
            print(f"엔코더: {a.encoder_topic}", flush=True)
        if a.rear_guard_m > 0.0:
            from sensor_msgs.msg import LaserScan
            from rclpy.qos import qos_profile_sensor_data

            def _on_scan(msg) -> None:
                rear_min[0] = _rear_min_range(msg)

            # ⚠️ **센서 QoS 라야 받는다.** 기본(RELIABLE)으로 구독하면 드라이버가
            # BEST_EFFORT 로 내보내 **한 장도 안 온다** — 에러가 아니라 조용한 침묵이라
            # "가드가 도는 줄 알았는데 안 돌았다" 가 된다.
            node.create_subscription(LaserScan, a.scan_topic, _on_scan,
                                     qos_profile_sensor_data)
            print(f"후방 가드: {a.scan_topic} · {a.rear_guard_m:.2f}m", flush=True)
        if a.lift_after_insert:
            from std_msgs.msg import String
            fork_publisher = node.create_publisher(String, "/fork/command", 10)
            node.create_subscription(String, "/fork/status",
                                     lambda m: fork_status.append(m.data), 10)
            fork_string_cls = String

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
    if a.contact_guard is not None:
        servo_kwargs["contact_guard_mm"] = a.contact_guard
    if a.retreat_step_max is not None:
        servo_kwargs["retreat_max_step_mm"] = a.retreat_step_max
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
        # ── 후방 가드 ────────────────────────────────────────────────────
        # **뒤로 갈 때만** 본다. 앞은 카메라가 보고 있고, 뒤는 이것 말고 아무도 안 본다.
        if a.rear_guard_m > 0.0 and linear_x < 0.0:
            back = rear_min[0]
            if back is not None and back < a.rear_guard_m:
                print(f"    ⛔ 후방 {back:.2f}m — 후진 중지(임계 {a.rear_guard_m:.2f}m)",
                      flush=True)
                linear_x = 0.0
                angular_z = 0.0
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
    last_error: list = [None]
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

            # ⚠️ **구독 콜백은 여기서만 돈다.** 이걸 안 부르면 `/scan`(후방 가드)·
            # `/odometry`(거리 폐루프) 가 **한 번도 실행되지 않는다** — 구독은 걸려
            # 있으니 로그도 정상으로 찍히고, 가드가 조용히 없는 상태가 된다.
            # 2026-08-07 에 그 상태로 여러 판을 돌렸다.
            if node is not None:
                rclpy.spin_once(node, timeout_sec=0.0)

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
            if error is not None:
                last_error[0] = error
            cmd = servo.step(error, dt, travel_m=travel["m"])
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
        # ⚠️ **노드를 파괴하기 전에** 올린다 — 뒤에서 부르면 발행할 통로가 없다.
        if fork_publisher is not None and servo.phase is Phase.DONE:
            # ⚠️ **물림이 확인될 때만 든다.** `done` 은 "계획한 거리를 갔다" 는 뜻이지
            # "구멍에 들어갔다" 는 뜻이 아니다. 2026-08-07 에 진입 직전 lat 이 +0.59
            # (허용치 0.45)인 채로 done 이 찍혔고, 그대로 들어올려 **박스가 다 엎어졌다.**
            bad = _engagement_problem(last_error[0], servo, a.lift_lateral_max)
            if bad:
                print(f"⛔ 포크를 들지 않는다 — {bad}", flush=True)
            else:
                _lift_fork(node, rclpy, fork_publisher, fork_string_cls,
                           fork_status, a.lift_timeout_s)
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
