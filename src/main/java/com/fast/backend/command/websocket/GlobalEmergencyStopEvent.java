package com.fast.backend.command.websocket;

import java.time.OffsetDateTime;

/**
 * 전체 비상정지 요약 WebSocket 이벤트(prompt53.md 13장). /topic/vehicles/emergency-stop로 전송한다.
 */
public record GlobalEmergencyStopEvent(
        String eventType,
        int requestedCount,
        int publishedCount,
        int failedCount,
        OffsetDateTime occurredAt
) {
}
