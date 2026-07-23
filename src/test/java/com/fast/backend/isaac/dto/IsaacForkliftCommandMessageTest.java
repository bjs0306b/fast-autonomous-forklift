package com.fast.backend.isaac.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백엔드가 발행하는 Isaac Sim 명령 메시지(prompt28.md 7장 합의 규격)의 직렬화 결과를 검증한다.
 */
class IsaacForkliftCommandMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void serialize_moveCommand_producesAgreedJsonKeysAndValues() throws Exception {
        IsaacForkliftCommandMessage message = new IsaacForkliftCommandMessage(
                "SIM01", "MOVE",
                new IsaacForkliftCommandMessage.Destination(2.40, 3.10, 0.0),
                LocalDateTime.of(2026, 7, 22, 10, 30, 0, 123_000_000));

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertThat(node.get("forkliftId").asText()).isEqualTo("SIM01");
        assertThat(node.get("command").asText()).isEqualTo("MOVE");
        assertThat(node.get("destination").get("x").asDouble()).isEqualTo(2.40);
        assertThat(node.get("destination").get("y").asDouble()).isEqualTo(3.10);
        assertThat(node.get("destination").get("direction").asDouble()).isEqualTo(0.0);
        assertThat(node.get("timestamp").asText()).startsWith("2026-07-22T10:30:00.123");
        // commandId는 합의 JSON에 없는 필드라 절대 나오면 안 된다(prompt28.md 7장 9번).
        assertThat(node.has("commandId")).isFalse();
    }
}
