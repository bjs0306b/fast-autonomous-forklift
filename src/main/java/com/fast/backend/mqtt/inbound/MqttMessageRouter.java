package com.fast.backend.mqtt.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.command.dto.VehicleCommandResultMessage;
import com.fast.backend.command.service.VehicleCommandResultService;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.forklift.dto.ForkliftStatusMessage;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.forklift.service.ForkliftStatusService;
import com.fast.backend.isaac.dto.IsaacForkliftPathMessage;
import com.fast.backend.isaac.service.IsaacForkliftPathService;
import com.fast.backend.station.dto.StationMeasurementCreateRequest;
import com.fast.backend.station.service.StationMeasurementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 현재 저장소에서 실제 발행 주체가 확인된 메시지 계약만 분기한다. */
@Component
public class MqttMessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MqttMessageRouter.class);

    private final ObjectMapper objectMapper;
    private final MqttTopics topics;
    private final ForkliftStatusService statusService;
    private final ForkliftLocationService locationService;
    private final IsaacForkliftPathService pathService;
    private final VehicleCommandResultService commandResultService;
    private final StationMeasurementService measurementService;

    public MqttMessageRouter(
            ObjectMapper objectMapper,
            MqttTopics topics,
            ForkliftStatusService statusService,
            ForkliftLocationService locationService,
            IsaacForkliftPathService pathService,
            VehicleCommandResultService commandResultService,
            StationMeasurementService measurementService) {
        this.objectMapper = objectMapper;
        this.topics = topics;
        this.statusService = statusService;
        this.locationService = locationService;
        this.pathService = pathService;
        this.commandResultService = commandResultService;
        this.measurementService = measurementService;
    }

    public void route(String topic, String payload) {
        if (topic == null || topic.isBlank() || payload == null || payload.isBlank()) {
            log.warn("MQTT message discarded: blank topic or payload");
            return;
        }
        if (!isSupported(topic)) {
            log.warn("Unknown MQTT topic received: topic={}", topic);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                log.warn("MQTT message discarded: topic={}, reason=payload must be an object", topic);
                return;
            }
            if (topics.isForkliftStatusTopic(topic)) {
                ForkliftStatusMessage message = objectMapper.treeToValue(root, ForkliftStatusMessage.class);
                if (matchesTopicVehicle(topic, message.forkliftId())) statusService.handleStatus(message);
            } else if (topics.isForkliftLocationTopic(topic)) {
                ForkliftLocationMessage message = objectMapper.treeToValue(root, ForkliftLocationMessage.class);
                if (matchesTopicVehicle(topic, message.vehicleId())) locationService.handleLocation(message);
            } else if (topics.isForkliftPathTopic(topic)) {
                IsaacForkliftPathMessage message = objectMapper.treeToValue(root, IsaacForkliftPathMessage.class);
                if (matchesTopicVehicle(topic, message.forkliftId())) pathService.handlePath(message);
            } else if (topics.isForkliftCommandResultTopic(topic)) {
                VehicleCommandResultMessage message = objectMapper.treeToValue(root, VehicleCommandResultMessage.class);
                if (matchesTopicVehicle(topic, message.vehicleId())) {
                    commandResultService.handleResult(message);
                }
            } else if (topics.isStationMeasurementTopic(topic)) {
                measurementService.create(objectMapper.treeToValue(root, StationMeasurementCreateRequest.class));
            }
        } catch (JsonProcessingException e) {
            log.error("MQTT message discarded: topic={}, reason=invalid JSON, error={}", topic, e.getMessage());
        } catch (RuntimeException e) {
            log.error("MQTT message processing failed and was isolated: topic={}, error={}", topic, e.getMessage());
        }
    }

    private boolean isSupported(String topic) {
        return topics.isForkliftStatusTopic(topic)
                || topics.isForkliftLocationTopic(topic)
                || topics.isForkliftPathTopic(topic)
                || topics.isForkliftCommandResultTopic(topic)
                || topics.isStationMeasurementTopic(topic);
    }

    private boolean matchesTopicVehicle(String topic, String payloadVehicleId) {
        String topicVehicleId = topics.extractForkliftId(topic);
        if (!topicVehicleId.equals(payloadVehicleId)) {
            log.warn("Vehicle ID mismatch: topic={}, topicVehicleId={}, payloadVehicleId={}",
                    topic, topicVehicleId, payloadVehicleId);
            return false;
        }
        return true;
    }
}
