package com.fast.backend.vehicle.websocket;

import java.time.LocalDateTime;

/** {@code VEHICLE_ERROR_OCCURRED} 이벤트의 {@code data} payload(prompt29.md 9장·19장). */
public record EmbeddedErrorEventData(
        String forkliftId,
        String errorCode,
        String errorSource,
        String severity,
        String message,
        LocalDateTime timestamp,
        LocalDateTime receivedAt
) {
}
