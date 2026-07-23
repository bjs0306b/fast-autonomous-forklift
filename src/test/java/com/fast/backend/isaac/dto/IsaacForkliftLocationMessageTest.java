package com.fast.backend.isaac.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isaac Sim 위치 메시지(prompt28.md 3장 합의 규격)의 역직렬화를 검증한다.
 */
class IsaacForkliftLocationMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void deserialize_fullPayload_mapsAllFields() throws Exception {
        String json = "{\"forkliftId\":\"SIM01\",\"x\":1.2340,\"y\":0.8720,\"direction\":1.5708,"
                + "\"speed\":0.1500,\"timestamp\":\"2026-07-22T10:30:00.123\"}";

        IsaacForkliftLocationMessage message = objectMapper.readValue(json, IsaacForkliftLocationMessage.class);

        assertThat(message.forkliftId()).isEqualTo("SIM01");
        assertThat(message.x()).isEqualTo(1.2340);
        assertThat(message.y()).isEqualTo(0.8720);
        assertThat(message.direction()).isEqualTo(1.5708);
        assertThat(message.speed()).isEqualTo(0.1500);
        assertThat(message.timestamp()).isEqualTo(LocalDateTime.of(2026, 7, 22, 10, 30, 0, 123_000_000));
    }

    @Test
    void deserialize_secondPrecisionTimestamp_parsesToLocalDateTime() throws Exception {
        String json = "{\"forkliftId\":\"SIM01\",\"x\":1.0,\"y\":1.0,\"direction\":0.0,\"speed\":0.0,"
                + "\"timestamp\":\"2026-07-22T10:30:00\"}";

        IsaacForkliftLocationMessage message = objectMapper.readValue(json, IsaacForkliftLocationMessage.class);

        assertThat(message.timestamp()).isEqualTo(LocalDateTime.of(2026, 7, 22, 10, 30, 0));
    }

    @Test
    void deserialize_zuluSuffixTimestamp_isLeniuentlyAcceptedByJacksonDefault() throws Exception {
        // prompt28.md 1장은 "Z"를 "허용하지 않는 형식"으로 명시했지만, 실제로 Jackson 기본
        // LocalDateTimeDeserializer는 trailing "Z"를 UTC 표시로 관대하게 처리해 그냥 제거하고 파싱에
        // 성공한다(오프셋 변환은 하지 않음 — 단순 문자열 제거) — "+09:00" 같은 진짜 오프셋은 아래
        // deserialize_offsetTimestamp_isRejected에서 확인하듯 정상적으로 거부된다. 이 차이를 실제로
        // 검증해 answer28.md 18장 "미확정 사항"에 기록했다(커스텀 Deserializer 없이는 Z만 선택적으로
        // 막을 수 없음).
        String json = "{\"forkliftId\":\"SIM01\",\"x\":1.0,\"y\":1.0,\"direction\":0.0,\"speed\":0.0,"
                + "\"timestamp\":\"2026-07-22T10:30:00.123Z\"}";

        IsaacForkliftLocationMessage message = objectMapper.readValue(json, IsaacForkliftLocationMessage.class);

        assertThat(message.timestamp()).isEqualTo(LocalDateTime.of(2026, 7, 22, 10, 30, 0, 123_000_000));
    }

    @Test
    void deserialize_offsetTimestamp_isRejected() {
        String json = "{\"forkliftId\":\"SIM01\",\"x\":1.0,\"y\":1.0,\"direction\":0.0,\"speed\":0.0,"
                + "\"timestamp\":\"2026-07-22T10:30:00.123+09:00\"}";

        assertThatThrownBy(() -> objectMapper.readValue(json, IsaacForkliftLocationMessage.class))
                .isInstanceOf(JsonProcessingException.class);
    }
}
