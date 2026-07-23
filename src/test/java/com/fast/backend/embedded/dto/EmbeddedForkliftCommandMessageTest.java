package com.fast.backend.embedded.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백엔드가 {@code forklift/{id}/command} 토픽으로 발행하는 실물 명령 payload의 직렬화 결과를 검증한다
 * (prompt29.md 5장 합의 규격). Isaac SIM 명령({@code destination} 포함)과 달리 {@code commandId}가
 * 있고 {@code destination}은 없어야 한다.
 */
class EmbeddedForkliftCommandMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void serialize_command_producesAgreedJsonKeysAndValues() throws Exception {
        EmbeddedForkliftCommandMessage message = new EmbeddedForkliftCommandMessage(
                "CMD-001", "REAL01", "FORK_UP", "화물 상차",
                LocalDateTime.of(2026, 7, 22, 10, 30, 0));

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertThat(node.get("commandId").asText()).isEqualTo("CMD-001");
        assertThat(node.get("forkliftId").asText()).isEqualTo("REAL01");
        assertThat(node.get("command").asText()).isEqualTo("FORK_UP");
        assertThat(node.get("reason").asText()).isEqualTo("화물 상차");
        assertThat(node.get("timestamp").asText()).startsWith("2026-07-22T10:30:00");
        assertThat(node.has("destination")).isFalse();
    }

    @Test
    void serialize_nullReason_omitsOrNullsReasonWithoutError() throws Exception {
        EmbeddedForkliftCommandMessage message = new EmbeddedForkliftCommandMessage(
                "CMD-002", "REAL01", "STOP", null, LocalDateTime.of(2026, 7, 22, 10, 30, 0));

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertThat(node.get("reason").isNull()).isTrue();
    }
}
