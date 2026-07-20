package com.fast.backend.mqtt.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.forklift.dto.ForkliftStatusMessage;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.forklift.service.ForkliftStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 수신 토픽에 따라 페이로드를 알맞은 DTO로 역직렬화하고 처리를 위임한다.
 * 잘못된 JSON이 와도 해당 메시지만 처리 실패로 남기고 애플리케이션은 계속 동작해야 한다.
 */
@Component
public class MqttMessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MqttMessageRouter.class);

    private final ObjectMapper objectMapper;
    private final MqttTopics mqttTopics;
    private final ForkliftStatusService forkliftStatusService;
    private final ForkliftLocationService forkliftLocationService;

    public MqttMessageRouter(ObjectMapper objectMapper, MqttTopics mqttTopics,
            ForkliftStatusService forkliftStatusService, ForkliftLocationService forkliftLocationService) {
        this.objectMapper = objectMapper;
        this.mqttTopics = mqttTopics;
        this.forkliftStatusService = forkliftStatusService;
        this.forkliftLocationService = forkliftLocationService;
    }

    public void route(String topic, String payload) {
        if (mqttTopics.isForkliftStatusTopic(topic)) {
            routeStatus(topic, payload);
        } else if (mqttTopics.isForkliftLocationTopic(topic)) {
            routeLocation(topic, payload);
        } else if (mqttTopics.isCargoDetectedTopic(topic)) {
            routeCargoDetected(payload);
        } else {
            log.warn("Unknown MQTT topic received: topic={}", topic);
        }
    }

    private void routeStatus(String topic, String payload) {
        try {
            ForkliftStatusMessage message = objectMapper.readValue(payload, ForkliftStatusMessage.class);
            forkliftStatusService.handleStatus(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse forklift status message: topic={}, error={}", topic, e.getMessage());
        }
    }

    private void routeLocation(String topic, String payload) {
        try {
            ForkliftLocationMessage message = objectMapper.readValue(payload, ForkliftLocationMessage.class);
            forkliftLocationService.handleLocation(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse forklift location message: topic={}, error={}", topic, e.getMessage());
        }
    }

    /**
     * cargo/detected는 아직 확정된 DTO가 없어 원본 JSON 로그만 남긴다.
     * 규격이 확정되면 이 메서드를 확장 지점으로 삼아 별도 DTO/서비스로 위임하면 된다.
     */
    private void routeCargoDetected(String payload) {
        log.info("Cargo detected raw payload received: payload={}", payload);
    }
}
