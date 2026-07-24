import json
import math
import unittest

from fast_mqtt_bridge.dto import (
    CommandMessage,
    CommandResult,
    LocationMessage,
    PathMessage,
    StatusMessage,
    normalize_heading,
    quaternion_to_heading,
)


class DtoTest(unittest.TestCase):
    def test_status_json_matches_backend(self):
        payload = json.loads(
            StatusMessage.create(
                "REAL-F01", "MOVING", 85, "2026-07-24T10:00:00+09:00"
            ).to_json()
        )
        self.assertEqual("REAL-F01", payload["forkliftId"])
        self.assertEqual("MOVING", payload["status"])
        self.assertEqual(85, payload["battery"])

    def test_location_json_uses_nested_position_and_message_at(self):
        payload = json.loads(
            LocationMessage.create(
                "REAL-F01", 2.5, 4.1, 90.0, "map", "2026-07-24T10:00:00+09:00"
            ).to_json()
        )
        self.assertEqual({"x": 2.5, "y": 4.1, "frameId": "map"}, payload["position"])
        self.assertEqual(90.0, payload["heading"])
        self.assertIn("messageAt", payload)

    def test_command_json_deserialization(self):
        command = CommandMessage.from_json(
            '{"commandId":"CMD-1","vehicleId":"REAL-F01","targetSystem":"ROS2",'
            '"commandCategory":"MOVE","command":"MOVE","payload":{"destination":'
            '{"x":2.5,"y":4.1,"heading":90,"frameId":"map"}}}'
        )
        self.assertEqual("CMD-1", command.command_id)
        self.assertEqual(2.5, command.destination.x)

    def test_non_object_command_payload_is_rejected(self):
        with self.assertRaises(ValueError):
            CommandMessage.from_json(
                '{"commandId":"CMD-1","vehicleId":"REAL-F01","payload":[]}'
            )

    def test_heading_normalization(self):
        self.assertEqual(270.0, normalize_heading(-90.0))
        self.assertEqual(0.0, normalize_heading(360.0))

    def test_quaternion_to_degree_cardinal_angles(self):
        cases = (
            (0.0, 0.0, 0.0, 1.0, 0.0),
            (0.0, 0.0, math.sin(math.pi / 4), math.cos(math.pi / 4), 90.0),
            (0.0, 0.0, 1.0, 0.0, 180.0),
            (0.0, 0.0, math.sin(-math.pi / 4), math.cos(-math.pi / 4), 270.0),
        )
        for x, y, z, w, expected in cases:
            with self.subTest(expected=expected):
                self.assertAlmostEqual(expected, quaternion_to_heading(x, y, z, w))

    def test_path_empty_and_size_limit(self):
        with self.assertRaises(ValueError):
            PathMessage.create("REAL-F01", "map", [])
        with self.assertRaises(ValueError):
            PathMessage.create("REAL-F01", "map", [(0.0, 0.0, 0.0)] * 2, max_points=1)

    def test_path_json_matches_current_backend_dto(self):
        payload = json.loads(
            PathMessage.create(
                "REAL-F01",
                "map",
                [(1.0, 2.0, 0.0), (2.0, 3.0, 90.0)],
                "2026-07-24T10:00:00+09:00",
            ).to_json()
        )
        self.assertEqual([{"x": 1.0, "y": 2.0}], payload["waypoints"])
        self.assertEqual(
            {"x": 2.0, "y": 3.0, "heading": 90.0},
            payload["goal"],
        )

    def test_command_result_matches_backend_field_names(self):
        command = CommandMessage.from_json(
            '{"commandId":"CMD-1","vehicleId":"REAL-F01","targetSystem":"ROS2",'
            '"commandCategory":"MOVE","command":"MOVE","payload":{"destination":'
            '{"x":0,"y":0,"heading":0,"frameId":"map"}}}'
        )
        payload = json.loads(
            CommandResult.create(
                command, "SUCCESS", "done", "2026-07-24T10:00:05+09:00"
            ).to_json()
        )
        self.assertEqual("SUCCESS", payload["result"])
        self.assertIn("completedAt", payload)
        self.assertNotIn("status", payload)


if __name__ == "__main__":
    unittest.main()
