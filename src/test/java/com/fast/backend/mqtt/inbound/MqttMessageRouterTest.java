package com.fast.backend.mqtt.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.command.service.VehicleCommandResultService;
import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.forklift.service.ForkliftStatusService;
import com.fast.backend.isaac.service.IsaacForkliftPathService;
import com.fast.backend.isaac.dto.IsaacVehicleTelemetryMessage;
import com.fast.backend.isaac.service.IsaacVehicleTelemetryService;
import com.fast.backend.isaac.service.IsaacVehicleEventService;
import com.fast.backend.station.service.StationArrivalService;
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
    private IsaacVehicleTelemetryService isaacTelemetryService;
    private IsaacVehicleEventService isaacEventService;
    private StationArrivalService stationArrivalService;
    private IsaacForkliftPathService pathService;
    private VehicleCommandResultService commandResultService;
    private MqttMessageRouter router;

    @BeforeEach
    void setUp() {
        statusService = mock(ForkliftStatusService.class);
        locationService = mock(ForkliftLocationService.class);
        pathService = mock(IsaacForkliftPathService.class);
        commandResultService = mock(VehicleCommandResultService.class);
        isaacTelemetryService = mock(IsaacVehicleTelemetryService.class);
        isaacEventService = mock(IsaacVehicleEventService.class);
        stationArrivalService = mock(StationArrivalService.class);
        MqttProperties properties = new MqttProperties(
                "tcp://localhost:1883", null, null, null, false, "in", "out",
                10, 30, true, true, 1, 5000, 5000,
                new MqttProperties.Topics(
                        "forklift/+/status", "forklift/+/location", "forklift/+/path",
                        "forklift/+/command-result", "fast/station/measure_request",
                        "forklift/%s/command", "fast/v1/vehicle/+/telemetry",
                        "fast/v1/vehicle/+/event", "forklift/+/arrived"));
        router = new MqttMessageRouter(
                new ObjectMapper().findAndRegisterModules(), new MqttTopics(properties),
                statusService, locationService, new VehicleLocationMessageAdapter(),
                pathService, commandResultService, isaacTelemetryService,
                isaacEventService, stationArrivalService);
    }

    /** payload 의 battery 는 백엔드가 쓰지 않는 구형 필드다. 남겨 두어 구형 송신자 호환도 함께 확인한다. */
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

    /** Isaac telemetry 는 새 토픽으로 들어오며 기존 forklift 경로를 건드리지 않는다. */
    @Test
    void isaacTelemetry_isRoutedToTheTelemetryService() {
        router.route("fast/v1/vehicle/sim01/telemetry", """
                {"vehicleId":"sim01","ts":1785946947471,
                 "pose":{"x":1.55,"y":0.4,"yaw":0.0},
                 "velocity":{"linear":0.0,"angular":0.0},
                 "forkHeight":0.0,"loaded":false,"cargoId":null,
                 "state":"IDLE","taskId":null,"battery":100.0}
                """);

        ArgumentCaptor<IsaacVehicleTelemetryMessage> captor =
                ArgumentCaptor.forClass(IsaacVehicleTelemetryMessage.class);
        verify(isaacTelemetryService)
                .handleTelemetry(org.mockito.ArgumentMatchers.eq("sim01"), captor.capture());

        IsaacVehicleTelemetryMessage message = captor.getValue();
        assertThat(message.vehicleId()).isEqualTo("sim01");
        assertThat(message.ts()).isEqualTo(1785946947471L);
        assertThat(message.pose().x()).isEqualTo(1.55);
        assertThat(message.pose().y()).isEqualTo(0.4);
        assertThat(message.velocity().linear()).isEqualTo(0.0);
        assertThat(message.state()).isEqualTo("IDLE");
        // 기존 위치 경로는 이 메시지를 보지 않는다.
        verify(locationService, never()).handleLocation(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isaacTelemetry_unknownTopicIsStillRejected() {
        router.route("fast/v1/vehicle/sim01/unknown", """
                {"vehicleId":"sim01","pose":{"x":1.0,"y":2.0,"yaw":0.0}}
                """);

        verify(isaacTelemetryService, never())
                .handleTelemetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isaacEvent_isRoutedToEventService() {
        router.route("fast/v1/vehicle/sim03/event", """
                {"vehicleId":"sim03","ts":1785946947471,"state":"HOLDING"}
                """);

        verify(isaacEventService).handleEvent(
                org.mockito.ArgumentMatchers.eq("sim03"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blankArrivalPayload_isRoutedUsingTopicVehicleId() {
        router.route("forklift/sim02/arrived", "");

        verify(stationArrivalService).handleArrival(
                org.mockito.ArgumentMatchers.eq("sim02"), org.mockito.ArgumentMatchers.any());
    }
}
