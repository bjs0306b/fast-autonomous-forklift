package com.fast.backend.embedded.dto;

import java.time.OffsetDateTime;

/**
 * {@code GET /api/vehicles/{forkliftId}/fork-status} 응답(prompt29.md 18장).
 *
 * <p>FR-202 스키마 전환(prompt85)으로 {@code limitBottom}·{@code messageAt} 이 빠졌다 —
 * 두 값은 더 이상 저장되지 않는다(전용 테이블이 사라지고 vehicle_current_status 로 흡수되면서
 * 대응 컬럼이 없어졌다). 실시간 값이 필요하면 WebSocket fork-status 이벤트를 구독해야 한다.
 */
public record EmbeddedForkStatusResponse(
        String forkliftId,
        String forkState,
        String errorCode,
        OffsetDateTime receivedAt
) {
}
