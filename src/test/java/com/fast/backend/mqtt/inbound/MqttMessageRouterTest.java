package com.fast.backend.mqtt.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.command.service.VehicleCommandResultService;
import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.forklift.service.ForkliftStatusService;
import com.fast.backend.isaac.service.IsaacForkliftPathService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MqttMessageRouterTest {

    private ForkliftStatusService statusService;
    private ForkliftLocationService locationService;
    private IsaacForkliftPathService pathService;
    private VehicleCommandResultService commandResultService;
    private MqttMessageRouter router;

    @BeforeEach
    void setUp() {
        statusService = mock(ForkliftStatusService.class);
        locationService = mock(ForkliftLocationService.class);
        pathService = mock(IsaacForkliftPathService.class);
        commandResultService = mock(VehicleCommandResultService.class);
        MqttProperties properties = new MqttProperties(
                "tcp://localhost:1883", null, null, "in", "out",
                10, 30, true, true, 1, 5000, 5000,
                new MqttProperties.Topics(
                        "forklift/+/status", "forklift/+/location", "forklift/+/path",
                        "forklift/+/command-result", "fast/station/measure_request",
                        "forklift/%s/command"));
        router = new MqttMessageRouter(
                new ObjectMapper().findAndRegisterModules(), new MqttTopics(properties),
                statusService, locationService, new VehicleLocationMessageAdapter(),
                pathService, commandResultService);
    }

    @Test
    void vehicleIdMismatch_doesNotReachService() {
        router.route("forklift/FORKLIFT-1/status", """
                {"forkliftId":"FORKLIFT-2","status":"IDLE","battery":90,
                 "timestamp":"2026-08-01T10:00:00+09:00"}
                """);

        verify(statusService, never()).handleStatus(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void obsoleteTopic_isIgnored() {
        router.route("forklift/FORKLIFT-1/fork-status", "{\"forkState\":\"STOPPED\"}");

        verify(statusService, never()).handleStatus(org.mockito.ArgumentMatchers.any());
        verify(locationService, never()).handleLocation(org.mockito.ArgumentMatchers.any());
        verify(commandResultService, never()).handleResult(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rosLocation_reachesServiceWithoutChangingCanonicalShape() {
        router.route("forklift/REAL-F01/location", """
                {"vehicleId":"REAL-F01","position":{"x":1.2,"y":3.4,"frameId":"map"},
                 "heading":90.0,"speed":0.2,"messageAt":"2026-08-03T15:00:00+09:00"}
                """);

        ArgumentCaptor<ForkliftLocationMessage> captor =
                ArgumentCaptor.forClass(ForkliftLocationMessage.class);
        verify(locationService).handleLocation(captor.capture());
        assertThat(captor.getValue().vehicleId()).isEqualTo("REAL-F01");
        assertThat(captor.getValue().position().frameId()).isEqualTo("map");
        assertThat(captor.getValue().heading()).isEqualTo(90.0);
    }

    @Test
    void legacyIsaacLocation_isConvertedToCanonicalShape() {
        router.route("forklift/SIM-F01/location", """
                {"forkliftId":"SIM-F01","x":1.2,"y":3.4,"direction":1.5707963267948966,
                 "speed":0.2,"timestamp":"2026-08-03T15:00:00+09:00"}
                """);

        ArgumentCaptor<ForkliftLocationMessage> captor =
                ArgumentCaptor.forClass(ForkliftLocationMessage.class);
        verify(locationService).handleLocation(captor.capture());
        ForkliftLocationMessage converted = captor.getValue();
        assertThat(converted.vehicleId()).isEqualTo("SIM-F01");
        assertThat(converted.position()).isEqualTo(new ForkliftLocationMessage.Position(1.2, 3.4, "map"));
        assertThat(converted.heading()).isCloseTo(90.0, within(0.0001));
        assertThat(converted.speed()).isEqualTo(0.2);
        assertThat(converted.messageAt().toInstant()).isEqualTo(Instant.parse("2026-08-03T06:00:00Z"));
    }

    @Test
    void legacyIsaacLocation_vehicleIdMismatchDoesNotReachService() {
        router.route("forklift/SIM-F01/location", """
                {"forkliftId":"SIM-F02","x":1.2,"y":3.4,"direction":0.0,
                 "timestamp":"2026-08-03T15:00:00+09:00"}
                """);

        verify(locationService, never()).handleLocation(org.mockito.ArgumentMatchers.any());
    }
}
