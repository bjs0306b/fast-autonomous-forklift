package com.fast.backend.mqtt.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.command.dto.VehicleCommandResultMessage;
import com.fast.backend.command.service.VehicleCommandResultService;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.forklift.dto.ForkliftStatusMessage;
import com.fast.backend.forklift.dto.LegacyIsaacLocationMessage;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.forklift.service.ForkliftStatusService;
import com.fast.backend.isaac.dto.IsaacForkliftPathMessage;
import com.fast.backend.isaac.dto.IsaacVehicleTelemetryMessage;
import com.fast.backend.isaac.dto.IsaacVehicleEventMessage;
import com.fast.backend.isaac.service.IsaacVehicleEventService;
import com.fast.backend.isaac.service.IsaacForkliftPathService;
import com.fast.backend.isaac.service.IsaacVehicleTelemetryService;
import com.fast.backend.station.dto.VehicleArrivedMessage;
import com.fast.backend.station.service.StationArrivalService;
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
    private final VehicleLocationMessageAdapter locationMessageAdapter;
    private final IsaacForkliftPathService pathService;
    private final VehicleCommandResultService commandResultService;
    private final IsaacVehicleTelemetryService isaacTelemetryService;
    private final IsaacVehicleEventService isaacEventService;
    private final StationArrivalService stationArrivalService;

    public MqttMessageRouter(
            ObjectMapper objectMapper,
            MqttTopics topics,
            ForkliftStatusService statusService,
            ForkliftLocationService locationService,
            VehicleLocationMessageAdapter locationMessageAdapter,
            IsaacForkliftPathService pathService,
            VehicleCommandResultService commandResultService,
            IsaacVehicleTelemetryService isaacTelemetryService,
            IsaacVehicleEventService isaacEventService,
            StationArrivalService stationArrivalService) {
        this.objectMapper = objectMapper;
        this.topics = topics;
        this.statusService = statusService;
        this.locationService = locationService;
        this.locationMessageAdapter = locationMessageAdapter;
        this.pathService = pathService;
        this.commandResultService = commandResultService;
        this.isaacTelemetryService = isaacTelemetryService;
        this.isaacEventService = isaacEventService;
        this.stationArrivalService = stationArrivalService;
    }

    public void route(String topic, String payload) {
        if (topic == null || topic.isBlank() || payload == null) {
            log.warn("MQTT message discarded: blank topic or payload");
            return;
        }
        if (!isSupported(topic)) {
            log.warn("Unknown MQTT topic received: topic={}", topic);
            return;
        }
        try {
            JsonNode root = payload.isBlank() && topics.isForkliftArrivedTopic(topic)
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                log.warn("MQTT message discarded: topic={}, reason=payload must be an object", topic);
                return;
            }
            if (topics.isForkliftStatusTopic(topic)) {
                ForkliftStatusMessage message = objectMapper.treeToValue(root, ForkliftStatusMessage.class);
                if (matchesTopicVehicle(topic, message.forkliftId())) statusService.handleStatus(message);
            } else if (topics.isForkliftLocationTopic(topic)) {
                routeLocation(topic, root);
            } else if (topics.isForkliftPathTopic(topic)) {
                IsaacForkliftPathMessage message = objectMapper.treeToValue(root, IsaacForkliftPathMessage.class);
                if (matchesTopicVehicle(topic, message.forkliftId())) pathService.handlePath(message);
            } else if (topics.isForkliftCommandResultTopic(topic)) {
                VehicleCommandResultMessage message = objectMapper.treeToValue(root, VehicleCommandResultMessage.class);
                if (matchesTopicVehicle(topic, message.vehicleId())) {
                    commandResultService.handleResult(message);
                }
            } else if (topics.isIsaacTelemetryTopic(topic)) {
                // Isaac 은 DB 와 다른 ID(sim01)를 쓰므로 여기서 ID 를 대조하지 않는다. 정규화와 불일치
                // 경고는 IsaacVehicleTelemetryService 가 별칭표를 보고 판단한다.
                IsaacVehicleTelemetryMessage message =
                        objectMapper.treeToValue(root, IsaacVehicleTelemetryMessage.class);
                isaacTelemetryService.handleTelemetry(topics.extractIsaacVehicleId(topic), message);
            } else if (topics.isIsaacEventTopic(topic)) {
                IsaacVehicleEventMessage message =
                        objectMapper.treeToValue(root, IsaacVehicleEventMessage.class);
                isaacEventService.handleEvent(topics.extractIsaacVehicleId(topic), message);
            } else if (topics.isForkliftArrivedTopic(topic)) {
                VehicleArrivedMessage message = objectMapper.treeToValue(root, VehicleArrivedMessage.class);
                stationArrivalService.handleArrival(topics.extractForkliftId(topic), message);
            }
        } catch (JsonProcessingException e) {
            log.error("MQTT message discarded: topic={}, reason=invalid JSON, error={}", topic, e.getMessage());
        } catch (RuntimeException e) {
            log.error("MQTT message processing failed and was isolated: topic={}, error={}", topic, e.getMessage());
        }
    }

    private void routeLocation(String topic, JsonNode root) throws JsonProcessingException {
        ForkliftLocationMessage message;
        if (root.hasNonNull("vehicleId")) {
            message = objectMapper.treeToValue(root, ForkliftLocationMessage.class);
        } else if (root.hasNonNull("forkliftId")) {
            LegacyIsaacLocationMessage legacyMessage =
                    objectMapper.treeToValue(root, LegacyIsaacLocationMessage.class);
            message = locationMessageAdapter.fromLegacyIsaac(legacyMessage);
        } else {
            log.warn("Vehicle location discarded: topic={}, reason=vehicle identifier missing", topic);
            return;
        }

        if (matchesTopicVehicle(topic, message.vehicleId())) {
            locationService.handleLocation(message);
        }
    }

    private boolean isSupported(String topic) {
        return topics.isForkliftStatusTopic(topic)
                || topics.isForkliftLocationTopic(topic)
                || topics.isForkliftPathTopic(topic)
                || topics.isForkliftCommandResultTopic(topic)
                || topics.isIsaacTelemetryTopic(topic)
                || topics.isIsaacEventTopic(topic)
                || topics.isForkliftArrivedTopic(topic);
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
