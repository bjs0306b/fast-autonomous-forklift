package com.fast.backend.embedded.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code forklift/{id}/command-result} 수신 payload 역직렬화를 검증한다(prompt29.md 7장). 일반 결과와
 * 비상정지 결과를 하나의 레코드로 통합했으므로 두 형태 모두 역직렬화되는지 확인한다.
 */
class EmbeddedCommandResultMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void deserialize_normalResult_mapsAllFields() throws Exception {
        String json = "{\"commandId\":\"CMD-001\",\"forkliftId\":\"REAL01\",\"command\":\"FORK_UP\","
                + "\"result\":\"SUCCESS\",\"forkState\":\"STOPPED\",\"limitBottom\":false,"
                + "\"completedAt\":\"2026-07-22T10:30:00\"}";

        EmbeddedCommandResultMessage message = objectMapper.readValue(json, EmbeddedCommandResultMessage.class);

        assertThat(message.commandId()).isEqualTo("CMD-001");
        assertThat(message.forkliftId()).isEqualTo("REAL01");
        assertThat(message.command()).isEqualTo("FORK_UP");
        assertThat(message.result()).isEqualTo("SUCCESS");
        assertThat(message.forkState()).isEqualTo("STOPPED");
        assertThat(message.limitBottom()).isFalse();
        assertThat(message.emergencyStopApplied()).isNull();
        assertThat(message.stoppedActions()).isNull();
    }

    @Test
    void deserialize_emergencyStopResult_mapsEmergencyFields() throws Exception {
        String json = "{\"commandId\":\"CMD-002\",\"forkliftId\":\"REAL01\",\"command\":\"EMERGENCY_STOP\","
                + "\"result\":\"SUCCESS\",\"emergencyStopApplied\":true,"
                + "\"stoppedActions\":[\"DRIVE\",\"STEERING\",\"FORK\"],\"requiresReset\":true,"
                + "\"completedAt\":\"2026-07-22T10:30:00\"}";

        EmbeddedCommandResultMessage message = objectMapper.readValue(json, EmbeddedCommandResultMessage.class);

        assertThat(message.emergencyStopApplied()).isTrue();
        assertThat(message.stoppedActions()).containsExactly("DRIVE", "STEERING", "FORK");
        assertThat(message.requiresReset()).isTrue();
    }

    @Test
    void deserialize_unknownProperty_throwsJsonProcessingException() {
        String json = "{\"commandId\":\"CMD-003\",\"unknownField\":\"x\"}";

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> objectMapper.readValue(json, EmbeddedCommandResultMessage.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
