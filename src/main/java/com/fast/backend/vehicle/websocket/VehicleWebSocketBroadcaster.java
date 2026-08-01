package com.fast.backend.vehicle.websocket;

import com.fast.backend.command.websocket.VehicleCommandResultEventData;
import com.fast.backend.common.websocket.RealtimeEvent;
import com.fast.backend.common.websocket.RealtimeEventType;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** 현재 차량 상태를 전체 및 차량별 토픽으로 발행한다. */
@Component
public class VehicleWebSocketBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(VehicleWebSocketBroadcaster.class);
    private final SimpMessagingTemplate messagingTemplate;

    public VehicleWebSocketBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastStatus(String vehicleId, VehicleStatusResponse data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_STATUS_UPDATED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.STATUS_ALL, VehicleWebSocketTopics.status(vehicleId));
    }

    public void broadcastLocation(String vehicleId, VehicleLocationEventData data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_LOCATION_UPDATED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.LOCATION_ALL, VehicleWebSocketTopics.location(vehicleId));
    }

    public void broadcastPath(String vehicleId, VehiclePathEventData data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_PATH_UPDATED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.PATH_ALL, VehicleWebSocketTopics.path(vehicleId));
    }

    public void broadcastCommandResult(
            String vehicleId, VehicleCommandResultEventData data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_COMMAND_RESULT_UPDATED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.RESULT_ALL, VehicleWebSocketTopics.result(vehicleId));
    }

    private void send(
            RealtimeEventType eventType,
            String vehicleId,
            OffsetDateTime occurredAt,
            Object data,
            String allTopic,
            String vehicleTopic) {
        RealtimeEvent<Object> event = RealtimeEvent.of(eventType, vehicleId, occurredAt, data);
        try {
            messagingTemplate.convertAndSend(allTopic, event);
            messagingTemplate.convertAndSend(vehicleTopic, event);
        } catch (RuntimeException exception) {
            log.error("Vehicle WebSocket broadcast failed: vehicleId={}, eventType={}, error={}",
                    vehicleId, eventType, exception.getMessage());
        }
    }
}
