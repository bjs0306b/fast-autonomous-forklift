import unittest

from fast_mqtt_bridge.mqtt_policy import (
    MQTT_QOS,
    MQTT_RETAINED,
    publish_vehicle_message,
    subscribe_vehicle_command,
)


class FakeClient:
    def __init__(self):
        self.publish_call = None
        self.subscribe_call = None

    def publish(self, topic, payload, qos, retain):
        self.publish_call = (topic, payload, qos, retain)
        return object()

    def subscribe(self, topic, qos):
        self.subscribe_call = (topic, qos)
        return object()


class MqttPolicyTest(unittest.TestCase):
    def test_publish_qos_one_and_retained_false(self):
        client = FakeClient()
        publish_vehicle_message(client, "forklift/REAL-F01/status", "{}")
        self.assertEqual(
            ("forklift/REAL-F01/status", "{}", 1, False),
            client.publish_call,
        )
        self.assertEqual(1, MQTT_QOS)
        self.assertFalse(MQTT_RETAINED)

    def test_subscribe_qos_one(self):
        client = FakeClient()
        subscribe_vehicle_command(client, "forklift/REAL-F01/command")
        self.assertEqual(("forklift/REAL-F01/command", 1), client.subscribe_call)


if __name__ == "__main__":
    unittest.main()

