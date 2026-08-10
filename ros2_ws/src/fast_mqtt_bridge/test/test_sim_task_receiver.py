import json
import unittest

import rclpy

from fast_mqtt_bridge.sim_task_receiver import SimTaskReceiver


class _Recorder:
    """SimTaskReceiver 의 발행만 가로챈다."""

    def __init__(self):
        self.sent = []

    def publish(self, message):
        self.sent.append(json.loads(message.data))


class SimTaskConversionTest(unittest.TestCase):
    """시뮬 좌표를 목업 좌표로 옮기는 한 곳.

    interface-spec 이 "변환은 MQTT 경계에서만" 이라고 정한 이유는, 변환이 두
    곳에 있으면 언젠가 한쪽만 고쳐지고 그 증상이 "가끔 엉뚱한 데로 간다" 로만
    보이기 때문이다. 그 한 곳이 여기이므로 여기서 틀리면 잡을 데가 없다.
    """

    @classmethod
    def setUpClass(cls):
        rclpy.init()

    @classmethod
    def tearDownClass(cls):
        rclpy.shutdown()

    def setUp(self):
        self.node = SimTaskReceiver()
        self.recorder = _Recorder()
        self.node._publisher = self.recorder

    def tearDown(self):
        self.node.destroy_node()

    def task(self, pickup, dropoff, task_id="T-1"):
        return {"taskId": task_id, "pickup": pickup, "dropoff": dropoff}

    def test_positions_are_divided_by_the_scale(self):
        accepted = self.node.handle_task(self.task(
            {"x": 5.0, "y": 6.0, "yaw": 0.0},
            {"x": 12.0, "y": 20.0, "yaw": 0.0},
        ))
        self.assertTrue(accepted)
        mission = self.recorder.sent[0]
        self.assertAlmostEqual(mission["pickup"]["x"], 0.5)
        self.assertAlmostEqual(mission["pickup"]["y"], 0.6)
        self.assertAlmostEqual(mission["dropoff"]["x"], 1.2)
        self.assertAlmostEqual(mission["dropoff"]["y"], 2.0)

    def test_yaw_is_not_scaled(self):
        """각도는 축척과 무관하다."""
        self.node.handle_task(self.task(
            {"x": 5.0, "y": 6.0, "yaw": 1.5708},
            {"x": 12.0, "y": 20.0, "yaw": -0.7854},
        ))
        mission = self.recorder.sent[0]
        self.assertAlmostEqual(mission["pickup"]["yaw"], 1.5708)
        self.assertAlmostEqual(mission["dropoff"]["yaw"], -0.7854)

    def test_a_target_beyond_the_mockup_is_refused(self):
        """시뮬 창고 어디든 유효하지만 목업은 2 x 3 m 뿐이다."""
        accepted = self.node.handle_task(self.task(
            {"x": 5.0, "y": 6.0, "yaw": 0.0},
            {"x": 25.0, "y": 6.0, "yaw": 0.0},     # 실물 2.5 m, 폭 밖
        ))
        self.assertFalse(accepted)
        self.assertEqual(self.recorder.sent, [])

    def test_a_target_on_the_wall_is_refused(self):
        """벽에 붙은 목표는 도달할 수 없다."""
        accepted = self.node.handle_task(self.task(
            {"x": 0.2, "y": 6.0, "yaw": 0.0},      # 실물 0.02 m, 여유 0.15 안
            {"x": 12.0, "y": 20.0, "yaw": 0.0},
        ))
        self.assertFalse(accepted)

    def test_one_bad_end_refuses_the_whole_task(self):
        """절반만 수행하면 파렛트를 든 채 갈 곳이 없다."""
        accepted = self.node.handle_task(self.task(
            {"x": 5.0, "y": 6.0, "yaw": 0.0},
            {"x": 99.0, "y": 99.0, "yaw": 0.0},
        ))
        self.assertFalse(accepted)
        self.assertEqual(self.recorder.sent, [])

    def test_a_malformed_payload_is_refused_not_guessed(self):
        for payload in (
            {"taskId": "T-2"},                                  # 목적지 없음
            self.task({"x": 5.0}, {"x": 12.0, "y": 20.0}),      # y 없음
            self.task({"x": "왼쪽", "y": 6.0}, {"x": 12.0, "y": 20.0}),
        ):
            with self.subTest(payload=payload):
                self.assertFalse(self.node.handle_task(payload))
        self.assertEqual(self.recorder.sent, [])

    def test_fork_height_is_a_length_and_scales_too(self):
        self.node.handle_task(self.task(
            {"x": 5.0, "y": 6.0, "yaw": 0.0, "forkHeight": 0.0},
            {"x": 12.0, "y": 20.0, "yaw": 0.0, "forkHeight": 1.5},
        ))
        mission = self.recorder.sent[0]
        self.assertAlmostEqual(mission["dropoff"]["forkHeight"], 0.15)

    def test_a_goto_task_carries_one_destination(self):
        """관제는 순환로를 따라 지점을 하나씩 보낸다 (orin-pose-spec §6.2).

        pickup·dropoff 한 쌍을 요구하면 이 형식이 통째로 거절되고, 증상은
        "관제가 좌표를 주는데 차가 안 움직인다" 로만 보인다.
        """
        accepted = self.node.handle_task({
            "taskId": "T-0042", "action": "GOTO",
            "pickup": {"x": 17.0, "y": 5.0, "yaw": 0.0},
        })
        self.assertTrue(accepted)
        mission = self.recorder.sent[0]
        self.assertEqual(mission["action"], "GOTO")
        self.assertAlmostEqual(mission["goal"]["x"], 1.7)
        self.assertAlmostEqual(mission["goal"]["y"], 0.5)
        self.assertNotIn("dropoff", mission)

    def test_a_lone_pickup_is_treated_as_a_goto(self):
        """action 을 안 붙이고 좌표만 보내는 쪽도 있다."""
        accepted = self.node.handle_task({
            "taskId": "T-0043",
            "pickup": {"x": 5.0, "y": 9.3, "yaw": 3.1416},
        })
        self.assertTrue(accepted)
        self.assertEqual(self.recorder.sent[0]["action"], "GOTO")

    def test_a_goto_outside_the_mockup_is_still_refused(self):
        accepted = self.node.handle_task({
            "taskId": "T-0044", "action": "GOTO",
            "pickup": {"x": 25.0, "y": 5.0, "yaw": 0.0},
        })
        self.assertFalse(accepted)
        self.assertEqual(self.recorder.sent, [])

    def test_the_simulator_numbers_are_carried_along(self):
        """로그 한 줄에서 보낸 값과 받은 값을 대조할 수 있어야 한다."""
        self.node.handle_task(self.task(
            {"x": 5.0, "y": 6.0, "yaw": 0.0},
            {"x": 12.0, "y": 20.0, "yaw": 0.0},
        ))
        mission = self.recorder.sent[0]
        self.assertEqual(mission["sim"]["pickup"]["x"], 5.0)
        self.assertEqual(mission["sim"]["dropoff"]["y"], 20.0)


if __name__ == "__main__":
    unittest.main()
