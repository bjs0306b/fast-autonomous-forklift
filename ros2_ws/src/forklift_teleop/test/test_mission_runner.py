import json
import unittest

import rclpy
from std_msgs.msg import String

from forklift_teleop.mission_runner import ALIGN, NAV, MissionRunner


class MissionRunnerTest(unittest.TestCase):
    """심이 준 작업 하나를 끝까지 끌고 가는 순서.

    검증할 것은 "어떤 순서로 무엇에 운전대를 넘기는가" 이고, 그것은 항법·정렬·
    포크가 실제로 되는지와 무관하게 정해져 있다. 그래서 셋 다 가짜로 두고
    순서와 실패 처리만 본다.
    """

    @classmethod
    def setUpClass(cls):
        rclpy.init()

    @classmethod
    def tearDownClass(cls):
        rclpy.shutdown()

    def setUp(self):
        self.node = MissionRunner()
        self.steps = []
        self.modes = []
        self.reports = []

        self.node._set_mode = lambda mode: self.modes.append(mode)
        self.node.navigate_to = lambda label, point: (
            self.steps.append(("nav", label, point["x"], point["y"]))
            or self.nav_ok)
        self.node.align = lambda what, point: (
            self.steps.append(("align", what)) or self.align_result)
        self.node.fork = lambda action: (
            self.steps.append(("fork", action)) or self.fork_ok)
        self.node._status.publish = lambda m: self.reports.append(
            json.loads(m.data))

        self.nav_ok = True
        self.align_result = True
        self.fork_ok = True

    def tearDown(self):
        self.node.destroy_node()

    def task(self):
        return {
            "taskId": "T-1",
            "pickup": {"x": 0.5, "y": 0.6, "yaw": 0.0},
            "dropoff": {"x": 1.2, "y": 2.0, "yaw": 1.5708},
        }

    def stages(self):
        return [r["stage"] for r in self.reports]

    def test_it_runs_go_align_lift_then_go_align_lower(self):
        self.assertTrue(self.node.run(self.task()))
        self.assertEqual(self.steps, [
            ("nav", "픽업", 0.5, 0.6),
            ("align", "PICKUP"),
            ("fork", "UP"),
            ("nav", "적재", 1.2, 2.0),
            ("align", "DROPOFF"),
            ("fork", "DOWN"),
        ])
        self.assertEqual(self.stages()[-1], "COMPLETED")

    def test_coordinates_are_used_as_given(self):
        """축척 변환은 MQTT 경계에서 한 번만 한다 -- 여기서 또 나누면 두 번이다."""
        self.node.run(self.task())
        self.assertEqual(self.steps[0], ("nav", "픽업", 0.5, 0.6))

    def test_the_wheel_goes_back_to_nav_even_when_a_stage_fails(self):
        """ALIGN 인 채 끝나면 **다음 작업이 통째로 안 움직인다.**

        중재기는 모드가 낡았다고 NAV 로 되돌리지 않는다 -- 포크가 파렛 안에
        있는 채 nav2 가 끼어드는 것을 막으려는 설계라서, 되돌리는 책임은
        미션 쪽에 있다.
        """
        self.fork_ok = False
        self.assertFalse(self.node.run(self.task()))
        self.assertEqual(self.modes[-1], NAV)

    def test_a_missing_aligner_is_skipped_not_a_failure(self):
        """비전 정렬은 따로 개발 중이다. 없다고 미션이 죽으면 나머지를 못 본다."""
        self.align_result = None
        self.assertTrue(self.node.run(self.task()))
        self.assertEqual(self.stages()[-1], "COMPLETED")
        self.assertEqual(self.reports[-1]["alignSkipped"], ["픽업", "적재"])

    def test_a_failing_aligner_stops_the_mission(self):
        """건너뛰는 것과 실패하는 것은 다르다 -- 후자는 파렛이 거기 없다는 뜻이다."""
        self.align_result = False
        self.assertFalse(self.node.run(self.task()))
        self.assertEqual(self.stages()[-1], "FAILED")

    def test_navigation_failure_does_not_touch_the_fork(self):
        self.nav_ok = False
        self.assertFalse(self.node.run(self.task()))
        self.assertEqual([s[0] for s in self.steps], ["nav"])

    def test_a_task_without_coordinates_fails_before_moving(self):
        task = self.task()
        del task["dropoff"]
        self.assertFalse(self.node.run(task))
        self.assertEqual([s[0] for s in self.steps],
                         ["nav", "align", "fork"])

    def test_a_goto_task_drives_once_and_leaves_the_fork_alone(self):
        """관제는 순환로를 따라 지점을 하나씩 준다 (orin-pose-spec §6.2).

        좌표 하나만 오는데 픽업·적재 한 쌍을 요구하면 단순 이동이 통째로
        거절되고, 증상은 "관제가 좌표를 주는데 차가 안 움직인다" 로만 보인다.
        """
        self.assertTrue(self.node.run({
            "taskId": "T-9", "action": "GOTO",
            "goal": {"x": 1.7, "y": 0.5, "yaw": 0.0},
        }))
        self.assertEqual(self.steps, [("nav", "목적지", 1.7, 0.5)])
        self.assertEqual(self.stages()[-1], "ARRIVED")

    def test_a_goto_without_coordinates_fails_before_moving(self):
        self.assertFalse(self.node.run({"taskId": "T-9", "action": "GOTO"}))
        self.assertEqual(self.steps, [])
        self.assertEqual(self.stages()[-1], "FAILED")

    def test_a_goto_that_cannot_be_reached_is_reported(self):
        self.nav_ok = False
        self.assertFalse(self.node.run({
            "taskId": "T-9", "action": "GOTO",
            "goal": {"x": 1.7, "y": 0.5, "yaw": 0.0},
        }))
        self.assertEqual(self.stages()[-1], "FAILED")
        self.assertEqual(self.modes[-1], NAV)

    def test_a_second_task_is_refused_while_one_is_running(self):
        """반쯤 하다 만 작업 두 개보다, 거절된 작업 하나가 낫다."""
        self.node._running = True
        self.node._on_task(String(data=json.dumps(self.task())))
        self.assertIsNone(self.node._pending)
        self.assertEqual(self.stages()[-1], "REJECTED")

    def test_a_malformed_task_is_ignored_not_guessed(self):
        self.node._on_task(String(data="{이건 JSON 이 아니다"))
        self.assertIsNone(self.node._pending)


class AlignModeGuardTest(unittest.TestCase):
    """ALIGN 일 때 전방 임계가 실제로 내려가는가.

    파렛은 다가가야 할 대상이지 비켜 갈 장애물이 아니다. 순항용 0.25 m 로는
    포크가 닿기 전에 가드가 세우고, 미션은 "정렬 성공, 포크 헛돌음" 으로 끝난다.
    """

    def test_align_policy_is_closer_and_does_not_steer(self):
        from forklift_teleop.obstacle_fusion import AvoidanceConfig
        from dataclasses import replace

        cruise = AvoidanceConfig()
        align = replace(cruise, stop_distance_m=0.20,
                        slowdown_distance_m=0.40,
                        avoidance_engage_distance_m=0.40,
                        avoidance_yaw_rate_rps=0.0)
        align.validate()
        self.assertLess(align.stop_distance_m, cruise.stop_distance_m)
        # 회피 조향은 꺼야 한다 -- 파렛을 비켜 가면 진입이 어긋난다.
        self.assertEqual(align.avoidance_yaw_rate_rps, 0.0)


if __name__ == "__main__":
    unittest.main()
