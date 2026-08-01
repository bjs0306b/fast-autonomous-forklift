package com.fast.backend.mqtt.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.command.service.VehicleCommandResultService;
import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.forklift.service.ForkliftStatusService;
import com.fast.backend.isaac.service.IsaacForkliftPathService;
import com.fast.backend.station.dto.StationMeasurementCreateRequest;
import com.fast.backend.station.service.StationMeasurementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MqttMessageRouterTest {

    private ForkliftStatusService statusService;
    private ForkliftLocationService locationService;
    private IsaacForkliftPathService pathService;
    private VehicleCommandResultService commandResultService;
    private StationMeasurementService measurementService;
    private MqttMessageRouter router;

    @BeforeEach
    void setUp() {
        statusService = mock(ForkliftStatusService.class);
        locationService = mock(ForkliftLocationService.class);
        pathService = mock(IsaacForkliftPathService.class);
        commandResultService = mock(VehicleCommandResultService.class);
        measurementService = mock(StationMeasurementService.class);
        MqttProperties properties = new MqttProperties(
                "tcp://localhost:1883", null, null, "in", "out",
                10, 30, true, true, 1, 5000, 5000,
                new MqttProperties.Topics(
                        "forklift/+/status", "forklift/+/location", "forklift/+/path",
                        "forklift/+/command-result", "fast/station/measurement",
                        "forklift/%s/command"));
        router = new MqttMessageRouter(
                new ObjectMapper().findAndRegisterModules(), new MqttTopics(properties),
                statusService, locationService, pathService, commandResultService, measurementService);
    }

    @Test
    void stationMeasurement_routesCurrentContract() {
        String payload = """
                {
                  "sessionId":"SESSION-1",
                  "measurementId":"MEASUREMENT-1",
                  "status":"ok",
                  "cargoHeight":0.52,
                  "tippingLevel":"safe",
                  "overhangRatio":0.02
                }
                """;

        router.route("fast/station/measurement", payload);

        ArgumentCaptor<StationMeasurementCreateRequest> captor =
                ArgumentCaptor.forClass(StationMeasurementCreateRequest.class);
        verify(measurementService).create(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo("SESSION-1");
        assertThat(captor.getValue().measurementId()).isEqualTo("MEASUREMENT-1");
        assertThat(captor.getValue().cargoHeight()).isEqualTo(0.52);
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
        verify(measurementService, never()).create(org.mockito.ArgumentMatchers.any());
    }
}
