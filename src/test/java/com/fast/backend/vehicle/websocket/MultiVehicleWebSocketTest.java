package com.fast.backend.vehicle.websocket;

import com.fast.backend.transport.websocket.TransportTaskBroadcaster;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 다중 차량 WebSocket 토픽 분리 검증(prompt51.md 10장). 공통 토픽 + 해당 차량/작업 전용 토픽에만 전송되고,
 * 다른 차량/작업 전용 토픽으로는 절대 전송되지 않음을 Mock SimpMessagingTemplate 호출로 확인한다.
 */
class MultiVehicleWebSocketTest {

    private static final OffsetDateTime OCCURRED_AT =
            OffsetDateTime.of(2026, 7, 27, 15, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void vehicleStatus_goesToCommonAndOwnTopicOnly() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);

        broadcaster.broadcastStatus("REAL-F01", VehicleStatusResponse.unknown(), OCCURRED_AT);

        verify(template).convertAndSend(eq("/topic/vehicles/status"), any(Object.class));
        verify(template).convertAndSend(eq("/topic/vehicles/status/REAL-F01"), any(Object.class));
        verify(template, never()).convertAndSend(eq("/topic/vehicles/status/REAL-F02"), any(Object.class));
        verify(template, never()).convertAndSend(eq("/topic/vehicles/status/SIM-F01"), any(Object.class));
    }

    @Test
    void vehicleLocation_goesToCommonAndOwnTopicOnly() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);

        VehicleLocationEventData data = new VehicleLocationEventData(
                "SIM-F01", null, new VehicleLocationEventData.Position(10.0, 20.0, "map"),
                270.0, null, 0.5, OCCURRED_AT, OCCURRED_AT);
        broadcaster.broadcastLocation("SIM-F01", data, OCCURRED_AT);

        verify(template).convertAndSend(eq("/topic/vehicles/location"), any(Object.class));
        verify(template).convertAndSend(eq("/topic/vehicles/location/SIM-F01"), any(Object.class));
        verify(template, never()).convertAndSend(eq("/topic/vehicles/location/REAL-F01"), any(Object.class));
    }

    @Test
    void twoVehicles_doNotCrossOwnTopics() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);

        broadcaster.broadcastStatus("REAL-F01", VehicleStatusResponse.unknown(), OCCURRED_AT);
        broadcaster.broadcastStatus("REAL-F02", VehicleStatusResponse.unknown(), OCCURRED_AT);

        verify(template).convertAndSend(eq("/topic/vehicles/status/REAL-F01"), any(Object.class));
        verify(template).convertAndSend(eq("/topic/vehicles/status/REAL-F02"), any(Object.class));
        // 공통 토픽엔 각 이벤트가 한 번씩(총 2회)
        verify(template, org.mockito.Mockito.times(2))
                .convertAndSend(eq("/topic/vehicles/status"), any(Object.class));
    }

    @Test
    void taskEvent_goesToCommonAndOwnTaskTopicOnly() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        TransportTaskBroadcaster broadcaster = new TransportTaskBroadcaster(template);

        // 트랜잭션 밖이라 즉시 전송된다(afterCommit 등록 없이 send).
        broadcaster.broadcastAfterCommit("TASK_DISPATCHED", "TASK-001", "MOVING_TO_PICKUP", "REAL-F01");

        verify(template).convertAndSend(eq("/topic/transport-tasks"), any(Object.class));
        verify(template).convertAndSend(eq("/topic/transport-tasks/TASK-001"), any(Object.class));
        verify(template, never()).convertAndSend(eq("/topic/transport-tasks/TASK-002"), any(Object.class));
    }

    @Test
    void twoTasks_doNotCrossOwnTopics() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        TransportTaskBroadcaster broadcaster = new TransportTaskBroadcaster(template);

        broadcaster.broadcastAfterCommit("TASK_COMPLETED", "TASK-001", "COMPLETED", "REAL-F01");
        broadcaster.broadcastAfterCommit("TASK_FAILED", "TASK-002", "FAILED", "REAL-F02");

        verify(template).convertAndSend(eq("/topic/transport-tasks/TASK-001"), any(Object.class));
        verify(template).convertAndSend(eq("/topic/transport-tasks/TASK-002"), any(Object.class));
        verify(template, org.mockito.Mockito.times(2))
                .convertAndSend(eq("/topic/transport-tasks"), any(Object.class));
    }

    @Test
    void payloadVehicleIdMatchesDestination() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        VehicleWebSocketBroadcaster broadcaster = new VehicleWebSocketBroadcaster(template);

        broadcaster.broadcastStatus("REAL-F02", VehicleStatusResponse.unknown(), OCCURRED_AT);

        org.mockito.ArgumentCaptor<Object> payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSend(eq("/topic/vehicles/status/REAL-F02"), payload.capture());
        com.fast.backend.common.websocket.RealtimeEvent<?> event =
                (com.fast.backend.common.websocket.RealtimeEvent<?>) payload.getValue();
        org.assertj.core.api.Assertions.assertThat(event.vehicleId()).isEqualTo("REAL-F02");
    }
}
