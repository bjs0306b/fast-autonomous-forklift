package com.fast.backend.command.websocket;

import com.fast.backend.command.dto.VehicleCommandResponse;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 안전 명령 WebSocket 토픽 분리 검증(prompt53.md 13장). 발행 이벤트는 명령 토픽(/topic/vehicles/commands)과
 * 해당 차량 전용 토픽에만, 요약은 /topic/vehicles/emergency-stop로 전송된다.
 */
class SafetyCommandWebSocketTest {

    private static VehicleCommandResponse published(String vehicleId, String command) {
        return new VehicleCommandResponse("VCMD-1", vehicleId, command, "ALL", "SAFETY", "PUBLISHED",
                null, null, null, null, null, null, null, List.of(), null, null);
    }

    @Test
    void publishEvent_goesToCommonAndOwnVehicleTopicOnly() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        SafetyCommandBroadcaster broadcaster = new SafetyCommandBroadcaster(template);

        broadcaster.broadcastPublish("EMERGENCY_STOP", published("REAL-F01", "EMERGENCY_STOP"));

        verify(template).convertAndSend(eq("/topic/vehicles/commands"), any(Object.class));
        verify(template).convertAndSend(eq("/topic/vehicles/commands/REAL-F01"), any(Object.class));
        verify(template, never()).convertAndSend(eq("/topic/vehicles/commands/REAL-F02"), any(Object.class));
    }

    @Test
    void publishFailedEvent_carriesFailedEventType() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        SafetyCommandBroadcaster broadcaster = new SafetyCommandBroadcaster(template);

        VehicleCommandResponse failed = new VehicleCommandResponse("VCMD-2", "REAL-F01", "STOP", "EMBEDDED",
                "SAFETY", "PUBLISH_FAILED", null, null, null, null, null, null, null, List.of(), null, null);
        broadcaster.broadcastPublish("STOP", failed);

        org.mockito.ArgumentCaptor<Object> payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSend(eq("/topic/vehicles/commands/REAL-F01"), payload.capture());
        SafetyCommandEvent event = (SafetyCommandEvent) payload.getValue();
        org.assertj.core.api.Assertions.assertThat(event.eventType()).isEqualTo("VEHICLE_STOP_PUBLISH_FAILED");
        org.assertj.core.api.Assertions.assertThat(event.vehicleId()).isEqualTo("REAL-F01");
    }

    @Test
    void globalSummary_goesToEmergencyStopTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        SafetyCommandBroadcaster broadcaster = new SafetyCommandBroadcaster(template);

        broadcaster.broadcastGlobalSummary(3, 2, 1);

        org.mockito.ArgumentCaptor<Object> payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSend(eq("/topic/vehicles/emergency-stop"), payload.capture());
        GlobalEmergencyStopEvent event = (GlobalEmergencyStopEvent) payload.getValue();
        org.assertj.core.api.Assertions.assertThat(event.eventType()).isEqualTo("GLOBAL_ESTOP_PUBLISHED");
        org.assertj.core.api.Assertions.assertThat(event.publishedCount()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(event.failedCount()).isEqualTo(1);
    }
}
