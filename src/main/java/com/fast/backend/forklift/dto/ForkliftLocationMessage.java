package com.fast.backend.forklift.dto;

import java.time.LocalDateTime;

/**
 * forklift/{forkliftId}/location 토픽으로 수신되는 지게차 위치 메시지(임시 Mock 통신 규격).
 */
public record ForkliftLocationMessage(
        String forkliftId,
        double x,
        double y,
        double direction,
        double speed,
        LocalDateTime timestamp
) {
}
