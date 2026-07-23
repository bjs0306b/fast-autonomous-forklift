package com.fast.backend.isaac.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isaac Sim 상태 메시지(prompt28.md 4장 합의 규격)와 LWT OFFLINE 최소 메시지(5장)의 역직렬화를 검증한다.
 */
class IsaacForkliftStatusMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void deserialize_fullPayload_mapsAllFields() throws Exception {
        String json = "{\"forkliftId\":\"SIM01\",\"status\":\"MOVING\",\"battery\":87,"
                + "\"forkHeight\":0.120,\"hasCargo\":true,\"cargoId\":\"BOX-0042\","
                + "\"footprint\":{\"length\":0.28,\"width\":0.16},\"timestamp\":\"2026-07-22T10:30:00.123\"}";

        IsaacForkliftStatusMessage message = objectMapper.readValue(json, IsaacForkliftStatusMessage.class);

        assertThat(message.forkliftId()).isEqualTo("SIM01");
        assertThat(message.status()).isEqualTo("MOVING");
        assertThat(message.battery()).isEqualTo(87);
        assertThat(message.forkHeight()).isEqualTo(0.120);
        assertThat(message.hasCargo()).isTrue();
        assertThat(message.cargoId()).isEqualTo("BOX-0042");
        assertThat(message.footprint().length()).isEqualTo(0.28);
        assertThat(message.footprint().width()).isEqualTo(0.16);
        assertThat(message.timestamp()).isEqualTo(LocalDateTime.of(2026, 7, 22, 10, 30, 0, 123_000_000));
    }

    @Test
    void deserialize_lwtOfflineMinimalPayload_allowsNullTimestampAndMissingFields() throws Exception {
        String json = "{\"forkliftId\":\"SIM01\",\"status\":\"OFFLINE\",\"timestamp\":null}";

        IsaacForkliftStatusMessage message = objectMapper.readValue(json, IsaacForkliftStatusMessage.class);

        assertThat(message.forkliftId()).isEqualTo("SIM01");
        assertThat(message.status()).isEqualTo("OFFLINE");
        assertThat(message.timestamp()).isNull();
        assertThat(message.battery()).isNull();
        assertThat(message.forkHeight()).isNull();
        assertThat(message.hasCargo()).isNull();
        assertThat(message.cargoId()).isNull();
        assertThat(message.footprint()).isNull();
    }

    @Test
    void deserialize_missingCargoId_isNull() throws Exception {
        String json = "{\"forkliftId\":\"SIM01\",\"status\":\"IDLE\",\"battery\":100,"
                + "\"forkHeight\":0.0,\"hasCargo\":false,"
                + "\"footprint\":{\"length\":0.28,\"width\":0.16},\"timestamp\":\"2026-07-22T10:30:00\"}";

        IsaacForkliftStatusMessage message = objectMapper.readValue(json, IsaacForkliftStatusMessage.class);

        assertThat(message.cargoId()).isNull();
        assertThat(message.hasCargo()).isFalse();
    }
}
