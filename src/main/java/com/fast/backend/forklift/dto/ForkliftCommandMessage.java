package com.fast.backend.forklift.dto;

import java.time.LocalDateTime;

/**
 * forklift/{forkliftId}/command 토픽으로 발행할 지게차 제어 명령 메시지(임시 Mock 통신 규격).
 * 이번 단계에서는 실제 이동/정지 REST API가 없어 이 DTO를 발행하는 호출부는 아직 없다.
 */
public record ForkliftCommandMessage(
        String forkliftId,
        String command,
        String destination,
        LocalDateTime timestamp
) {
}
