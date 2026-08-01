package com.fast.backend.mqtt.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.command.service.VehicleCommandResultService;
import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.forklift.service.ForkliftStatusService;
import com.fast.backend.isaac.service.IsaacForkliftPathService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
                statusService, locationService, pathService, commandResultService);
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
}
