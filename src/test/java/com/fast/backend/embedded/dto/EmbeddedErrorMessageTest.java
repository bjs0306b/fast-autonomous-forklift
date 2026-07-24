package com.fast.backend.embedded.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code forklift/{id}/error} 수신 payload 역직렬화를 검증한다(prompt29.md 9장). */
class EmbeddedErrorMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void deserialize_mapsAllFields() throws Exception {
        String json = "{\"forkliftId\":\"REAL01\",\"errorCode\":\"E001\",\"errorSource\":\"DRIVE\","
                + "\"severity\":\"CRITICAL\",\"message\":\"모터 과전류\",\"timestamp\":\"2026-07-22T10:30:00+09:00\"}";

        EmbeddedErrorMessage message = objectMapper.readValue(json, EmbeddedErrorMessage.class);

        assertThat(message.forkliftId()).isEqualTo("REAL01");
        assertThat(message.errorCode()).isEqualTo("E001");
        assertThat(message.errorSource()).isEqualTo("DRIVE");
        assertThat(message.severity()).isEqualTo("CRITICAL");
        assertThat(message.message()).isEqualTo("모터 과전류");
    }

    @Test
    void deserialize_unrecognizedErrorCode_stillDeserializesAsString() throws Exception {
        // errorCode는 후보 목록이 확정되지 않아 String으로만 검증한다(9장 근거) — 임의 문자열도 허용.
        String json = "{\"forkliftId\":\"REAL01\",\"errorCode\":\"E999-UNSEEN\",\"errorSource\":\"SYSTEM\","
                + "\"severity\":\"WARNING\",\"timestamp\":\"2026-07-22T10:30:00+09:00\"}";

        EmbeddedErrorMessage message = objectMapper.readValue(json, EmbeddedErrorMessage.class);

        assertThat(message.errorCode()).isEqualTo("E999-UNSEEN");
    }
}
