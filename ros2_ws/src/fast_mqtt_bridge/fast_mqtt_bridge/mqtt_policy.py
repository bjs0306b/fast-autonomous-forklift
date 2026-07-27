"""Fixed MQTT policy for all vehicle topics."""

import os
import ssl


MQTT_QOS = 1
MQTT_RETAINED = False


def configure_tls(client, enabled: bool, ca_cert: str) -> None:
    if not enabled:
        return

    certificate_path = os.path.abspath(os.path.expanduser(ca_cert))
    if not os.path.isfile(certificate_path):
        raise FileNotFoundError(
            f"MQTT CA certificate does not exist: {certificate_path}"
        )

    client.tls_set(
        ca_certs=certificate_path,
        cert_reqs=ssl.CERT_REQUIRED,
    )
    client.tls_insecure_set(False)


def publish_vehicle_message(client, topic: str, payload: str):
    return client.publish(topic, payload, qos=MQTT_QOS, retain=MQTT_RETAINED)


def subscribe_vehicle_command(client, topic: str):
    return client.subscribe(topic, qos=MQTT_QOS)

