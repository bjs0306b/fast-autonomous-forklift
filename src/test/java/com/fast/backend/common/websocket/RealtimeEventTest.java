package com.fast.backend.common.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeEventTest {
    private static final OffsetDateTime OCCURRED_AT =
            OffsetDateTime.of(2026, 7, 23, 11, 20, 27, 0, ZoneOffset.ofHours(9));
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void serializesStableEnvelope() throws Exception {
        RealtimeEvent<Map<String, Object>> event = RealtimeEvent.of(
                RealtimeEventType.VEHICLE_STATUS_UPDATED, "FORKLIFT-01", OCCURRED_AT,
                Map.of("status", "MOVING"));
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(event));
        assertThat(node.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("eventType", "vehicleId", "occurredAt", "data");
        assertThat(node.get("occurredAt").asText()).isEqualTo("2026-07-23T11:20:27+09:00");
    }

    @Test
    void stationEventAllowsNullVehicleId() throws Exception {
        RealtimeEvent<Map<String, Object>> event = RealtimeEvent.of(
                RealtimeEventType.STATION_MEASUREMENT_COMPLETED, null, OCCURRED_AT,
                Map.of("measurementId", "M-1"));
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(event));
        assertThat(node.get("vehicleId").isNull()).isTrue();
    }

    @Test
    void eventTypesContainOnlyEmittedContracts() {
        assertThat(RealtimeEventType.values()).containsExactlyInAnyOrder(
                RealtimeEventType.VEHICLE_STATUS_UPDATED,
                RealtimeEventType.VEHICLE_LOCATION_UPDATED,
                RealtimeEventType.VEHICLE_PATH_UPDATED,
                RealtimeEventType.VEHICLE_COMMAND_RESULT_UPDATED,
                RealtimeEventType.STATION_MEASUREMENT_COMPLETED);
    }
}
