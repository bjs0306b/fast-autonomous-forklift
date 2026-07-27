import json
import unittest

from fast_mqtt_bridge.bridge_core import BridgeCore
from fast_mqtt_bridge.dto import (
    CommandMessage,
    LocationMessage,
    PathMessage,
    StatusMessage,
)


class MockCommandAdapter:
    def __init__(self, fail=False):
        self.commands = []
        self.fail = fail

    def execute(self, command, emit_result):
        self.commands.append(command)
        emit_result("ACCEPTED", "accepted")
        emit_result("IN_PROGRESS", "moving")
        emit_result("FAILED" if self.fail else "SUCCESS", "finished")


class BridgeCoreIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.published = []
        self.now = [100.0]
        self.adapter = MockCommandAdapter()
        self.core = BridgeCore(
            "REAL-F01",
            self.adapter,
            lambda topic, payload: self.published.append((topic, json.loads(payload))) or True,
            clock=lambda: self.now[0],
        )

    def test_status_callback_to_mqtt_publish_qos_payload_boundary(self):
        status = StatusMessage.create("REAL-F01", "IDLE", 90)
        self.assertTrue(self.core.publish_status(status))
        self.assertFalse(self.core.publish_status(status))
        self.assertEqual("forklift/REAL-F01/status", self.published[0][0])
        self.assertEqual("IDLE", self.published[0][1]["status"])

    def test_location_is_throttled(self):
        location = LocationMessage.create("REAL-F01", 1.0, 2.0, 90.0)
        self.assertTrue(self.core.publish_location(location))
        self.assertFalse(self.core.publish_location(location))
        self.now[0] += 0.101
        self.assertTrue(self.core.publish_location(location))

    def test_same_path_is_not_republished_when_timestamp_changes(self):
        first = PathMessage.create("REAL-F01", "map", [(1.0, 2.0, 90.0)], "a")
        second = PathMessage.create("REAL-F01", "map", [(1.0, 2.0, 90.0)], "b")
        self.assertTrue(self.core.publish_path(first))
        self.assertFalse(self.core.publish_path(second))

    def test_mqtt_command_to_adapter_and_success_results(self):
        command = CommandMessage.from_json(
            '{"commandId":"CMD-I1","vehicleId":"REAL-F01","targetSystem":"ROS2",'
            '"commandCategory":"MOVE","command":"MOVE","payload":{"destination":'
            '{"x":1,"y":2,"heading":90,"frameId":"map"}}}'
        )
        self.assertTrue(self.core.process_command(command))
        self.assertEqual(1, len(self.adapter.commands))
        results = [payload["result"] for topic, payload in self.published
                   if topic.endswith("command-result")]
        self.assertEqual(["ACCEPTED", "IN_PROGRESS", "SUCCESS"], results)

    def test_adapter_failure_result(self):
        published = []
        core = BridgeCore(
            "REAL-F01",
            MockCommandAdapter(fail=True),
            lambda topic, payload: published.append(json.loads(payload)) or True,
        )
        command = CommandMessage.from_json(
            '{"commandId":"CMD-I2","vehicleId":"REAL-F01","targetSystem":"ROS2",'
            '"commandCategory":"MOVE","command":"MOVE","payload":{"destination":'
            '{"x":1,"y":2,"heading":90,"frameId":"map"}}}'
        )
        core.process_command(command)
        self.assertEqual("FAILED", published[-1]["result"])

    def test_emergency_can_cancel_queued_move_without_execution(self):
        command = CommandMessage.from_json(
            '{"commandId":"CMD-Q1","vehicleId":"REAL-F01","targetSystem":"ROS2",'
            '"commandCategory":"MOVE","command":"MOVE","payload":{"destination":'
            '{"x":1,"y":2,"heading":90,"frameId":"map"}}}'
        )
        self.assertTrue(self.core.cancel_pending_move(command))
        self.assertEqual("CANCELLED", self.published[-1][1]["result"])
        self.assertEqual([], self.adapter.commands)


if __name__ == "__main__":
    unittest.main()
