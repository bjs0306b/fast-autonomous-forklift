package com.fast.backend.forklift.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ROS2 위치 메시지 JSON(prompt24.md 1장, prompt25.md 2장 최종 규격)이 {@link ForkliftLocationMessage}로
 * 정확히 역직렬화되는지 검증한다. Spring 컨텍스트 없이 {@code MqttMessageRouter}가 실제로 사용하는 것과
 * 동일한 방식(JavaTimeModule 등록)으로 ObjectMapper를 구성한다(MqttMessageRouterTest와 동일 패턴).
 */
class ForkliftLocationMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void deserialize_fullPayload_mapsAllFields() throws Exception {
        String json = "{"
                + "\"vehicleId\":\"FORKLIFT-01\","
                + "\"status\":\"MOVING\","
                + "\"position\":{\"x\":2.5,\"y\":4.1,\"frameId\":\"map\"},"
                + "\"heading\":90.0,"
                + "\"quaternion\":{\"x\":0.0,\"y\":0.0,\"z\":0.7071,\"w\":0.7071},"
                + "\"speed\":0.4,"
                + "\"messageAt\":\"2026-07-22T13:30:00+09:00\""
                + "}";

        ForkliftLocationMessage message = objectMapper.readValue(json, ForkliftLocationMessage.class);

        assertThat(message.vehicleId()).isEqualTo("FORKLIFT-01");
        assertThat(message.status()).isEqualTo("MOVING");
        assertThat(message.position().x()).isEqualTo(2.5);
        assertThat(message.position().y()).isEqualTo(4.1);
        assertThat(message.position().frameId()).isEqualTo("map");
        assertThat(message.heading()).isEqualTo(90.0);
        assertThat(message.quaternion().x()).isEqualTo(0.0);
        assertThat(message.quaternion().y()).isEqualTo(0.0);
        assertThat(message.quaternion().z()).isEqualTo(0.7071);
        assertThat(message.quaternion().w()).isEqualTo(0.7071);
        assertThat(message.speed()).isEqualTo(0.4);
        assertThat(message.messageAt()).isEqualTo(LocalDateTime.of(2026, 7, 22, 13, 30, 0).atOffset(java.time.ZoneOffset.ofHours(9)));
    }

    @Test
    void deserialize_nestedPosition_mapsIndependentlyOfOtherFields() throws Exception {
        String json = "{\"vehicleId\":\"FORKLIFT-01\",\"position\":{\"x\":10.0,\"y\":-5.5,\"frameId\":\"odom\"},"
                + "\"messageAt\":\"2026-07-22T13:30:00+09:00\"}";

        ForkliftLocationMessage message = objectMapper.readValue(json, ForkliftLocationMessage.class);

        assertThat(message.position()).isNotNull();
        assertThat(message.position().x()).isEqualTo(10.0);
        assertThat(message.position().y()).isEqualTo(-5.5);
        assertThat(message.position().frameId()).isEqualTo("odom");
    }

    @Test
    void deserialize_nestedQuaternion_mapsIndependentlyOfOtherFields() throws Exception {
        String json = "{\"vehicleId\":\"FORKLIFT-01\",\"position\":{\"x\":1.0,\"y\":1.0},"
                + "\"quaternion\":{\"x\":0.1,\"y\":0.2,\"z\":0.3,\"w\":0.9},"
                + "\"messageAt\":\"2026-07-22T13:30:00+09:00\"}";

        ForkliftLocationMessage message = objectMapper.readValue(json, ForkliftLocationMessage.class);

        assertThat(message.quaternion()).isNotNull();
        assertThat(message.quaternion().x()).isEqualTo(0.1);
        assertThat(message.quaternion().y()).isEqualTo(0.2);
        assertThat(message.quaternion().z()).isEqualTo(0.3);
        assertThat(message.quaternion().w()).isEqualTo(0.9);
    }

    @Test
    void deserialize_isoMessageAt_parsesToLocalDateTime() throws Exception {
        String json = "{\"vehicleId\":\"FORKLIFT-01\",\"position\":{\"x\":1.0,\"y\":1.0},"
                + "\"messageAt\":\"2026-01-05T08:15:30+09:00\"}";

        ForkliftLocationMessage message = objectMapper.readValue(json, ForkliftLocationMessage.class);

        assertThat(message.messageAt()).isEqualTo(LocalDateTime.of(2026, 1, 5, 8, 15, 30).atOffset(java.time.ZoneOffset.ofHours(9)));
    }

    @Test
    void deserialize_messageAtWithMilliseconds_parsesToLocalDateTime() throws Exception {
        // prompt25.md 1.2장·2장 최종 규격 예시: 밀리초 포함 messageAt.
        String json = "{\"vehicleId\":\"FORKLIFT-01\",\"position\":{\"x\":1.0,\"y\":1.0},"
                + "\"messageAt\":\"2026-07-22T13:30:00.123+09:00\"}";

        ForkliftLocationMessage message = objectMapper.readValue(json, ForkliftLocationMessage.class);

        assertThat(message.messageAt()).isEqualTo(LocalDateTime.of(2026, 7, 22, 13, 30, 0, 123_000_000).atOffset(java.time.ZoneOffset.ofHours(9)));
    }

    @Test
    void deserialize_activeStatus_mapsAsIs() throws Exception {
        String json = "{\"vehicleId\":\"FORKLIFT-01\",\"status\":\"ACTIVE\","
                + "\"position\":{\"x\":1.0,\"y\":1.0},\"messageAt\":\"2026-07-22T13:30:00+09:00\"}";

        ForkliftLocationMessage message = objectMapper.readValue(json, ForkliftLocationMessage.class);

        assertThat(message.status()).isEqualTo("ACTIVE");
    }

    @Test
    void deserialize_invalidMessageAtFormat_throwsJsonProcessingException() {
        // prompt25.md 5장 "파싱 오류 처리" — JSON 역직렬화 자체가 실패해야 MqttMessageRouter가 해당
        // 메시지를 폐기하고 Service를 호출하지 않을 수 있다(MqttMessageRouterTest에서 라우팅 레벨로도 검증).
        String json = "{\"vehicleId\":\"FORKLIFT-01\",\"position\":{\"x\":1.0,\"y\":1.0},"
                + "\"messageAt\":\"not-a-valid-timestamp\"}";

        assertThatThrownBy(() -> objectMapper.readValue(json, ForkliftLocationMessage.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void deserialize_missingOptionalFields_leavesThemNull() throws Exception {
        String json = "{\"vehicleId\":\"FORKLIFT-01\",\"position\":{\"x\":1.0,\"y\":1.0},"
                + "\"messageAt\":\"2026-07-22T13:30:00+09:00\"}";

        ForkliftLocationMessage message = objectMapper.readValue(json, ForkliftLocationMessage.class);

        assertThat(message.status()).isNull();
        assertThat(message.position().frameId()).isNull();
        assertThat(message.heading()).isNull();
        assertThat(message.quaternion()).isNull();
        assertThat(message.speed()).isNull();
    }
}
