package com.fast.backend.embedded.dto;

import java.time.LocalDateTime;

/** {@code GET /api/vehicles/{forkliftId}/embedded-errors} 목록 응답 항목(prompt29.md 18장). */
public record EmbeddedErrorResponse(
        Long id,
        String forkliftId,
        String errorCode,
        String errorSource,
        String severity,
        String message,
        LocalDateTime occurredAt,
        LocalDateTime receivedAt
) {
}
