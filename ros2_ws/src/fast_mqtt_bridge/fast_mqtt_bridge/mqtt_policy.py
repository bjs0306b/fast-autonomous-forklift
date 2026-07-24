"""Fixed MQTT policy for all vehicle topics."""

MQTT_QOS = 1
MQTT_RETAINED = False


def publish_vehicle_message(client, topic: str, payload: str):
    return client.publish(topic, payload, qos=MQTT_QOS, retain=MQTT_RETAINED)


def subscribe_vehicle_command(client, topic: str):
    return client.subscribe(topic, qos=MQTT_QOS)

