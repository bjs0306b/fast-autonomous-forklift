package com.fast.backend.embedded.dto;

import java.time.LocalDateTime;

/**
 * {@code forklift/{id}/fork-status} 토픽으로 수신되는 실물 포크 상태(prompt29.md 8장).
 *
 * <p>{@link com.fast.backend.isaac.dto.IsaacForkliftStatusMessage}에는 {@code forkHeight}가 있지만
 * 이는 Isaac Sim 상태 규격 전용이다 — 실물 임베디드 포크 상태에는 포크 높이와 {@code limitTop}을
 * 사용하지 않는다(8장 "중요", 작업 원칙 12·13번). 그래서 이 필드들을 이 클래스에 절대 추가하지 않았다.
 */
public record EmbeddedForkStatusMessage(
        String forkliftId,
        String forkState,
        Boolean limitBottom,
        String errorCode,
        LocalDateTime timestamp
) {
}
