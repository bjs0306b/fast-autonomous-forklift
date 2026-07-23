package com.fast.backend.isaac.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isaac Sim 경로 메시지(prompt28.md 6장 합의 규격)의 역직렬화를 검증한다.
 */
class IsaacForkliftPathMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void deserialize_fullPayload_preservesWaypointOrderAndGoal() throws Exception {
        String json = "{\"forkliftId\":\"SIM01\",\"waypoints\":["
                + "{\"x\":1.20,\"y\":0.87},{\"x\":2.40,\"y\":0.87},{\"x\":2.40,\"y\":3.10}"
                + "],\"goal\":{\"x\":2.40,\"y\":3.10,\"direction\":0.0},"
                + "\"timestamp\":\"2026-07-22T10:30:00.123\"}";

        IsaacForkliftPathMessage message = objectMapper.readValue(json, IsaacForkliftPathMessage.class);

        assertThat(message.forkliftId()).isEqualTo("SIM01");
        assertThat(message.waypoints()).extracting(IsaacForkliftPathMessage.Waypoint::x)
                .containsExactly(1.20, 2.40, 2.40);
        assertThat(message.goal().x()).isEqualTo(2.40);
        assertThat(message.goal().y()).isEqualTo(3.10);
        assertThat(message.goal().direction()).isEqualTo(0.0);
    }

    @Test
    void deserialize_emptyWaypoints_isAllowed() throws Exception {
        String json = "{\"forkliftId\":\"SIM01\",\"waypoints\":[],"
                + "\"goal\":{\"x\":1.0,\"y\":1.0,\"direction\":0.0},"
                + "\"timestamp\":\"2026-07-22T10:30:00\"}";

        IsaacForkliftPathMessage message = objectMapper.readValue(json, IsaacForkliftPathMessage.class);

        assertThat(message.waypoints()).isEmpty();
    }
}
