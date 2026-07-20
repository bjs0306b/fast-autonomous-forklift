package com.fast.backend.mqtt.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * mqttInputChannel(Inbound Adapter의 Output Channel)로 들어온 원시 메시지를 받아
 * 수신 정보를 로그로 남기고 {@link MqttMessageRouter}에게 라우팅을 위임한다.
 */
@Component
public class MqttMessageReceiver {

    private static final Logger log = LoggerFactory.getLogger(MqttMessageReceiver.class);

    private final MqttMessageRouter mqttMessageRouter;

    public MqttMessageReceiver(MqttMessageRouter mqttMessageRouter) {
        this.mqttMessageRouter = mqttMessageRouter;
    }

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void receive(Message<String> message) {
        String topic = (String) message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
        Object qos = message.getHeaders().get(MqttHeaders.RECEIVED_QOS);
        Object retained = message.getHeaders().get(MqttHeaders.RECEIVED_RETAINED);
        String payload = message.getPayload();

        log.info("MQTT message received: topic={}, qos={}, retained={}, payload={}, receivedAt={}",
                topic, qos, retained, payload, Instant.now());

        mqttMessageRouter.route(topic, payload);
    }
}
