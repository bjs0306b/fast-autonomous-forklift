package com.fast.backend.isaac.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isaac Sim 위치 메시지(prompt28.md 3장 합의 규격)의 역직렬화를 검증한다.
 */
class IsaacForkliftLocationMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 실제 애플리케이션과 동일하게 구성한다. application.yml의
        // spring.jackson.deserialization.adjust-dates-to-context-time-zone=false 가 없으면 Jackson이
        // 수신한 +09:00을 컨텍스트 타임존(기본 UTC)으로 변환해버려, 오프셋 보존 검증이 실제 동작과
        // 다른 결과를 낸다(순간 값은 같지만 offset이 Z로 바뀐다).
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    }

    @Test
    void deserialize_fullPayload_mapsAllFields() throws Exception {
        String json = "{\"forkliftId\":\"SIM01\",\"x\":1.2340,\"y\":0.8720,\"direction\":1.5708,"
                + "\"speed\":0.1500,\"timestamp\":\"2026-07-22T10:30:00.123+09:00\"}";

        IsaacForkliftLocationMessage message = objectMapper.readValue(json, IsaacForkliftLocationMessage.class);

        assertThat(message.forkliftId()).isEqualTo("SIM01");
        assertThat(message.x()).isEqualTo(1.2340);
        assertThat(message.y()).isEqualTo(0.8720);
        assertThat(message.heading()).isEqualTo(1.5708);
        assertThat(message.speed()).isEqualTo(0.1500);
        assertThat(message.timestamp()).isEqualTo(LocalDateTime.of(2026, 7, 22, 10, 30, 0, 123_000_000).atOffset(java.time.ZoneOffset.ofHours(9)));
    }

    @Test
    void deserialize_secondPrecisionTimestamp_parsesToLocalDateTime() throws Exception {
        String json = "{\"forkliftId\":\"SIM01\",\"x\":1.0,\"y\":1.0,\"direction\":0.0,\"speed\":0.0,"
                + "\"timestamp\":\"2026-07-22T10:30:00+09:00\"}";

        IsaacForkliftLocationMessage message = objectMapper.readValue(json, IsaacForkliftLocationMessage.class);

        assertThat(message.timestamp()).isEqualTo(LocalDateTime.of(2026, 7, 22, 10, 30, 0).atOffset(java.time.ZoneOffset.ofHours(9)));
    }

    @Test
    void deserialize_zuluSuffixTimestamp_isParsedAsUtcInstant() throws Exception {
        // 타입이 OffsetDateTime이 되면서(prompt32.md 1장 6번) "Z"가 문자열로 잘려 나가는 게 아니라
        // UTC 오프셋으로 정확히 해석된다 — 같은 순간을 +09:00으로 표현하면 19:30이다.
        String json = "{\"forkliftId\":\"SIM01\",\"x\":1.0,\"y\":1.0,\"heading\":0.0,\"speed\":0.0,"
                + "\"timestamp\":\"2026-07-22T10:30:00.123Z\"}";

        IsaacForkliftLocationMessage message = objectMapper.readValue(json, IsaacForkliftLocationMessage.class);

        assertThat(message.timestamp().toInstant())
                .isEqualTo(OffsetDateTime.of(2026, 7, 22, 10, 30, 0, 123_000_000, ZoneOffset.UTC).toInstant());
        assertThat(message.timestamp().atZoneSameInstant(ZoneOffset.ofHours(9)).getHour()).isEqualTo(19);
    }

    @Test
    void deserialize_offsetTimestamp_isAcceptedAndPreservesOffset() throws Exception {
        // prompt32.md 1장 6번 확정: 통신 시각은 Asia/Seoul(+09:00) ISO-8601이다. 과거에는 이 payload가
        // 거부됐지만 이제는 정상 규격이며, 오프셋이 손실 없이 보존돼야 한다.
        String json = "{\"forkliftId\":\"SIM01\",\"x\":1.0,\"y\":1.0,\"heading\":0.0,\"speed\":0.0,"
                + "\"timestamp\":\"2026-07-22T10:30:00.123+09:00\"}";

        IsaacForkliftLocationMessage message = objectMapper.readValue(json, IsaacForkliftLocationMessage.class);

        assertThat(message.timestamp())
                .isEqualTo(OffsetDateTime.of(2026, 7, 22, 10, 30, 0, 123_000_000, ZoneOffset.ofHours(9)));
        assertThat(message.timestamp().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    void deserialize_directionAlias_isStillAcceptedDuringTransition() throws Exception {
        // prompt32.md 1장 5번: 표준 필드는 heading이지만, 아직 전환하지 않은 Isaac 브리지를 위해
        // direction을 읽기 alias로 허용한다(값 자체는 변환하지 않는다 — 단위 전환은 발행 측 책임).
        String json = "{\"forkliftId\":\"SIM01\",\"x\":1.0,\"y\":1.0,\"direction\":90.0,\"speed\":0.0,"
                + "\"timestamp\":\"2026-07-22T10:30:00+09:00\"}";

        IsaacForkliftLocationMessage message = objectMapper.readValue(json, IsaacForkliftLocationMessage.class);

        assertThat(message.heading()).isEqualTo(90.0);
    }
}
