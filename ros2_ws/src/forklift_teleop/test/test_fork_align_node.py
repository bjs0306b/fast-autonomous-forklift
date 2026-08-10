import json
import pathlib
import sys
import unittest

_AI_SRC = pathlib.Path.home() / "S15P11A304" / "ai" / "src"


def _vision_available() -> bool:
    """비전 의존(TensorRT·cv2)이 없는 기기에서는 이 파일 전체를 건너뛴다."""
    if str(_AI_SRC) not in sys.path:
        sys.path.insert(0, str(_AI_SRC))
    try:
        import cv2  # noqa: F401
        import tensorrt  # noqa: F401
        from control.fork_servo import Phase  # noqa: F401
        return True
    except Exception:
        return False


@unittest.skipUnless(_vision_available(),
                     "비전 의존(cv2·tensorrt·ai/src)이 없다")
class ForkAlignNodeTest(unittest.TestCase):
    """정렬 루프가 **어떻게 끝나든** 안전한 상태로 돌아오는가.

    검증하는 것은 비전 정확도가 아니라 배선이다. 검출이 얼마나 맞는지는
    `ai/` 쪽 평가가 따로 하고, 여기서 지킬 것은 셋뿐이다:

        - 끝나면 반드시 정지 명령과 NAV 복귀
        - 포크는 단계 전환에서만 움직인다
        - 엔코더가 거리로 적산된다 (없으면 진입 깊이가 시간 추정으로 돌아간다)
    """

    @classmethod
    def setUpClass(cls):
        import rclpy
        rclpy.init()
        cls.rclpy = rclpy
        globals()['rclpy'] = rclpy

    @classmethod
    def tearDownClass(cls):
        cls.rclpy.shutdown()

    def setUp(self):
        from forklift_teleop.fork_align_node import ForkAlignNode
        self.node = ForkAlignNode()
        self.commands = []
        self.modes = []
        self.forks = []
        self.results = []
        self.node._command.publish = lambda m: self.commands.append(m)
        self.node._mode.publish = lambda m: self.modes.append(m.data)
        self.node._fork.publish = lambda m: self.forks.append(m.data)
        self.node._result.publish = lambda m: self.results.append(
            json.loads(m.data))

    def tearDown(self):
        self.node.destroy_node()

    def test_a_camera_failure_is_reported_not_swallowed(self):
        """열지 못하면 그 사유가 올라가야 한다.

        조용히 넘어가면 미션은 ALIGN 에서 45초를 기다렸다 타임아웃으로만 알게
        되고, 그때는 카메라인지 모델인지 배선인지 구분되지 않는다.
        """
        self.node._open = lambda: "/dev/video0 를 못 열었다"
        self.node.run({"action": "PICKUP"})
        self.assertEqual(self.results[-1]["state"], "ERROR")
        self.assertIn("video0", self.results[-1]["detail"])
        self.assertFalse(self.node._running)

    def test_the_wheel_goes_back_to_nav_even_on_failure(self):
        """ALIGN 인 채 끝나면 **다음 항법이 통째로 안 움직인다.**

        중재기는 모드가 낡았다고 NAV 로 되돌리지 않는다 -- 포크가 파렛 안에
        있는 채 nav2 가 끼어드는 것을 막으려는 설계라, 되돌릴 책임이 이쪽에 있다.
        """
        self.node._open = lambda: "엔진 로드 실패"
        self.node.run({})
        # 실패 경로에서도 정지 명령과 NAV 복귀가 있어야 한다
        self.assertEqual(self.modes[-1], "NAV")

    def test_encoder_is_integrated_into_distance(self):
        """ForkServo 는 시간이 아니라 **거리**로 진입·후진을 끝낸다.

        이 적산이 없으면 거리를 `시간 x 속도상수` 로 추정하는데, 그 상수가
        바닥·배터리에 따라 두 배 넘게 흔들려 파렛을 밀거나 뒤 벽을 받는다.
        """
        from geometry_msgs.msg import TwistWithCovarianceStamped
        message = TwistWithCovarianceStamped()
        message.twist.twist.linear.x = 0.20
        self.node._on_encoder(message)      # 기준 시각만 잡힌다
        before = self.node._travel_m
        import time as _time
        _time.sleep(0.05)
        self.node._on_encoder(message)
        self.assertGreater(self.node._travel_m, before)

    def test_encoder_direction_is_ignored(self):
        """부호는 안 쓴다 -- 엔코더 방향이 미검증이고 필요한 것은 크기다."""
        from geometry_msgs.msg import TwistWithCovarianceStamped
        import time as _time
        forward = TwistWithCovarianceStamped()
        forward.twist.twist.linear.x = 0.20
        backward = TwistWithCovarianceStamped()
        backward.twist.twist.linear.x = -0.20
        self.node._on_encoder(forward)
        _time.sleep(0.05)
        self.node._on_encoder(forward)
        after_forward = self.node._travel_m
        _time.sleep(0.05)
        self.node._on_encoder(backward)
        self.assertGreater(self.node._travel_m, after_forward)

    def _command(self, phase, reason="시험"):
        from control.fork_servo import DriveCommand
        return DriveCommand(phase=phase, reason=reason)

    def test_an_abort_never_lifts(self):
        """포크가 구멍에 없다는 뜻이다 -- 올리면 화물을 밀거나 떨어뜨린다.

        fork_servo 가 남긴 기록이 그 위험을 적고 있다: 08-07 에 진입 50mm 만에
        done 을 찍어 "구멍에 들어가지도 않은 채 화물을 들어올릴 뻔했다".
        """
        from control.fork_servo import Phase
        self.node._finish(self._command(Phase.ABORT, "재접근 한계"), {}, 0.1)
        self.assertEqual(self.forks, [])
        self.assertEqual(self.results[-1]["state"], "ERROR")

    def test_pickup_lifts_after_insertion(self):
        from control.fork_servo import Phase
        self.node._lift = lambda action: self.forks.append(action) or True
        self.node._finish(self._command(Phase.DONE, "진입 완료"),
                          {"action": "PICKUP"}, 0.9)
        self.assertEqual(self.forks, ["UP"])
        self.assertEqual(self.results[-1]["state"], "DONE")
        self.assertTrue(self.results[-1]["loaded"])

    def test_dropoff_lowers_instead(self):
        from control.fork_servo import Phase
        self.node._lift = lambda action: self.forks.append(action) or True
        self.node._finish(self._command(Phase.DONE, "진입 완료"),
                          {"action": "DROPOFF"}, 0.9)
        self.assertEqual(self.forks, ["DOWN"])
        self.assertFalse(self.results[-1]["loaded"])

    def test_a_failed_lift_is_reported_as_error(self):
        """진입은 됐는데 포크가 안 움직인 것은 성공이 아니다."""
        from control.fork_servo import Phase
        self.node._lift = lambda action: False
        self.node._finish(self._command(Phase.DONE, "진입 완료"),
                          {"action": "PICKUP"}, 0.9)
        self.assertEqual(self.results[-1]["state"], "ERROR")

    def test_lift_carries_the_configured_steps(self):
        """스텝을 실어야 재플래시 없이 높이를 바꾼다."""
        self.node.set_parameters(
            [rclpy.parameter.Parameter("lift_steps",
                                       rclpy.Parameter.Type.INTEGER, 900)])
        self.node._fork_done = True
        self.node._fork_running_seen = True
        # _lift 는 상태를 초기화하고 기다리므로, 발행만 확인한다
        published = []
        self.node._fork.publish = lambda m: published.append(m.data)
        self.node._lift("UP")
        self.assertEqual(published[0], "UP 900")

    def test_pickup_homes_and_does_not_stack_a_second_backoff(self):
        """HOME 은 **그 자체로** 6500스텝 백오프까지 한다.

        task_comm.c 의 lift_backoff_waiting 경로가 하한 도달 뒤 500ms 쉬고
        STEPPER_MOTOR_HOME_BACKOFF_STEPS 만큼 되올린다 -- 부팅 호밍과 같은
        높이로 끝난다. 여기에 UP 6500 을 또 붙이면 13000, 두 배가 된다.
        config.h 주석이 "백오프 없음" 이라고 적고 있는데 코드와 다르다.
        """
        sent = []
        self.node._send_fork = lambda action, steps, timeout: (
            sent.append((action, steps)) or True)
        self.assertIsNone(self.node._prepare({"action": "PICKUP"}))
        self.assertEqual(sent, [("HOME", None)])

    def test_dropoff_never_homes_with_a_pallet_on_the_fork(self):
        """호밍은 하한 리밋까지 내려간다 -- 짐을 든 채로 하면 바닥에 찍는다."""
        sent = []
        self.node._send_fork = lambda action, steps, timeout: (
            sent.append(action) or True)
        self.assertIsNone(self.node._prepare({"action": "DROPOFF"}))
        self.assertEqual(sent, [])

    def test_a_failed_homing_stops_before_driving(self):
        """높이를 모르는 채 접근하면 상판이나 하판을 민다."""
        self.node._send_fork = lambda action, steps, timeout: False
        self.assertIsNotNone(self.node._prepare({"action": "PICKUP"}))

    def test_a_second_request_is_refused_while_one_runs(self):
        from std_msgs.msg import String
        self.node._running = True
        self.node._on_request(String(data=json.dumps({"action": "PICKUP"})))
        self.assertIsNone(self.node._pending)

    def test_a_bodyless_request_is_still_accepted(self):
        """손으로 쏠 때 본문 없이 보내는 일이 잦다."""
        from std_msgs.msg import String
        self.node._on_request(String(data=""))
        self.assertIsNotNone(self.node._pending)


if __name__ == "__main__":
    unittest.main()
