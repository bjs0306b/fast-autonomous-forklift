package com.fast.backend.vehicle.websocket;

import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * "WebSocket 실패는 로그만 남기고 DB 갱신 결과를 유지한다"(prompt16.md 15장, prompt20.md 18장 조건)를
 * 직접 검증한다. VehicleStatusService/ForkliftLocationService는 이 클래스를 호출만 할 뿐 예외 처리를
 * 하지 않으므로, 예외를 실제로 삼키는 책임이 이 클래스에 있다는 것을 여기서 확정한다(prompt20.md 14장
 * "Broadcaster 단위 테스트").
 */
class VehicleWebSocketBroadcasterTest {

    @Test
    void broadcastStatus_sendsEnvelopeToAllAndVehicleSpecificTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        VehicleStatusResponse data = new VehicleStatusResponse(
                VehicleStatus.ACTIVE, 82, 1.2, 3.4, 90.0, 0.4, LocalDateTime.now(), LocalDateTime.now());
        LocalDateTime occurredAt = LocalDateTime.now();

        broadcaster.broadcastStatus("SIM-F01", data, occurredAt);

        VehicleWebSocketEvent<VehicleStatusResponse> expected = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_STATUS_UPDATED, "SIM-F01", occurredAt, data);
        verify(template).convertAndSend("/topic/vehicles/status", expected);
        verify(template).convertAndSend("/topic/vehicles/status/SIM-F01", expected);
    }

    @Test
    void broadcastLocation_sendsEnvelopeToAllAndVehicleSpecificTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        VehicleLocationEventData data = new VehicleLocationEventData(
                "SIM-F01", VehicleStatus.ACTIVE,
                new VehicleLocationEventData.Position(2.5, 4.1, "map"), 90.0,
                new VehicleLocationEventData.Quaternion(0.0, 0.0, 0.7071, 0.7071),
                0.4, LocalDateTime.now(), LocalDateTime.now());
        LocalDateTime occurredAt = LocalDateTime.now();

        broadcaster.broadcastLocation("SIM-F01", data, occurredAt);

        VehicleWebSocketEvent<VehicleLocationEventData> expected = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_LOCATION_UPDATED, "SIM-F01", occurredAt, data);
        verify(template).convertAndSend("/topic/vehicles/location", expected);
        verify(template).convertAndSend("/topic/vehicles/location/SIM-F01", expected);
    }

    @Test
    void broadcastCommandResult_sendsEnvelopeToAllAndVehicleSpecificTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        VehicleCommandResultEventData data = new VehicleCommandResultEventData(
                "CMD-20260721-0001", "SIM-F01", "MOVE", "SUCCEEDED", "Destination reached", LocalDateTime.now());
        LocalDateTime occurredAt = LocalDateTime.now();

        broadcaster.broadcastCommandResult("SIM-F01", data, occurredAt);

        VehicleWebSocketEvent<VehicleCommandResultEventData> expected = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_COMMAND_RESULT_UPDATED, "SIM-F01", occurredAt, data);
        verify(template).convertAndSend("/topic/vehicles/result", expected);
        verify(template).convertAndSend("/topic/vehicles/result/SIM-F01", expected);
    }

    @Test
    void broadcastStatus_messagingTemplateThrows_exceptionDoesNotPropagate() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new RuntimeException("boom")).when(template).convertAndSend(anyString(), any(Object.class));
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        VehicleStatusResponse data = VehicleStatusResponse.unknown();

        assertThatCode(() -> broadcaster.broadcastStatus("SIM-F01", data, LocalDateTime.now()))
                .doesNotThrowAnyException();
        verify(template, times(1)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void broadcastLocation_messagingTemplateThrows_exceptionDoesNotPropagate() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new RuntimeException("boom")).when(template).convertAndSend(anyString(), any(Object.class));
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        VehicleLocationEventData data = new VehicleLocationEventData(
                "SIM-F01", VehicleStatus.UNKNOWN,
                new VehicleLocationEventData.Position(1.0, 1.0, "map"), 0.0, null,
                0.0, LocalDateTime.now(), LocalDateTime.now());

        assertThatCode(() -> broadcaster.broadcastLocation("SIM-F01", data, LocalDateTime.now()))
                .doesNotThrowAnyException();
    }

    @Test
    void broadcastIsaacLocation_sendsToExistingLocationDestinations() {
        // prompt28.md 3장 "기존 위치 WebSocket destination이 있다면 우선 재사용" — 기존 ROS2 위치와
        // 같은 /topic/vehicles/location 계열 destination을 그대로 쓴다.
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        IsaacVehicleLocationEventData data = new IsaacVehicleLocationEventData(
                "SIM01", 1.234, 0.872, 1.5708, 0.15, LocalDateTime.now(), LocalDateTime.now());
        LocalDateTime occurredAt = LocalDateTime.now();

        broadcaster.broadcastIsaacLocation("SIM01", data, occurredAt);

        VehicleWebSocketEvent<IsaacVehicleLocationEventData> expected = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_LOCATION_UPDATED, "SIM01", occurredAt, data);
        verify(template).convertAndSend("/topic/vehicles/location", expected);
        verify(template).convertAndSend("/topic/vehicles/location/SIM01", expected);
    }

    @Test
    void broadcastIsaacStatus_sendsToExistingStatusDestinations() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        IsaacVehicleStatusEventData data = new IsaacVehicleStatusEventData(
                "SIM01", "MOVING", 87, 0.12, true, "BOX-0042",
                new IsaacVehicleStatusEventData.Footprint(0.28, 0.16), LocalDateTime.now(), LocalDateTime.now());
        LocalDateTime occurredAt = LocalDateTime.now();

        broadcaster.broadcastIsaacStatus("SIM01", data, occurredAt);

        VehicleWebSocketEvent<IsaacVehicleStatusEventData> expected = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_STATUS_UPDATED, "SIM01", occurredAt, data);
        verify(template).convertAndSend("/topic/vehicles/status", expected);
        verify(template).convertAndSend("/topic/vehicles/status/SIM01", expected);
    }

    @Test
    void broadcastPath_sendsToNewPathDestinations() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        VehiclePathEventData data = new VehiclePathEventData(
                "SIM01",
                java.util.List.of(new VehiclePathEventData.Waypoint(1.2, 0.87)),
                new VehiclePathEventData.Goal(2.4, 3.1, 0.0),
                LocalDateTime.now(), LocalDateTime.now());
        LocalDateTime occurredAt = LocalDateTime.now();

        broadcaster.broadcastPath("SIM01", data, occurredAt);

        VehicleWebSocketEvent<VehiclePathEventData> expected = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_PATH_UPDATED, "SIM01", occurredAt, data);
        verify(template).convertAndSend("/topic/vehicles/path", expected);
        verify(template).convertAndSend("/topic/vehicles/path/SIM01", expected);
    }

    @Test
    void broadcastPath_messagingTemplateThrows_exceptionDoesNotPropagate() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new RuntimeException("boom")).when(template).convertAndSend(anyString(), any(Object.class));
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        VehiclePathEventData data = new VehiclePathEventData(
                "SIM01", java.util.List.of(), new VehiclePathEventData.Goal(1.0, 1.0, 0.0),
                LocalDateTime.now(), LocalDateTime.now());

        assertThatCode(() -> broadcaster.broadcastPath("SIM01", data, LocalDateTime.now()))
                .doesNotThrowAnyException();
    }

    @Test
    void broadcastEmbeddedCommandResult_sendsToExistingResultDestinations() {
        // prompt29.md 19장 "기존 결과 destination·eventType을 그대로 재사용" — /topic/vehicles/result 재사용.
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        EmbeddedCommandResultEventData data = new EmbeddedCommandResultEventData(
                "CMD-001", "REAL01", "FORK_UP", "SUCCESS", "STOPPED", false, null, null, null,
                null, null, LocalDateTime.now());
        LocalDateTime occurredAt = LocalDateTime.now();

        broadcaster.broadcastEmbeddedCommandResult("REAL01", data, occurredAt);

        VehicleWebSocketEvent<EmbeddedCommandResultEventData> expected = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_COMMAND_RESULT_UPDATED, "REAL01", occurredAt, data);
        verify(template).convertAndSend("/topic/vehicles/result", expected);
        verify(template).convertAndSend("/topic/vehicles/result/REAL01", expected);
    }

    @Test
    void broadcastForkStatus_sendsToNewForkStatusDestinations() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        EmbeddedForkStatusEventData data = new EmbeddedForkStatusEventData(
                "REAL01", "STOPPED", false, null, LocalDateTime.now(), LocalDateTime.now());
        LocalDateTime occurredAt = LocalDateTime.now();

        broadcaster.broadcastForkStatus("REAL01", data, occurredAt);

        VehicleWebSocketEvent<EmbeddedForkStatusEventData> expected = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_FORK_STATUS_UPDATED, "REAL01", occurredAt, data);
        verify(template).convertAndSend("/topic/vehicles/fork-status", expected);
        verify(template).convertAndSend("/topic/vehicles/fork-status/REAL01", expected);
    }

    @Test
    void broadcastForkStatus_messagingTemplateThrows_exceptionDoesNotPropagate() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new RuntimeException("boom")).when(template).convertAndSend(anyString(), any(Object.class));
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        EmbeddedForkStatusEventData data = new EmbeddedForkStatusEventData(
                "REAL01", "STOPPED", false, null, LocalDateTime.now(), LocalDateTime.now());

        assertThatCode(() -> broadcaster.broadcastForkStatus("REAL01", data, LocalDateTime.now()))
                .doesNotThrowAnyException();
    }

    @Test
    void broadcastEmbeddedError_sendsToNewErrorDestinations() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        EmbeddedErrorEventData data = new EmbeddedErrorEventData(
                "REAL01", "E001", "DRIVE", "WARNING", "msg", LocalDateTime.now(), LocalDateTime.now());
        LocalDateTime occurredAt = LocalDateTime.now();

        broadcaster.broadcastEmbeddedError("REAL01", data, occurredAt);

        VehicleWebSocketEvent<EmbeddedErrorEventData> expected = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_ERROR_OCCURRED, "REAL01", occurredAt, data);
        verify(template).convertAndSend("/topic/vehicles/errors", expected);
        verify(template).convertAndSend("/topic/vehicles/errors/REAL01", expected);
    }

    @Test
    void broadcastEmbeddedError_messagingTemplateThrows_exceptionDoesNotPropagate() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new RuntimeException("boom")).when(template).convertAndSend(anyString(), any(Object.class));
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);
        EmbeddedErrorEventData data = new EmbeddedErrorEventData(
                "REAL01", "E001", "DRIVE", "WARNING", "msg", LocalDateTime.now(), LocalDateTime.now());

        assertThatCode(() -> broadcaster.broadcastEmbeddedError("REAL01", data, LocalDateTime.now()))
                .doesNotThrowAnyException();
    }
}
