package com.fast.backend.vehicle.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fast.backend.common.websocket.RealtimeEvent;
import com.fast.backend.common.websocket.RealtimeEventType;
import com.fast.backend.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프론트가 실제로 받는 {@code VEHICLE_LOCATION_UPDATED} 이벤트 JSON의 키가 prompt24.md 7장 규격과
 * 일치하는지, null 선택 필드가 생략되지 않고 명시적으로 내려가는지(application.yml의
 * {@code spring.jackson.default-property-inclusion: always}) 검증한다. 실제 앱과 동일하게
 * JavaTimeModule 등록 + WRITE_DATES_AS_TIMESTAMPS 비활성화(Spring Boot의 Jackson2ObjectMapperBuilder
 * 기본값과 동일, 그렇지 않으면 LocalDateTime이 ISO 문자열이 아닌 숫자 배열로 직렬화된다)로 ObjectMapper를
 * 직접 구성해 Spring 컨텍스트 없이 빠르게 검증한다.
 */
class VehicleLocationEventDataTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void serialize_fullData_containsAllAgreedKeys() throws Exception {
        VehicleLocationEventData data = new VehicleLocationEventData(
                "FORKLIFT-01",
                VehicleStatus.ACTIVE,
                new VehicleLocationEventData.Position(2.5, 4.1, "map"),
                90.0,
                new VehicleLocationEventData.Quaternion(0.0, 0.0, 0.7071, 0.7071),
                0.4,
                LocalDateTime.of(2026, 7, 22, 13, 30, 0).atOffset(java.time.ZoneOffset.ofHours(9)),
                LocalDateTime.of(2026, 7, 22, 13, 30, 0, 120_000_000).atOffset(java.time.ZoneOffset.ofHours(9)));

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(data));

        assertThat(node.get("vehicleId").asText()).isEqualTo("FORKLIFT-01");
        assertThat(node.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(node.get("position").get("x").asDouble()).isEqualTo(2.5);
        assertThat(node.get("position").get("y").asDouble()).isEqualTo(4.1);
        assertThat(node.get("position").get("frameId").asText()).isEqualTo("map");
        assertThat(node.get("heading").asDouble()).isEqualTo(90.0);
        assertThat(node.get("quaternion").get("x").asDouble()).isEqualTo(0.0);
        assertThat(node.get("quaternion").get("w").asDouble()).isEqualTo(0.7071);
        assertThat(node.get("speed").asDouble()).isEqualTo(0.4);
        assertThat(node.get("messageAt").asText()).startsWith("2026-07-22T13:30:00");
        assertThat(node.get("receivedAt").asText()).startsWith("2026-07-22T13:30:00");
    }

    @Test
    void serialize_nullOptionalFields_areSerializedExplicitlyAsNull() throws Exception {
        VehicleLocationEventData data = new VehicleLocationEventData(
                "FORKLIFT-01",
                VehicleStatus.UNKNOWN,
                new VehicleLocationEventData.Position(2.5, 4.1, "map"),
                null,
                null,
                null,
                LocalDateTime.of(2026, 7, 22, 13, 30, 0).atOffset(java.time.ZoneOffset.ofHours(9)),
                LocalDateTime.of(2026, 7, 22, 13, 30, 0).atOffset(java.time.ZoneOffset.ofHours(9)));

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(data));

        // default-property-inclusion=always 정책 — null이어도 키 자체는 생략되지 않고 남아야 한다.
        assertThat(node.has("heading")).isTrue();
        assertThat(node.get("heading").isNull()).isTrue();
        assertThat(node.has("quaternion")).isTrue();
        assertThat(node.get("quaternion").isNull()).isTrue();
        assertThat(node.has("speed")).isTrue();
        assertThat(node.get("speed").isNull()).isTrue();
    }

    @Test
    void serialize_envelope_wrapsDataUnderExpectedEventType() throws Exception {
        VehicleLocationEventData data = new VehicleLocationEventData(
                "FORKLIFT-01", VehicleStatus.ACTIVE,
                new VehicleLocationEventData.Position(1.0, 1.0, "map"), 0.0, null, 0.0,
                java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)), java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)));
        RealtimeEvent<VehicleLocationEventData> event = RealtimeEvent.of(
                RealtimeEventType.VEHICLE_LOCATION_UPDATED, "FORKLIFT-01", java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)), data);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(event));

        assertThat(node.get("eventType").asText()).isEqualTo("VEHICLE_LOCATION_UPDATED");
        assertThat(node.get("vehicleId").asText()).isEqualTo("FORKLIFT-01");
        assertThat(node.get("data").get("vehicleId").asText()).isEqualTo("FORKLIFT-01");
        assertThat(node.get("data").get("position").get("x").asDouble()).isEqualTo(1.0);
    }
}
