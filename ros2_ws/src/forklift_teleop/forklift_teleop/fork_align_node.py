"""Drive the pallet-entry servo loop that `fork_servo.py` describes but never had.

`ai/src/control/fork_servo.py:13` 이 **"ROS2 배선은 `fork_align_node.py`"** 라고
적고 있는데 그 파일이 없었다. 상태기계도, 구멍 검출도, 미션 쪽 계약도 전부
만들어져 있었지만 **셋을 잇는 노드가 없어 아무것도 돌지 않았다.**
`/mission/task` 에 구독자가 없던 것과 같은 종류의 구멍이다.

이 노드는 새 판단을 하지 않는다. 이미 있는 것을 잇기만 한다:

    /align/request                              (mission_runner)
          |
    카메라 -> TrtDetector -> eligible_targets -> AlignTarget.error()
          |                                            |
          |                                      YawSmoother
          |                                            |
          +--> ForkServo.step(error, dt, travel_m) -> DriveCommand
                          |                |
                 /cmd_vel_align      /fork/command
                          |
                   /align/result --> mission_runner 가 다음 단계로

⚠️ **`travel_m` 에 엔코더 적산을 반드시 넣는다.** `ForkServo.step` 의 주석이
   이유를 적고 있다 -- 없으면 진입·후진 거리를 `시간 x 속도상수` 로 추정하는데
   그 상수가 바닥·구동 하한·배터리에 따라 두 배 넘게 흔들린다. 08-06~07 에 그
   상수를 다섯 번 다시 재고도 파렛을 밀거나 뒤 벽을 들이받았다.

⚠️ **요청이 없을 때는 아무것도 발행하지 않는다.** 중재기가 ALIGN 일 때만 이
   노드의 명령이 모터에 닿지만, 안 쓸 때 조용한 편이 디버깅에 낫다 -- 무엇이
   운전대를 쥐고 있는지가 토픽 트래픽만 봐도 드러난다.

⚠️ **카메라는 뒤집혀 달려 있다** (`rotate180=True`). 학습은 정립 프레임으로 했고
   촬영도 정립 저장했다. 추론에서 회전을 빼면 학습·추론 도메인이 정반대가 되는데,
   증상이 "아무것도 검출 안 됨" 이라 원인을 찾기 어렵다
   (`docs/ai/onboard-tensorrt-runbook.md` §6).

⚠️ **검출·추론은 별도 스레드에서 돈다.** 한 프레임에 45 ms 가 걸리므로(실측
   22 fps) 실행기 콜백 안에서 돌리면 그동안 구독이 밀린다 -- 엔코더와 가드
   상태가 늦게 들어와 제어가 과거를 보고 판단하게 된다.
"""

import json
import os
import sys
import threading
import time
from typing import Optional

from geometry_msgs.msg import Twist, TwistWithCovarianceStamped
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile
from std_msgs.msg import Bool, String

ALIGN = "ALIGN"
NAV = "NAV"


def _load_vision(source_dir: str):
    """`ai/src` 를 import 경로에 얹고 필요한 것만 가져온다.

    ⚠️ 여기서 실패하면 **노드를 아예 못 띄운다.** 조용히 넘어가면 미션이
       ALIGN 단계에서 45초를 기다렸다 타임아웃으로만 알게 되는데, 그때는
       원인이 카메라인지 모델인지 배선인지 구분되지 않는다.
    """
    if source_dir not in sys.path:
        sys.path.insert(0, source_dir)
    from perception.fork_align import YawSmoother, eligible_targets
    from perception.trt_detector import TrtDetector
    from control.fork_servo import ForkServo, Phase
    return TrtDetector, eligible_targets, YawSmoother, ForkServo, Phase


class ForkAlignNode(Node):
    def __init__(self) -> None:
        super().__init__("fork_align_node")

        repo = os.path.expanduser("~/S15P11A304")
        self.declare_parameter("ai_source_dir", os.path.join(repo, "ai", "src"))
        self.declare_parameter(
            "engine_path", os.path.expanduser(
                "~/trt_test/onboard_s640_ep116_fp16.engine"))
        self.declare_parameter(
            "plugin_path", os.path.expanduser(
                "~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so"))
        self.declare_parameter("camera_index", 0)
        self.declare_parameter("frame_width", 1280)
        self.declare_parameter("frame_height", 800)
        # 카메라를 뒤집어 달았다. 위 주석 참고 -- 끄면 아무것도 검출되지 않는다.
        self.declare_parameter("rotate180", True)
        # 포크 중심선의 화면 x. 카메라가 차체 중심에서 벗어나 있으면 넣는다.
        # 비워 두면 이미지 중앙을 쓰는데, 그러면 장착 오프셋만큼 일정한 편차를
        # 안고 수렴한다.
        self.declare_parameter("fork_center_x", -1.0)
        self.declare_parameter("focal_px", 0.0)
        self.declare_parameter("request_topic", "/align/request")
        self.declare_parameter("result_topic", "/align/result")
        self.declare_parameter("command_topic", "/cmd_vel_align")
        self.declare_parameter("fork_command_topic", "/fork/command")
        self.declare_parameter("mode_topic", "/drive/mode")
        self.declare_parameter("status_topic", "/align/status")
        self.declare_parameter("timeout_sec", 90.0)
        self.declare_parameter("fork_status_topic", "/fork/status")
        self.declare_parameter("loaded_topic", "/fork/loaded")
        self.declare_parameter("fork_timeout_sec", 30.0)
        self.declare_parameter("home_timeout_sec", 60.0)
        # 진입 전 포크 높이. 호밍 뒤 하한에서 여기까지 올려놓고 파렛에 들어간다.
        #
        # ⚠️ **`HOME` 은 하한까지만 내려가고 거기서 멈춘다 -- 백오프가 없다.**
        #    부팅 호밍(config.h STEPPER_MOTOR_HOME_BACKOFF_STEPS)과 다르다.
        #    그래서 HOME 뒤에 "UP <스텝>" 을 따로 보내야 같은 높이가 된다.
        #    2026-08-07 에 이걸 모르고 HOME 만 걸어놓고 "높이가 맞다" 고 봤다.
        self.declare_parameter("entry_steps", 7500)
        self.declare_parameter("home_before_pickup", True)
        # 진입 뒤 파렛을 바닥에서 띄우는 양(스텝).
        #
        # ⚠️ **아직 실측값이 아니다.** 스텝<->mm 환산 기록이 저장소에 없다 --
        #    바닥 기준 7 mm 가 호밍 백오프 6500스텝이라는 것만 알려져 있다.
        #    tools/fork_lift_calib.py 로 파렛이 뜨는 최소 스텝을 찾아 넣을 것.
        #
        # ⚠️ **높이 상한이 빡빡하다.** ToF 는 바닥에서 40 mm 에 달렸고 가드는
        #    20 mm 위를 장애물로 본다. 파렛 총높이가 14 mm 라 6 mm 넘게 띄우면
        #    가드가 자기 화물을 장애물로 본다. 그래서 적재 중에는 ToF 높이 창을
        #    따로 올린다(/fork/loaded).
        self.declare_parameter("lift_steps", 1200)

        (self._Detector, self._eligible, self._Smoother,
         self._ForkServo, self._Phase) = _load_vision(
            str(self.get_parameter("ai_source_dir").value))

        self._encoder_speed = 0.0
        self._travel_m = 0.0
        self._travel_at: Optional[float] = None
        self._pending: Optional[dict] = None
        self._running = False
        self._fork_running_seen = False
        self._fork_done = False
        self._fork_error: Optional[str] = None
        self._lock = threading.Lock()

        self.create_subscription(
            String, str(self.get_parameter("request_topic").value),
            self._on_request, 10)
        self.create_subscription(
            TwistWithCovarianceStamped, "/wheel/twist", self._on_encoder, 10)
        self.create_subscription(
            String, str(self.get_parameter("fork_status_topic").value),
            self._on_fork_status, 10)

        latched = QoSProfile(depth=1)
        latched.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL
        self._result = self.create_publisher(
            String, str(self.get_parameter("result_topic").value), latched)
        self._status = self.create_publisher(
            String, str(self.get_parameter("status_topic").value), latched)
        self._mode = self.create_publisher(
            String, str(self.get_parameter("mode_topic").value), latched)
        self._command = self.create_publisher(
            Twist, str(self.get_parameter("command_topic").value), 10)
        self._fork = self.create_publisher(
            String, str(self.get_parameter("fork_command_topic").value), 10)
        self._loaded = self.create_publisher(
            Bool, str(self.get_parameter("loaded_topic").value), latched)

        self._camera = None
        self._detector = None
        self.get_logger().info(
            "Fork align ready: /align/request 를 기다린다 "
            "(카메라·엔진은 첫 요청 때 연다)"
        )

    # ---- 입력 -------------------------------------------------------

    def _on_encoder(self, message: TwistWithCovarianceStamped) -> None:
        """엔코더 속도를 거리로 적산한다.

        ⚠️ 부호는 안 쓰고 크기만 쓴다 -- 엔코더 방향이 아직 미검증이고,
           ForkServo 가 원하는 것은 "얼마나 갔나" 라는 단조증가 값이다.
        """
        speed = abs(message.twist.twist.linear.x)
        now = time.monotonic()
        with self._lock:
            if self._travel_at is not None:
                self._travel_m += speed * (now - self._travel_at)
            self._travel_at = now
            self._encoder_speed = speed

    def _on_request(self, message: String) -> None:
        if self._running:
            self.get_logger().warning("이미 정렬 중이라 새 요청을 무시한다")
            return
        try:
            self._pending = json.loads(message.data)
        except (TypeError, ValueError):
            # 본문이 없어도 요청으로 받는다 -- 손으로 쏠 때 편하다.
            self._pending = {"action": "PICKUP"}

    # ---- 자원 -------------------------------------------------------

    def _open(self) -> Optional[str]:
        """카메라와 엔진을 연다. 실패하면 사유를 문자열로 돌려준다."""
        import cv2
        if self._camera is None:
            index = int(self.get_parameter("camera_index").value)
            camera = cv2.VideoCapture(index, cv2.CAP_V4L2)
            if not camera.isOpened():
                return f"/dev/video{index} 를 못 열었다"
            camera.set(cv2.CAP_PROP_FRAME_WIDTH,
                       int(self.get_parameter("frame_width").value))
            camera.set(cv2.CAP_PROP_FRAME_HEIGHT,
                       int(self.get_parameter("frame_height").value))
            self._camera = camera
        if self._detector is None:
            try:
                self._detector = self._Detector(
                    str(self.get_parameter("engine_path").value),
                    plugin=str(self.get_parameter("plugin_path").value),
                    class_names=("pallet", "hole"),
                    rotate180=bool(self.get_parameter("rotate180").value),
                )
            except Exception as error:      # noqa: BLE001 - 사유를 그대로 올린다
                return f"엔진 로드 실패: {error}"
        return None

    # ---- 출력 -------------------------------------------------------

    def _say(self, ok: bool, detail: str, **extra) -> None:
        payload = {"state": "DONE" if ok else "ERROR", "detail": detail}
        payload.update(extra)
        self._result.publish(String(data=json.dumps(payload,
                                                    ensure_ascii=False)))
        (self.get_logger().info if ok else self.get_logger().error)(detail)

    def _report(self, phase, error, command) -> None:
        self._status.publish(String(data=json.dumps({
            "phase": getattr(phase, "value", str(phase)),
            "lateral": None if error is None else round(error.lateral_ratio, 3),
            "yaw": None if error is None else round(error.yaw_signal, 3),
            "v": round(command.linear_x, 3),
            "w": round(command.angular_z, 3),
            "reason": command.reason,
            "travelM": round(self._travel_m, 3),
        }, ensure_ascii=False)))

    # ---- 정렬 -------------------------------------------------------

    def run(self, request: dict) -> None:
        # ⚠️ **정리는 어떤 경로에서도 돌아야 한다.** 종전에는 카메라·엔진 열기
        #    실패를 try 앞에서 일찍 돌려보냈는데, 그러면 정지 명령과 NAV 복귀를
        #    건너뛴다. 실패 경로가 가장 조용히 지나가는 길이라 거기서 빠지면
        #    아무도 모른다 -- 테스트가 이걸 잡았다.
        self._running = True
        try:
            self._run(request)
        finally:
            self._command.publish(Twist())
            self._mode.publish(String(data=NAV))
            self._running = False

    def _run(self, request: dict) -> None:
        failure = self._open()
        if failure:
            self._say(False, failure)
            return

        # 접근을 시작하기 전에 포크 높이를 되잡는다. 호밍은 하한까지 내려가는
        # 동작이라 **여기 말고는 안전한 자리가 없다** -- 구멍 안에서 하면 파렛을
        # 부순다.
        failure = self._prepare(request)
        if failure:
            self._say(False, failure)
            return

        servo = self._ForkServo()
        servo.reset()
        smoother = self._Smoother()
        fork_center = float(self.get_parameter("fork_center_x").value)
        focal = float(self.get_parameter("focal_px").value)
        # 제한시간은 호밍이 끝난 뒤에 잡는다 -- 호밍이 정렬 시간을 잡아먹으면
        # 접근도 못 해보고 시간 초과가 난다.
        deadline = time.monotonic() + float(
            self.get_parameter("timeout_sec").value)

        self._mode.publish(String(data=ALIGN))
        with self._lock:
            self._travel_m = 0.0
            self._travel_at = None
        self.get_logger().info(f"정렬 시작: {request.get('action', 'PICKUP')}")

        last = time.monotonic()
        last_phase = None
        try:
            while rclpy.ok() and time.monotonic() < deadline:
                rclpy.spin_once(self, timeout_sec=0.0)
                ok, frame = self._camera.read()
                if not ok or frame is None:
                    self._say(False, "카메라 프레임이 끊겼다")
                    return

                detections = self._detector.detect(frame)
                targets = self._eligible(detections)
                error = None
                if targets:
                    error = targets[0].error(
                        frame.shape[1],
                        fork_center_x=None if fork_center < 0 else fork_center,
                        focal_px=focal or None)
                error = smoother.update(error)

                now = time.monotonic()
                dt, last = now - last, now
                with self._lock:
                    travel = self._travel_m
                command = servo.step(error, dt, travel_m=travel)

                twist = Twist()
                twist.linear.x = command.linear_x
                twist.angular.z = command.angular_z
                self._command.publish(twist)

                if command.phase is not last_phase:
                    last_phase = command.phase
                    self.get_logger().info(
                        f"{command.phase.value}: {command.reason}")
                self._report(command.phase, error, command)

                if servo.is_finished():
                    self._finish(command, request, travel)
                    return
            self._say(False, "정렬 시간 초과")
        except Exception as error:          # noqa: BLE001
            self._say(False, f"정렬 중 예외: {error}")

    def _finish(self, command, request: dict, travel: float) -> None:
        """진입이 끝났다. 여기서만 포크를 움직이고 결과를 낸다.

        ⚠️ **ABORT 면 절대 올리지 않는다.** 포크가 구멍에 없다는 뜻이다.
           fork_servo 가 남긴 기록이 그 위험을 적고 있다 -- 08-07 에 진입
           50mm 만에 done 을 찍어 "구멍에 들어가지도 않은 채 화물을 들어올릴
           뻔했다". DONE 의 정확성이 리프트를 지키는 유일한 장치다.
        """
        if command.phase is self._Phase.ABORT:
            self._say(False, command.reason or "정렬 중단",
                      phase="abort", travelM=round(travel, 3))
            return

        # PICKUP 은 들어올리고, DROPOFF 는 내려놓는다. mission_runner 가
        # 이미 두 값을 넘기고 있다.
        pickup = str(request.get("action", "PICKUP")).upper() != "DROPOFF"
        if not self._lift("UP" if pickup else "DOWN"):
            self._say(False, "진입은 됐는데 포크를 못 움직였다",
                      phase=command.phase.value, travelM=round(travel, 3))
            return

        # 적재 상태가 바뀌었다. 가드가 이걸 보고 ToF 높이 창을 바꾼다 --
        # 든 파렛은 장애물이 아니라 화물이다.
        self._loaded.publish(Bool(data=pickup))
        self._say(True, command.reason or "진입·리프트 완료",
                  phase=command.phase.value, travelM=round(travel, 3),
                  loaded=pickup)

    def _prepare(self, request: dict) -> Optional[str]:
        """진입 전에 포크 높이를 호밍으로 되잡는다. 실패 사유를 돌려준다.

        ⚠️ **적재 중(DROPOFF)에는 절대 호밍하지 않는다.** 호밍은 하한 리밋까지
           내려가는 동작이라, 파렛을 든 채로 하면 화물을 바닥에 찍는다.

        ⚠️ 홈잉은 진입 **전**에만 안전하다. 포크가 이미 구멍 안에 있을 때
           하한까지 내리면 파렛을 부순다 -- 그래서 주행 루프 밖, 접근을
           시작하기도 전에 한 번만 한다.

        높이를 유지하려면 드라이버를 켜둬야 한다(config.h
        STEPPER_MOTOR_HOLD_AFTER_MOTION=1). 0 이면 몇 분 뒤 중력으로 내려앉아
        여기서 맞춘 높이가 무의미해진다.
        """
        if str(request.get("action", "PICKUP")).upper() == "DROPOFF":
            return None
        if not bool(self.get_parameter("home_before_pickup").value):
            return None

        timeout = float(self.get_parameter("home_timeout_sec").value)
        if not self._send_fork("HOME", None, timeout):
            return "포크 호밍이 안 끝났다"
        steps = int(self.get_parameter("entry_steps").value)
        if steps > 0 and not self._send_fork("UP", steps, timeout):
            return f"진입 높이({steps}스텝)로 못 올렸다"
        return None

    def _lift(self, action: str) -> bool:
        """진입이 끝난 뒤에만 포크를 움직인다. 완료를 기다린다.

        ⚠️ **진입 중에는 절대 건드리지 않는다.** 종전에는 Phase.INSERT 에서
           UP 을, Phase.RETREAT 에서 DOWN 을 보냈는데 둘 다 틀렸다:

             INSERT  는 포크가 구멍으로 미끄러져 들어가는 구간이다. 거기서
                     올리면 날이 구멍 상판을 밀어 **진입 자체가 막힌다.**
             RETREAT 는 최종 후퇴가 아니라 **재접근용 후진**이다
                     (MAX_RETRIES=20, 로그 "미정렬 — 80mm 물러난다 1/20").
                     거기서 내리면 정렬 도중에 높이가 어긋난다.

           fork_servo 는 리프트를 아예 모른다 -- 구멍에 넣는 주행까지만 하고
           들어올리는 것은 호출자 몫이다. 그래서 리프트는 DONE 뒤에 온다.
        """
        steps = int(self.get_parameter("lift_steps").value)
        return self._send_fork(
            action, steps if steps > 0 else None,
            float(self.get_parameter("fork_timeout_sec").value))

    def _send_fork(self, action: str, steps: Optional[int],
                   timeout_sec: float) -> bool:
        """포크 한 동작을 보내고 완료를 기다린다.

        ⚠️ RUNNING 을 본 적이 있어야 DONE 을 인정한다. 그러지 않으면 직전
           동작의 DONE 이 남아 있다가 "이미 끝났다" 로 읽힌다.
        """
        command = action if steps is None else f"{action} {steps}"
        self._fork_running_seen = False
        self._fork_done = False
        self._fork_error = None
        self._fork.publish(String(data=command))
        self.get_logger().info(f"포크 {command}")

        deadline = time.monotonic() + timeout_sec
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(self, timeout_sec=0.05)
            if self._fork_error is not None:
                self.get_logger().error(f"포크 {command} 실패: {self._fork_error}")
                return False
            if self._fork_done:
                return True
        self.get_logger().error(f"포크 {command} 시간 초과")
        self._fork.publish(String(data="STOP"))
        return False

    def _on_fork_status(self, message: String) -> None:
        try:
            status = json.loads(message.data)
        except (TypeError, ValueError):
            return
        state = str(status.get("state", "")).upper()
        if state == "RUNNING":
            self._fork_running_seen = True
        elif state == "DONE" and self._fork_running_seen:
            self._fork_done = True
        elif state == "ERROR":
            self._fork_error = message.data

    def pump(self) -> None:
        if self._pending is None:
            return
        request, self._pending = self._pending, None
        self.run(request)

    def destroy_node(self) -> bool:
        if self._camera is not None:
            self._camera.release()
        return super().destroy_node()


def main(args=None) -> None:
    rclpy.init(args=args)
    node = ForkAlignNode()
    try:
        while rclpy.ok():
            rclpy.spin_once(node, timeout_sec=0.1)
            node.pump()
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
