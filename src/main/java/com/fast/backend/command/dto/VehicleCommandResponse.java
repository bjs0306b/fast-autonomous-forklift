package com.fast.backend.command.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 명령 발행/조회 REST 응답. POST/GET 단건/GET 목록에서 동일하게 사용한다.
 *
 * <p>시각은 전부 {@code +09:00} {@link OffsetDateTime}이다(prompt32.md 1장 6번).
 * {@code stoppedActions}/{@code emergencyStopApplied}/{@code requiresReset}은 결과를 아직 받지 않았거나
 * 해당 정보가 없는 명령에서 각각 빈 리스트/null이다.
 */
public record VehicleCommandResponse(
        String commandId,
        String vehicleId,
        String command,
        String targetSystem,
        String commandCategory,
        String status,
        String reason,
        String payloadJson,
        OffsetDateTime issuedAt,
        OffsetDateTime publishedAt,
        OffsetDateTime completedAt,
        String errorCode,
        String resultMessage,
        List<String> stoppedActions,
        Boolean emergencyStopApplied,
        Boolean requiresReset
) {
}
