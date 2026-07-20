package com.fast.backend.mqtt.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.forklift.service.ForkliftStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 실제 MQTT Broker 없이 순수 라우팅/역직렬화 로직만 검증하는 단위 테스트.
 */
class MqttMessageRouterTest {

    private MqttMessageRouter router;
    private ForkliftStatusService forkliftStatusService;
    private ForkliftLocationService forkliftLocationService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MqttProperties.Topics topics = new MqttProperties.Topics(
                "forklift/+/status", "forklift/+/location", "cargo/detected",
                "forklift/%s/command", "forklift/%s/emergency");
        MqttProperties properties = new MqttProperties(
                "tcp://localhost:1883", null, null,
                "fast-backend-inbound", "fast-backend-outbound",
                10, 30, true, true, 1, 5000L, 5000L, topics);
        MqttTopics mqttTopics = new MqttTopics(properties);

        forkliftStatusService = mock(ForkliftStatusService.class);
        forkliftLocationService = mock(ForkliftLocationService.class);
        router = new MqttMessageRouter(objectMapper, mqttTopics, forkliftStatusService, forkliftLocationService);
    }

    @Test
    void route_statusTopic_convertsToForkliftStatusMessage() {
        String payload = "{\"forkliftId\":\"F01\",\"status\":\"MOVING\",\"battery\":82,"
                + "\"timestamp\":\"2026-07-20T09:20:00\"}";

        router.route("forklift/F01/status", payload);

        verify(forkliftStatusService, times(1)).handleStatus(any());
    }

    @Test
    void route_locationTopic_convertsToForkliftLocationMessage() {
        String payload = "{\"forkliftId\":\"F01\",\"x\":120.5,\"y\":84.2,\"direction\":90.0,\"speed\":1.2,"
                + "\"timestamp\":\"2026-07-20T09:20:00\"}";

        router.route("forklift/F01/location", payload);

        verify(forkliftLocationService, times(1)).handleLocation(any());
        verifyNoInteractions(forkliftStatusService);
    }

    @Test
    void route_invalidJson_doesNotThrow() {
        router.route("forklift/F01/status", "not-a-json");

        verifyNoInteractions(forkliftStatusService);
        verifyNoInteractions(forkliftLocationService);
    }

    @Test
    void route_unknownTopic_doesNotThrow() {
        router.route("unknown/topic", "payload");

        verifyNoInteractions(forkliftStatusService);
        verifyNoInteractions(forkliftLocationService);
    }

    @Test
    void route_cargoDetectedTopic_doesNotThrow() {
        router.route("cargo/detected", "{\"any\":\"payload\"}");

        verifyNoInteractions(forkliftStatusService);
        verifyNoInteractions(forkliftLocationService);
    }
}
