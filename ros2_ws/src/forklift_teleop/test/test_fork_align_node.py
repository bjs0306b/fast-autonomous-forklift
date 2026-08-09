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

    def test_fork_moves_only_on_phase_change(self):
        from control.fork_servo import Phase
        self.node._on_phase(Phase.APPROACH)
        self.assertEqual(self.forks, [])
        self.node._on_phase(Phase.INSERT)
        self.assertEqual(self.forks, ["UP"])
        self.node._on_phase(Phase.RETREAT)
        self.assertEqual(self.forks, ["UP", "DOWN"])

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
