package com.fast.backend.embedded.dto;

import java.time.OffsetDateTime;

/**
 * {@code forklift/{id}/error} 토픽으로 수신되는 실물 임베디드 오류 이벤트(prompt29.md 9장).
 * {@code errorCode}는 후보 목록이 아직 최종 확정되지 않아 enum이 아닌 String으로 유지한다(9장 근거).
 */
public record EmbeddedErrorMessage(
        String forkliftId,
        String errorCode,
        String errorSource,
        String severity,
        String message,
        OffsetDateTime timestamp
) {
}
