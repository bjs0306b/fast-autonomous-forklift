package com.fast.backend.forklift.dto;

import java.time.OffsetDateTime;

/**
 * forklift/{forkliftId}/status 토픽으로 수신되는 지게차 상태 메시지(임시 Mock 통신 규격).
 */
public record ForkliftStatusMessage(
        String forkliftId,
        String status,
        int battery,
        OffsetDateTime timestamp
) {
}
