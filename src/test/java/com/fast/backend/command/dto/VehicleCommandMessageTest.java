package com.fast.backend.command.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수신 측(ROS2 브리지 / 임베디드 펌웨어)이 실제로 받는 명령 JSON 계약을 검증한다.
 */
class VehicleCommandMessageTest {

    private static final OffsetDateTime TIMESTAMP =
            OffsetDateTime.of(2026, 7, 23, 11, 20, 27, 0, ZoneOffset.ofHours(9));

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    }

    @Test
    void serialize_moveCommand_matchesConfirmedExample() throws Exception {
        VehicleCommandMessage message = new VehicleCommandMessage(
                "CMD-001", "SIM-F01", VehicleCommandTargetSystem.ROS2, VehicleCommandCategory.MOVE,
                VehicleCommandType.MOVE,
                VehicleCommandPayload.ofDestination(new VehicleCommandDestination(5.0, 6.0, 180.0, "map")),
                TIMESTAMP);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertThat(node.get("commandId").asText()).isEqualTo("CMD-001");
        assertThat(node.get("vehicleId").asText()).isEqualTo("SIM-F01");
        assertThat(node.get("targetSystem").asText()).isEqualTo("ROS2");
        assertThat(node.get("commandCategory").asText()).isEqualTo("MOVE");
        assertThat(node.get("command").asText()).isEqualTo("MOVE");
        assertThat(node.get("payload").get("destination").get("x").asDouble()).isEqualTo(5.0);
        assertThat(node.get("payload").get("destination").get("y").asDouble()).isEqualTo(6.0);
        assertThat(node.get("payload").get("destination").get("heading").asDouble()).isEqualTo(180.0);
        assertThat(node.get("payload").get("destination").get("frameId").asText()).isEqualTo("map");
        assertThat(node.get("destination").get("x").asDouble()).isEqualTo(5.0);
        assertThat(node.get("destination").get("y").asDouble()).isEqualTo(6.0);
        assertThat(node.get("destination").get("direction").asDouble()).isCloseTo(Math.PI,
                org.assertj.core.data.Offset.offset(1.0e-12));
        assertThat(node.has("reason")).isFalse();
        assertThat(node.get("timestamp").asText()).isEqualTo("2026-07-23T11:20:27+09:00");
    }

    @Test
    void serialize_embeddedCommand_hasEmptyPayloadObjectNotNull() throws Exception {
        // 수신 측이 payload 키의 존재 여부를 분기하지 않고 항상 같은 모양으로 읽을 수 있어야 한다.
        VehicleCommandMessage message = new VehicleCommandMessage(
                "CMD-002", "REAL-F01", VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.FORK,
                VehicleCommandType.FORK_UP, VehicleCommandPayload.empty(), TIMESTAMP);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertThat(node.get("payload").isObject()).isTrue();
        assertThat(node.get("payload").size()).isZero();
        assertThat(node.has("destination")).isFalse();
        assertThat(node.has("reason")).isFalse();
    }

    @Test
    void serialize_emergencyStop_carriesTheThreeFieldsReceiversBranchOn() throws Exception {
        VehicleCommandMessage message = new VehicleCommandMessage(
                "CMD-003", "REAL-F01", VehicleCommandTargetSystem.ALL, VehicleCommandCategory.SAFETY,
                VehicleCommandType.EMERGENCY_STOP, VehicleCommandPayload.empty(), TIMESTAMP);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertThat(node.get("targetSystem").asText()).isEqualTo("ALL");
        assertThat(node.get("commandCategory").asText()).isEqualTo("SAFETY");
        assertThat(node.get("command").asText()).isEqualTo("EMERGENCY_STOP");
    }

    @Test
    void serialize_timestamp_alwaysKeepsTheSeoulOffset() throws Exception {
        VehicleCommandMessage message = new VehicleCommandMessage(
                "CMD-004", "REAL-F01", VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.SAFETY,
                VehicleCommandType.STOP, VehicleCommandPayload.empty(), TIMESTAMP);

        String json = objectMapper.writeValueAsString(message);

        assertThat(json).contains("\"timestamp\":\"2026-07-23T11:20:27+09:00\"");
    }

    @Test
    void deserialize_destinationDirectionAlias_isAcceptedDuringTransition() throws Exception {
        String json = "{\"x\":1.0,\"y\":2.0,\"direction\":90.0,\"frameId\":\"odom\"}";

        VehicleCommandDestination destination = objectMapper.readValue(json, VehicleCommandDestination.class);

        assertThat(destination.heading()).isEqualTo(90.0);
        assertThat(destination.frameId()).isEqualTo("odom");
    }
}
