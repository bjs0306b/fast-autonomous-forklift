package com.fast.backend.embedded.dto;

import java.time.LocalDateTime;

/** {@code GET /api/vehicles/{forkliftId}/fork-status} 응답(prompt29.md 18장). */
public record EmbeddedForkStatusResponse(
        String forkliftId,
        String forkState,
        Boolean limitBottom,
        String errorCode,
        LocalDateTime messageAt,
        LocalDateTime receivedAt
) {
}
