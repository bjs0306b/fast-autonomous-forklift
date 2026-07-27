import os
import unittest
from unittest.mock import patch

from fast_mqtt_bridge.config import BridgeConfig


class ConfigTest(unittest.TestCase):
    def test_safe_defaults_are_qos_one_and_not_retained(self):
        config = BridgeConfig()
        config.validate()
        self.assertEqual(1, config.mqtt_qos)
        self.assertFalse(config.mqtt_retained)
        self.assertFalse(config.mqtt_tls_enabled)

    def test_environment_overrides_parameter(self):
        parameters = {"mqtt_host": "yaml-broker", "vehicle_id": "YAML-F01"}
        with patch.dict(
            os.environ,
            {"MQTT_BROKER_HOST": "env-broker", "VEHICLE_ID": "ENV-F01"},
            clear=False,
        ):
            config = BridgeConfig.from_parameters(
                lambda name, default: parameters.get(name, default)
            )
        self.assertEqual("env-broker", config.mqtt_host)
        self.assertEqual("ENV-F01", config.vehicle_id)

    def test_tls_environment_overrides_parameters(self):
        parameters = {
            "mqtt_tls_enabled": False,
            "mqtt_ca_cert": "/yaml/ca.crt",
        }
        with patch.dict(
            os.environ,
            {
                "MQTT_TLS_ENABLED": "true",
                "MQTT_CA_CERT": "/env/ca.crt",
            },
            clear=False,
        ):
            config = BridgeConfig.from_parameters(
                lambda name, default: parameters.get(name, default)
            )
        self.assertTrue(config.mqtt_tls_enabled)
        self.assertEqual("/env/ca.crt", config.mqtt_ca_cert)

    def test_tls_requires_ca_certificate(self):
        with self.assertRaisesRegex(ValueError, "mqtt_ca_cert"):
            BridgeConfig(mqtt_tls_enabled=True).validate()


if __name__ == "__main__":
    unittest.main()

