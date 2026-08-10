"""Nav2 MOVE 어댑터 테스트 (S15P11A304-192).

ROS 없이 돈다 — 어댑터가 액션 클라이언트를 **주입받도록** 만든 이유다.
"""

import math
import unittest

from fast_mqtt_bridge.dto import CommandMessage, Destination
from fast_mqtt_bridge.nav2_adapter import Nav2CommandAdapter, heading_to_yaw_rad


def move(x=1.0, y=2.0, heading=90.0, frame_id="map"):
    return CommandMessage(
        command_id="cmd-1", vehicle_id="REAL-F01", target_system="ROS2",
        command_category="MOVE", command="MOVE",
        destination=Destination(x=x, y=y, heading=heading, frame_id=frame_id),
        reason=None,
    )


class FakeSender:
    """goal 을 받아두고, 수락·완료를 테스트가 직접 흘린다(실제 Nav2 처럼 비동기)."""

    def __init__(self, server_up=True):
        self.server_up = server_up
        self.goals = []
        self._accepted = None
        self._done = None

    def wait_for_server(self, timeout_sec):
        return self.server_up

    def send_goal(self, x, y, yaw_rad, frame_id, on_accepted, on_done):
        self.goals.append((x, y, yaw_rad, frame_id))
        self._accepted, self._done = on_accepted, on_done

    def accept(self, ok=True):
        self._accepted(ok)

    def finish(self, ok=True, message="done"):
        self._done(ok, message)


class Recorder:
    def __init__(self):
        self.results = []

    def __call__(self, result, message):
        self.results.append(result)

    @property
    def last(self):
        return self.results[-1] if self.results else None


class Nav2AdapterTest(unittest.TestCase):
    def test_도착하면_SUCCESS까지_간다(self):
        sender, emit = FakeSender(), Recorder()
        Nav2CommandAdapter(sender).execute(move(), emit)
        sender.accept()
        sender.finish(True, "arrived")
        self.assertEqual(["ACCEPTED", "IN_PROGRESS", "SUCCESS"], emit.results)

    def test_목표를_실물_맵_배수로_줄여_보낸다(self):
        """백엔드는 시뮬 좌표(20x30m)로 말하고 실물 맵은 그 1/10(2.0x3.0m)이다.

        배수가 없으면 (15.5, 4.0) 이 그대로 나가 맵 밖이 되고 Nav2 가 경로를 못 짠다
        (2026-08-10 실측: 지게차가 안 움직였다).
        """
        sender, emit = FakeSender(), Recorder()
        Nav2CommandAdapter(sender, destination_scale=0.1).execute(
            move(x=15.5, y=4.0, heading=90.0), emit)

        x, y, yaw, _ = sender.goals[0]
        self.assertAlmostEqual(1.55, x, places=6)
        self.assertAlmostEqual(0.40, y, places=6)
        # 각도는 배수와 무관하다 — 축소해도 방향은 그대로다.
        self.assertAlmostEqual(heading_to_yaw_rad(90.0), yaw, places=9)

    def test_배수_기본값은_좌표를_바꾸지_않는다(self):
        sender, emit = FakeSender(), Recorder()
        Nav2CommandAdapter(sender).execute(move(x=15.5, y=4.0), emit)

        x, y, _, _ = sender.goals[0]
        self.assertEqual((15.5, 4.0), (x, y))

    def test_목표를_보내고_바로_돌아온다(self):
        """주행이 끝날 때까지 붙잡고 있으면 MQTT 콜백 스레드가 수십 초 묶인다."""
        sender, emit = FakeSender(), Recorder()
        Nav2CommandAdapter(sender).execute(move(), emit)
        self.assertEqual(1, len(sender.goals))
        self.assertEqual("ACCEPTED", emit.last, "완료를 기다리지 않고 돌아와야 한다")

    def test_Nav2가_안_떠_있으면_즉시_FAILED(self):
        """응답을 기다리면 백엔드 300초 TTL 이 지나야 실패를 안다 — 그 전에 끊는다."""
        sender, emit = FakeSender(server_up=False), Recorder()
        Nav2CommandAdapter(sender).execute(move(), emit)
        self.assertEqual(["FAILED"], emit.results)
        self.assertEqual([], sender.goals, "서버가 없으면 목표를 보내지 않는다")

    def test_goal이_거절되면_REJECTED가_아니라_FAILED(self):
        """REJECTED 는 '명령이 잘못됐다'는 뜻이라 백엔드가 원인을 엉뚱한 데서 찾는다."""
        sender, emit = FakeSender(), Recorder()
        Nav2CommandAdapter(sender).execute(move(), emit)
        sender.accept(False)
        self.assertEqual(["ACCEPTED", "FAILED"], emit.results)

    def test_중단되면_SUCCESS를_보내지_않는다(self):
        """계약: Nav2 도착이 성공했을 때만 SUCCESS. 안 그러면 백엔드가 도착하지도
        않은 차량에 대고 측정을 시작한다."""
        sender, emit = FakeSender(), Recorder()
        Nav2CommandAdapter(sender).execute(move(), emit)
        sender.accept()
        sender.finish(False, "aborted")
        self.assertEqual("FAILED", emit.last)
        self.assertNotIn("SUCCESS", emit.results)

    def test_MOVE가_아니면_실행하지_않는다(self):
        sender, emit = FakeSender(), Recorder()
        stop = CommandMessage(
            command_id="cmd-2", vehicle_id="REAL-F01", target_system="ROS2",
            command_category="SAFETY", command="STOP", destination=None, reason=None)
        Nav2CommandAdapter(sender).execute(stop, emit)
        self.assertEqual(["FAILED"], emit.results)
        self.assertEqual([], sender.goals)

    def test_좌표와_프레임을_그대로_넘긴다(self):
        sender, emit = FakeSender(), Recorder()
        Nav2CommandAdapter(sender).execute(move(x=3.5, y=-1.25, frame_id="odom"), emit)
        x, y, _, frame = sender.goals[0]
        self.assertEqual((3.5, -1.25, "odom"), (x, y, frame))


class HeadingTest(unittest.TestCase):
    def test_0도와_90도(self):
        self.assertAlmostEqual(0.0, heading_to_yaw_rad(0.0))
        self.assertAlmostEqual(math.pi / 2, heading_to_yaw_rad(90.0))

    def test_180도를_넘으면_음수로_접힌다(self):
        """계약은 [0,360) 인데 ROS 는 (-π,π] 다. 안 접으면 반대로 돈다."""
        self.assertAlmostEqual(-math.pi / 2, heading_to_yaw_rad(270.0))
        self.assertLess(heading_to_yaw_rad(359.0), 0.0)


if __name__ == "__main__":
    unittest.main()
