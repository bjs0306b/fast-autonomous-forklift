package com.fast.backend.command.websocket;

import java.time.OffsetDateTime;

/**
 * 안전 명령 발행 결과 WebSocket 이벤트(prompt53.md 13장). 차량 상태 토픽(/topic/vehicles/status)과 분리된
 * 명령 처리 토픽(/topic/vehicles/commands)으로 전송한다.
 *
 * <p>{@code eventType} 예: VEHICLE_STOP_PUBLISHED / VEHICLE_STOP_PUBLISH_FAILED /
 * VEHICLE_ESTOP_PUBLISHED / VEHICLE_ESTOP_PUBLISH_FAILED.
 */
public record SafetyCommandEvent(
        String eventType,
        String vehicleId,
        String commandId,
        String command,
        String status,
        String message,
        OffsetDateTime occurredAt
) {
}
