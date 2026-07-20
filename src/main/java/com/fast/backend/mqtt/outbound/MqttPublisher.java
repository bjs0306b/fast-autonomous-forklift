package com.fast.backend.mqtt.outbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.mqtt.gateway.MqttGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MQTT 발행 책임을 도메인 Service로부터 분리한 컴포넌트.
 * 도메인 Service → MqttPublisher → MqttGateway → Outbound Channel → MQTT Broker 순으로 이어진다.
 */
@Component
public class MqttPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);

    private final MqttGateway mqttGateway;
    private final ObjectMapper objectMapper;

    public MqttPublisher(MqttGateway mqttGateway, ObjectMapper objectMapper) {
        this.mqttGateway = mqttGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * Java 객체를 Jackson으로 JSON 직렬화한 뒤 발행한다.
     */
    public void publish(Object payload, String topic, int qos, boolean retained) {
        publishRaw(serialize(payload), topic, qos, retained);
    }

    /**
     * 이미 문자열로 만들어진 페이로드를 그대로 발행한다(예: MQTT 테스트 발행 API).
     */
    public void publishRaw(String payload, String topic, int qos, boolean retained) {
        log.info("Publishing MQTT message: topic={}, qos={}, retained={}", topic, qos, retained);
        mqttGateway.publish(payload, topic, qos, retained);
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize MQTT payload: type={}, error={}",
                    payload == null ? "null" : payload.getClass().getName(), e.getMessage());
            throw new MqttPublishException("Failed to serialize MQTT payload", e);
        }
    }
}
