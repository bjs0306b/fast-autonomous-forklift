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

/**
 * 확정 WebSocket 공통 envelope(prompt32.md 1장 13번)의 JSON 모양을 고정한다.
 * 프론트는 도메인과 무관하게 {@code eventType}/{@code vehicleId}/{@code occurredAt}/{@code data}
 * 네 키만 알면 되어야 한다.
 */
class RealtimeEventTest {

    private static final OffsetDateTime OCCURRED_AT =
            OffsetDateTime.of(2026, 7, 23, 11, 20, 27, 0, ZoneOffset.ofHours(9));

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 실제 앱과 동일하게 null 필드를 생략하지 않는다(application.yml default-property-inclusion: always).
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void serialize_containsExactlyTheFourEnvelopeKeys() throws Exception {
        RealtimeEvent<Map<String, Object>> event = RealtimeEvent.of(
                RealtimeEventType.VEHICLE_STATUS_UPDATED, "REAL-F01", OCCURRED_AT, Map.of("status", "MOVING"));

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(event));

        assertThat(node.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("eventType", "vehicleId", "occurredAt", "data");
    }

    @Test
    void serialize_eventTypeAndOccurredAtAndDataAreAlwaysPresent() throws Exception {
        RealtimeEvent<Map<String, Object>> event = RealtimeEvent.of(
                RealtimeEventType.VEHICLE_COMMAND_RESULT_UPDATED, "REAL-F01", OCCURRED_AT, Map.of("result", "SUCCESS"));

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(event));

        assertThat(node.get("eventType").asText()).isEqualTo("VEHICLE_COMMAND_RESULT_UPDATED");
        assertThat(node.get("occurredAt").asText()).isEqualTo("2026-07-23T11:20:27+09:00");
        assertThat(node.get("data").isObject()).isTrue();
    }

    @Test
    void serialize_vehicleIdIsNullableForEventsWithoutAVehicle() throws Exception {
        // 차량과 연결되지 않은 AI 분석, 차량이 배정되지 않은 스테이션 측정은 vehicleId가 null이다.
        RealtimeEvent<Map<String, Object>> event = RealtimeEvent.of(
                RealtimeEventType.STATION_MEASUREMENT_COMPLETED, null, OCCURRED_AT, Map.of("stationId", "station-1"));

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(event));

        assertThat(node.has("vehicleId")).isTrue();
        assertThat(node.get("vehicleId").isNull()).isTrue();
        // 도메인 고유 식별자는 최상위로 올라오지 않고 data 안에 남는다.
        assertThat(node.get("data").get("stationId").asText()).isEqualTo("station-1");
    }

    @Test
    void occurredAt_alwaysKeepsTheSeoulOffset() throws Exception {
        RealtimeEvent<String> event = RealtimeEvent.of(
                RealtimeEventType.VEHICLE_ERROR_OCCURRED, "REAL-F01", OCCURRED_AT, "x");

        assertThat(objectMapper.writeValueAsString(event)).contains("\"occurredAt\":\"2026-07-23T11:20:27+09:00\"");
    }

    @Test
    void eventType_coversEveryConfirmedDomainEvent() {
        assertThat(RealtimeEventType.values()).containsExactlyInAnyOrder(
                RealtimeEventType.VEHICLE_STATUS_UPDATED,
                RealtimeEventType.VEHICLE_LOCATION_UPDATED,
                RealtimeEventType.VEHICLE_PATH_UPDATED,
                RealtimeEventType.VEHICLE_COMMAND_RESULT_UPDATED,
                RealtimeEventType.VEHICLE_FORK_STATUS_UPDATED,
                RealtimeEventType.VEHICLE_ERROR_OCCURRED,
                RealtimeEventType.AI_CARGO_ANALYSIS_COMPLETED,
                RealtimeEventType.STATION_MEASUREMENT_COMPLETED,
                // prompt63.md 3장 3번으로 추가된 적재 화물 안전 이벤트.
                RealtimeEventType.VEHICLE_LOAD_SAFETY_UPDATED);
    }
}
