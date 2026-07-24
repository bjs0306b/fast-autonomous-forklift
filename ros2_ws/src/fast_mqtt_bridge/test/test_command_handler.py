import unittest

from fast_mqtt_bridge.command_handler import (
    CommandProcessor,
    RecentCommandCache,
    ResultStateTracker,
    validate_command,
)
from fast_mqtt_bridge.dto import CommandMessage


def command_json(**overrides):
    data = {
        "commandId": "CMD-1",
        "vehicleId": "REAL-F01",
        "targetSystem": "ROS2",
        "commandCategory": "MOVE",
        "command": "MOVE",
        "payload": {
            "destination": {"x": 1.0, "y": 2.0, "heading": 90.0, "frameId": "map"}
        },
    }
    data.update(overrides)
    import json
    return CommandMessage.from_json(json.dumps(data))


class SuccessfulAdapter:
    def __init__(self):
        self.executions = []

    def execute(self, command, emit_result):
        self.executions.append(command)
        emit_result("ACCEPTED", "accepted")
        emit_result("IN_PROGRESS", "moving")
        emit_result("SUCCESS", "done")


class FailingAdapter:
    def execute(self, command, emit_result):
        emit_result("ACCEPTED", "accepted")
        raise RuntimeError("navigation failed")


class CommandHandlerTest(unittest.TestCase):
    def test_vehicle_id_mismatch(self):
        failure = validate_command(command_json(vehicleId="OTHER"), "REAL-F01")
        self.assertEqual("REJECTED", failure.result)

    def test_target_system_mismatch(self):
        failure = validate_command(
            command_json(targetSystem="EMBEDDED"), "REAL-F01"
        )
        self.assertEqual("REJECTED", failure.result)

    def test_category_combination(self):
        failure = validate_command(
            command_json(commandCategory="SAFETY"), "REAL-F01"
        )
        self.assertEqual("REJECTED", failure.result)

    def test_move_destination_required(self):
        failure = validate_command(command_json(payload={}), "REAL-F01")
        self.assertEqual("REJECTED", failure.result)

    def test_frame_and_heading_validation(self):
        failure = validate_command(
            command_json(
                payload={
                    "destination": {
                        "x": 0,
                        "y": 0,
                        "heading": 360,
                        "frameId": "base_link",
                    }
                }
            ),
            "REAL-F01",
        )
        self.assertIn("heading", failure.message)

    def test_duplicate_command_is_not_executed_and_final_result_is_republished(self):
        adapter = SuccessfulAdapter()
        results = []
        processor = CommandProcessor("REAL-F01", adapter, results.append)
        command = command_json()
        self.assertTrue(processor.process(command))
        self.assertFalse(processor.process(command))
        self.assertEqual(1, len(adapter.executions))
        self.assertEqual(["ACCEPTED", "IN_PROGRESS", "SUCCESS", "SUCCESS"],
                         [result.result for result in results])

    def test_adapter_failure_publishes_failed(self):
        results = []
        processor = CommandProcessor("REAL-F01", FailingAdapter(), results.append)
        self.assertFalse(processor.process(command_json()))
        self.assertEqual(["ACCEPTED", "FAILED"], [result.result for result in results])

    def test_terminal_transition_blocks_regression(self):
        tracker = ResultStateTracker()
        self.assertTrue(tracker.transition("1", "ACCEPTED"))
        self.assertTrue(tracker.transition("1", "SUCCESS"))
        self.assertFalse(tracker.transition("1", "FAILED"))

    def test_cache_ttl_and_max_size(self):
        now = [0.0]
        cache = RecentCommandCache(10.0, 1, clock=lambda: now[0])
        self.assertTrue(cache.reserve("1"))
        self.assertTrue(cache.reserve("2"))
        self.assertTrue(cache.reserve("1"))
        now[0] = 11.0
        self.assertTrue(cache.reserve("2"))


if __name__ == "__main__":
    unittest.main()
