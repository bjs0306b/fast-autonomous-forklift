package com.fast.backend.embedded.dto;

import java.time.LocalDateTime;

/**
 * 백엔드가 {@code forklift/{id}/command} 토픽으로 실물 지게차(REAL01)에 발행하는 명령 payload
 * (prompt29.md 5장 합의 규격). {@link com.fast.backend.isaac.dto.IsaacForkliftCommandMessage}(SIM
 * 차량용, {@code destination} 포함·{@code commandId} 없음)와 필드 구조가 달라 별도 DTO로 분리했다
 * (answer29.md 4장 근거) — Isaac SIM 명령 JSON은 이 클래스 도입으로 전혀 바뀌지 않는다.
 */
public record EmbeddedForkliftCommandMessage(
        String commandId,
        String forkliftId,
        String command,
        String reason,
        LocalDateTime timestamp
) {
}
