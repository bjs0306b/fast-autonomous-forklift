package com.fast.backend.embedded.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 명령 발행/조회 REST 응답(prompt29.md 18장). POST/GET 단건/GET 목록에서 동일하게 사용한다. */
public record EmbeddedCommandResponse(
        String commandId,
        String forkliftId,
        String command,
        String status,
        String reason,
        LocalDateTime issuedAt,
        LocalDateTime publishedAt,
        LocalDateTime completedAt,
        String errorCode,
        String resultMessage,
        List<String> stoppedActions,
        Boolean emergencyStopApplied,
        Boolean requiresReset
) {
}
