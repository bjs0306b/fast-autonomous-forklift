package com.fast.backend.embedded.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code forklift/{id}/fork-status} 수신 payload 역직렬화를 검증한다(prompt29.md 8장). */
class EmbeddedForkStatusMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void deserialize_mapsAllFields() throws Exception {
        String json = "{\"forkliftId\":\"REAL01\",\"forkState\":\"BOTTOM\",\"limitBottom\":true,"
                + "\"errorCode\":null,\"timestamp\":\"2026-07-22T10:30:00\"}";

        EmbeddedForkStatusMessage message = objectMapper.readValue(json, EmbeddedForkStatusMessage.class);

        assertThat(message.forkliftId()).isEqualTo("REAL01");
        assertThat(message.forkState()).isEqualTo("BOTTOM");
        assertThat(message.limitBottom()).isTrue();
        assertThat(message.errorCode()).isNull();
    }

    @Test
    void deserialize_forkHeightField_rejectedAsUnknownProperty() {
        // 작업 원칙 12·13번 "포크 높이·limitTop을 절대 사용하지 않는다" — 만약 이런 필드가 오면
        // Jackson의 알 수 없는 속성 거부로 자연스럽게 역직렬화가 실패해야 한다.
        String json = "{\"forkliftId\":\"REAL01\",\"forkState\":\"STOPPED\",\"limitBottom\":false,"
                + "\"forkHeight\":0.5,\"timestamp\":\"2026-07-22T10:30:00\"}";

        assertThatThrownBy(() -> objectMapper.readValue(json, EmbeddedForkStatusMessage.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
