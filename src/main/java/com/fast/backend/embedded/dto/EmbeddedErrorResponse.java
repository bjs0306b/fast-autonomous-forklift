package com.fast.backend.embedded.dto;

import java.time.OffsetDateTime;

/** {@code GET /api/vehicles/{forkliftId}/embedded-errors} 목록 응답 항목(prompt29.md 18장). */
public record EmbeddedErrorResponse(
        Long id,
        String forkliftId,
        String errorCode,
        String errorSource,
        String severity,
        String message,
        OffsetDateTime occurredAt,
        OffsetDateTime receivedAt
) {
}
