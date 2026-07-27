import unittest
import os
import ssl
import tempfile

from fast_mqtt_bridge.mqtt_policy import (
    MQTT_QOS,
    MQTT_RETAINED,
    configure_tls,
    publish_vehicle_message,
    subscribe_vehicle_command,
)


class FakeClient:
    def __init__(self):
        self.publish_call = None
        self.subscribe_call = None
        self.tls_set_call = None
        self.tls_insecure_call = None

    def publish(self, topic, payload, qos, retain):
        self.publish_call = (topic, payload, qos, retain)
        return object()

    def subscribe(self, topic, qos):
        self.subscribe_call = (topic, qos)
        return object()

    def tls_set(self, **kwargs):
        self.tls_set_call = kwargs

    def tls_insecure_set(self, value):
        self.tls_insecure_call = value


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

    def test_tls_uses_required_ca_verification(self):
        client = FakeClient()
        with tempfile.NamedTemporaryFile() as certificate:
            configure_tls(client, True, certificate.name)
            self.assertEqual(
                os.path.abspath(certificate.name),
                client.tls_set_call["ca_certs"],
            )
        self.assertEqual(ssl.CERT_REQUIRED, client.tls_set_call["cert_reqs"])
        self.assertFalse(client.tls_insecure_call)

    def test_tls_rejects_missing_ca_certificate(self):
        with self.assertRaisesRegex(FileNotFoundError, "does not exist"):
            configure_tls(FakeClient(), True, "/missing/fast-ca.crt")


if __name__ == "__main__":
    unittest.main()

