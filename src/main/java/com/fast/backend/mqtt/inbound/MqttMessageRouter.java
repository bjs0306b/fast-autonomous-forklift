package com.fast.backend.mqtt.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.ai.dto.AiCargoAnalysisMessage;
import com.fast.backend.ai.service.AiCargoAnalysisService;
import com.fast.backend.command.dto.VehicleCommandResultMessage;
import com.fast.backend.command.service.VehicleCommandResultService;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.embedded.dto.EmbeddedErrorMessage;
import com.fast.backend.embedded.dto.EmbeddedForkStatusMessage;
import com.fast.backend.embedded.service.EmbeddedErrorService;
import com.fast.backend.embedded.service.EmbeddedForkStatusService;
import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.forklift.dto.ForkliftStatusMessage;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.forklift.service.ForkliftStatusService;
import com.fast.backend.isaac.dto.IsaacForkliftLocationMessage;
import com.fast.backend.isaac.dto.IsaacForkliftPathMessage;
import com.fast.backend.isaac.dto.IsaacForkliftStatusMessage;
import com.fast.backend.isaac.service.IsaacForkliftLocationService;
import com.fast.backend.isaac.service.IsaacForkliftPathService;
import com.fast.backend.isaac.service.IsaacForkliftStatusService;
import com.fast.backend.station.dto.StationMeasurementMessage;
import com.fast.backend.station.service.StationMeasurementService;
import com.fast.backend.transport.dispatch.TransportCommandResultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 수신 토픽에 따라 페이로드를 알맞은 DTO로 역직렬화하고 처리를 위임한다.
 * 잘못된 JSON이 와도 해당 메시지만 처리 실패로 남기고 애플리케이션은 계속 동작해야 한다.
 *
 * <p>{@code forklift/{id}/location}과 {@code forklift/{id}/status} 토픽은 실물 ROS2 규격(prompt24.md/25.md,
 * "vehicleId" 키·중첩 {@code position} 구조)과 Isaac Sim 합의 규격(prompt28.md, "forkliftId" 키·평면
 * 구조)이 같은 토픽 이름을 공유한다 — 두 스펙 모두 서로 다른 필수 식별자 키를 쓰기 때문에, payload를
 * 한 번 트리로 읽어 키 존재 여부로 두 경로를 구분한다(answer28.md 3장·4장에 이 판별 방식과 그 이유를
 * 문서화했다). 이 판별 로직이 기존 ROS2 경로의 동작을 전혀 바꾸지 않는다 — "vehicleId"가 있는 payload는
 * 지금까지와 동일하게 처리된다.
 */
@Component
public class MqttMessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MqttMessageRouter.class);

    private final ObjectMapper objectMapper;
    private final MqttTopics mqttTopics;
    private final ForkliftStatusService forkliftStatusService;
    private final ForkliftLocationService forkliftLocationService;
    private final AiCargoAnalysisService aiCargoAnalysisService;
    private final IsaacForkliftLocationService isaacForkliftLocationService;
    private final IsaacForkliftStatusService isaacForkliftStatusService;
    private final IsaacForkliftPathService isaacForkliftPathService;
    private final VehicleCommandResultService vehicleCommandResultService;
    private final EmbeddedForkStatusService embeddedForkStatusService;
    private final EmbeddedErrorService embeddedErrorService;
    private final StationMeasurementService stationMeasurementService;
    private final TransportCommandResultService transportCommandResultService;

    public MqttMessageRouter(ObjectMapper objectMapper, MqttTopics mqttTopics,
            ForkliftStatusService forkliftStatusService, ForkliftLocationService forkliftLocationService,
            AiCargoAnalysisService aiCargoAnalysisService,
            IsaacForkliftLocationService isaacForkliftLocationService,
            IsaacForkliftStatusService isaacForkliftStatusService,
            IsaacForkliftPathService isaacForkliftPathService,
            VehicleCommandResultService vehicleCommandResultService,
            EmbeddedForkStatusService embeddedForkStatusService,
            EmbeddedErrorService embeddedErrorService,
            StationMeasurementService stationMeasurementService,
            TransportCommandResultService transportCommandResultService) {
        this.objectMapper = objectMapper;
        this.mqttTopics = mqttTopics;
        this.forkliftStatusService = forkliftStatusService;
        this.forkliftLocationService = forkliftLocationService;
        this.aiCargoAnalysisService = aiCargoAnalysisService;
        this.isaacForkliftLocationService = isaacForkliftLocationService;
        this.isaacForkliftStatusService = isaacForkliftStatusService;
        this.isaacForkliftPathService = isaacForkliftPathService;
        this.vehicleCommandResultService = vehicleCommandResultService;
        this.embeddedForkStatusService = embeddedForkStatusService;
        this.embeddedErrorService = embeddedErrorService;
        this.stationMeasurementService = stationMeasurementService;
        this.transportCommandResultService = transportCommandResultService;
    }

    public void route(String topic, String payload) {
        if (topic == null || topic.isBlank()) {
            log.warn("MQTT message discarded: topic is null or blank");
            return;
        }
        if (!isSupportedTopic(topic)) {
            log.warn("Unknown MQTT topic received: topic={}", topic);
            return;
        }
        if (payload == null || payload.isBlank()) {
            log.warn("MQTT message discarded: topic={}, reason=payload is null or blank", topic);
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                log.warn("MQTT message discarded: topic={}, reason=payload must be a JSON object", topic);
                return;
            }

            if (mqttTopics.isForkliftStatusTopic(topic)) {
                routeStatus(topic, payload);
            } else if (mqttTopics.isForkliftLocationTopic(topic)) {
                routeLocation(topic, payload);
            } else if (mqttTopics.isForkliftPathTopic(topic)) {
                routePath(topic, payload);
            } else if (mqttTopics.isForkliftCommandResultTopic(topic)) {
                routeCommandResult(topic, payload);
            } else if (mqttTopics.isForkliftForkStatusTopic(topic)) {
                routeForkStatus(topic, payload);
            } else if (mqttTopics.isForkliftErrorTopic(topic)) {
                routeEmbeddedError(topic, payload);
            } else if (mqttTopics.isCargoDetectedTopic(topic)) {
                routeCargoDetected(payload);
            } else if (mqttTopics.isStationMeasurementTopic(topic)) {
                routeStationMeasurement(topic, payload);
            }
        } catch (JsonProcessingException e) {
            log.error("MQTT message discarded: topic={}, reason=invalid JSON, error={}", topic, e.getMessage());
        } catch (RuntimeException e) {
            log.error("MQTT message processing failed unexpectedly and was isolated: topic={}, error={}",
                    topic, e.getMessage());
        }
    }

    private boolean isSupportedTopic(String topic) {
        return mqttTopics.isForkliftStatusTopic(topic)
                || mqttTopics.isForkliftLocationTopic(topic)
                || mqttTopics.isForkliftPathTopic(topic)
                || mqttTopics.isForkliftCommandResultTopic(topic)
                || mqttTopics.isForkliftForkStatusTopic(topic)
                || mqttTopics.isForkliftErrorTopic(topic)
                || mqttTopics.isCargoDetectedTopic(topic)
                || mqttTopics.isStationMeasurementTopic(topic);
    }

    /**
     * forklift/{id}/status 라우팅. Isaac 상태 payload는 forkHeight/hasCargo/footprint 중 하나라도
     * 있으면(일반 상태), 또는 "battery" 키 자체가 없으면(LWT OFFLINE 최소 메시지, prompt28.md 5장)
     * Isaac 경로로 판별한다. 그 외(기존 "forkliftId"+"battery" 4필드 Mock 규격)는 기존 경로 그대로다.
     */
    private void routeStatus(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (isIsaacStatusPayload(node)) {
                IsaacForkliftStatusMessage message = objectMapper.treeToValue(node, IsaacForkliftStatusMessage.class);
                if (!isVehicleIdConsistentWithTopic(topic, message.forkliftId())) {
                    return;
                }
                isaacForkliftStatusService.handleStatus(message);
            } else {
                ForkliftStatusMessage message = objectMapper.treeToValue(node, ForkliftStatusMessage.class);
                if (!isVehicleIdConsistentWithTopic(topic, message.forkliftId())) {
                    return;
                }
                forkliftStatusService.handleStatus(message);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse forklift status message: topic={}, error={}", topic, e.getMessage());
        }
    }

    private boolean isIsaacStatusPayload(JsonNode node) {
        return node.has("forkHeight") || node.has("hasCargo") || node.has("footprint") || !node.has("battery");
    }

    /**
     * forklift/{id}/location 라우팅. "forkliftId" 키가 있으면 Isaac 경로, "vehicleId" 키가 있으면
     * 기존 ROS2 경로다(answer28.md 3장 판별 방식).
     */
    private void routeLocation(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.has("forkliftId")) {
                IsaacForkliftLocationMessage message = objectMapper.treeToValue(node, IsaacForkliftLocationMessage.class);
                if (!isVehicleIdConsistentWithTopic(topic, message.forkliftId())) {
                    return;
                }
                isaacForkliftLocationService.handleLocation(message);
            } else {
                ForkliftLocationMessage message = objectMapper.treeToValue(node, ForkliftLocationMessage.class);
                if (!isVehicleIdConsistentWithTopic(topic, message.vehicleId())) {
                    return;
                }
                forkliftLocationService.handleLocation(message);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse forklift location message: topic={}, error={}", topic, e.getMessage());
        }
    }

    /**
     * forklift/{id}/path 라우팅(prompt28.md 6장·9장). 실물 ROS2 쪽에 이 토픽을 쓰는 기존 사례가 없어
     * 판별 없이 곧바로 Isaac 경로로 처리한다.
     */
    private void routePath(String topic, String payload) {
        try {
            IsaacForkliftPathMessage message = objectMapper.readValue(payload, IsaacForkliftPathMessage.class);
            if (!isVehicleIdConsistentWithTopic(topic, message.forkliftId())) {
                return;
            }
            isaacForkliftPathService.handlePath(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse forklift path message: topic={}, error={}", topic, e.getMessage());
        }
    }

    /**
     * forklift/{id}/command-result 라우팅(prompt29.md 13장). 실물 명령 결과는 SIM 경로와 토픽 자체가
     * 달라(신규 suffix) 판별 로직 없이 곧바로 처리한다.
     */
    private void routeCommandResult(String topic, String payload) {
        try {
            VehicleCommandResultMessage message = objectMapper.readValue(payload, VehicleCommandResultMessage.class);
            if (!isVehicleIdConsistentWithTopic(topic, message.vehicleId())) {
                return;
            }
            // 같은 결과 토픽을 차량 명령(embedded_vehicle_command)과 운반 명령(transport_command)이 공유한다.
            // 각 서비스는 자기 commandId만 처리하고 나머지는 조용히 무시하므로, 둘 다 호출해도 안전하다
            // (운반 명령 commandId는 TCMD- 접두어라 서로 충돌하지 않는다, prompt48.md 12장).
            vehicleCommandResultService.handleResult(message);
            transportCommandResultService.handleResult(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse vehicle command result message: topic={}, error={}", topic, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Vehicle command result processing failed unexpectedly: error={}", e.getMessage());
        }
    }

    /** forklift/{id}/fork-status 라우팅(prompt29.md 13장). */
    private void routeForkStatus(String topic, String payload) {
        try {
            EmbeddedForkStatusMessage message = objectMapper.readValue(payload, EmbeddedForkStatusMessage.class);
            if (!isVehicleIdConsistentWithTopic(topic, message.forkliftId())) {
                return;
            }
            embeddedForkStatusService.handleForkStatus(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse embedded fork status message: topic={}, error={}", topic, e.getMessage());
        }
    }

    /** forklift/{id}/error 라우팅(prompt29.md 13장). */
    private void routeEmbeddedError(String topic, String payload) {
        try {
            EmbeddedErrorMessage message = objectMapper.readValue(payload, EmbeddedErrorMessage.class);
            if (!isVehicleIdConsistentWithTopic(topic, message.forkliftId())) {
                return;
            }
            embeddedErrorService.handleError(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse embedded error message: topic={}, error={}", topic, e.getMessage());
        }
    }

    /**
     * cargo/detected는 AI 화물·파렛트 분석 결과 규격이 확정되어(prompt26.md) {@link AiCargoAnalysisMessage}로
     * 역직렬화하고 {@link AiCargoAnalysisService#process}에 위임한다. JSON 역직렬화 실패는 다른 라우팅
     * 메서드와 동일하게 여기서 잡아 해당 메시지만 폐기한다.
     *
     * <p>{@code AiCargoAnalysisService#process}는 검증 실패(BusinessException)만 자체적으로 흡수하고,
     * DB insert 단계에서 발생하는 예상치 못한 {@link RuntimeException}은 트랜잭션 롤백을 위해 일부러
     * 밖으로 전파한다(그 클래스 Javadoc 참고) — 그 예외가 MQTT 소비 스레드까지 올라와 애플리케이션을
     * 죽이지 않도록 여기서 최종적으로 받아 로그만 남긴다.
     */
    private void routeCargoDetected(String payload) {
        try {
            AiCargoAnalysisMessage message = objectMapper.readValue(payload, AiCargoAnalysisMessage.class);
            aiCargoAnalysisService.process(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse AI cargo analysis message: topic={}, error={}",
                    mqttTopics.cargoDetectedTopic(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("AI cargo analysis processing failed unexpectedly: error={}", e.getMessage());
        }
    }

    /**
     * fast/station/{station_id}/measurement 라우팅(prompt16.md 2·8단계, MR !36, FR-101-5). 토픽의
     * {station_id}와 payload의 station_id가 일치하지 않으면 메시지를 폐기하고 경고 로그만 남긴다.
     * 검증 실패(BusinessException)는 Service가 내부에서 흡수하고, DB insert 단계의 예상치 못한
     * RuntimeException은 트랜잭션 롤백을 위해 밖으로 전파되므로 여기서 최종적으로 받아 로그만 남긴다.
     */
    private void routeStationMeasurement(String topic, String payload) {
        try {
            StationMeasurementMessage message = objectMapper.readValue(payload, StationMeasurementMessage.class);
            String topicStationId = mqttTopics.extractStationId(topic);
            if (!topicStationId.equals(message.stationId())) {
                log.warn("Station ID mismatch between topic and payload: topic={}, topicStationId={}, "
                                + "payloadStationId={}",
                        topic, topicStationId, message.stationId());
                return;
            }
            stationMeasurementService.process(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse station measurement message: topic={}, error={}", topic, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Station measurement processing failed unexpectedly: topic={}, error={}", topic, e.getMessage());
        }
    }

    /**
     * MQTT 토픽에 담긴 vehicleId(forklift/{vehicleId}/{종류} 형태)와 페이로드 안의 forkliftId/vehicleId가
     * 일치하는지 확인한다(prompt20.md 11장 "vehicleId 불일치" 시 DB 반영·WebSocket 브로드캐스트 모두
     * 하지 말아야 함). 이 메서드는 isForkliftStatusTopic/isForkliftLocationTopic/isForkliftPathTopic이
     * 이미 true로 확인된 토픽에서만 호출되므로 {@link MqttTopics#extractForkliftId}는 항상 매칭에 성공한다.
     */
    private boolean isVehicleIdConsistentWithTopic(String topic, String payloadVehicleId) {
        String topicVehicleId = mqttTopics.extractForkliftId(topic);
        if (!topicVehicleId.equals(payloadVehicleId)) {
            log.warn("Vehicle ID mismatch between topic and payload: topic={}, topicVehicleId={}, "
                            + "payloadVehicleId={}",
                    topic, topicVehicleId, payloadVehicleId);
            return false;
        }
        return true;
    }
}
