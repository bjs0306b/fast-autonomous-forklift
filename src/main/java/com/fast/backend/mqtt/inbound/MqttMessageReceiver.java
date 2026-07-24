package com.fast.backend.mqtt.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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

        int payloadBytes = payload == null ? 0 : payload.getBytes(StandardCharsets.UTF_8).length;
        log.info("MQTT message received: topic={}, qos={}, retained={}, payloadBytes={}, receivedAt={}",
                topic, qos, retained, payloadBytes, Instant.now());

        try {
            mqttMessageRouter.route(topic, payload);
            log.debug("MQTT message routed: topic={}", topic);
        } catch (RuntimeException e) {
            // Router와 각 Service가 일차적으로 오류를 격리하지만, 새 메시지 유형이나 예상하지 못한
            // 런타임 오류가 MQTT consumer thread까지 전파되지 않도록 수신 경계에서 마지막으로 막는다.
            // 전체 payload는 민감정보 노출 가능성이 있어 로그에 남기지 않는다.
            log.error("MQTT message discarded after unexpected routing failure: topic={}, error={}",
                    topic, e.getMessage());
        }
    }
}
