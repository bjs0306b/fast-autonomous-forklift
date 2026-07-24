package com.fast.backend.vehicle.websocket;

import java.time.OffsetDateTime;

/**
 * {@code VEHICLE_FORK_STATUS_UPDATED} 이벤트의 {@code data} payload(prompt29.md 8장·19장). 포크
 * 높이·{@code limitTop}은 절대 포함하지 않는다(작업 원칙 12·13번).
 */
public record EmbeddedForkStatusEventData(
        String forkliftId,
        String forkState,
        Boolean limitBottom,
        String errorCode,
        OffsetDateTime timestamp,
        OffsetDateTime receivedAt
) {
}
