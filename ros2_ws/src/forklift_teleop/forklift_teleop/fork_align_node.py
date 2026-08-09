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
from std_msgs.msg import String

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

        (self._Detector, self._eligible, self._Smoother,
         self._ForkServo, self._Phase) = _load_vision(
            str(self.get_parameter("ai_source_dir").value))

        self._encoder_speed = 0.0
        self._travel_m = 0.0
        self._travel_at: Optional[float] = None
        self._pending: Optional[dict] = None
        self._running = False
        self._lock = threading.Lock()

        self.create_subscription(
            String, str(self.get_parameter("request_topic").value),
            self._on_request, 10)
        self.create_subscription(
            TwistWithCovarianceStamped, "/wheel/twist", self._on_encoder, 10)

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

        servo = self._ForkServo()
        servo.reset()
        smoother = self._Smoother()
        fork_center = float(self.get_parameter("fork_center_x").value)
        focal = float(self.get_parameter("focal_px").value)
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
                    self._on_phase(command.phase)
                self._report(command.phase, error, command)

                if servo.is_finished():
                    aborted = command.phase is self._Phase.ABORT
                    self._say(not aborted,
                              command.reason or command.phase.value,
                              phase=command.phase.value,
                              travelM=round(travel, 3))
                    return
            self._say(False, "정렬 시간 초과")
        except Exception as error:          # noqa: BLE001
            self._say(False, f"정렬 중 예외: {error}")

    def _on_phase(self, phase) -> None:
        """포크를 올리고 내리는 것은 단계 전환에서만 한다."""
        if phase is self._Phase.INSERT:
            self._fork.publish(String(data="UP"))
        elif phase is self._Phase.RETREAT:
            self._fork.publish(String(data="DOWN"))

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
