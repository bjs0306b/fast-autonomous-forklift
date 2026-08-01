package com.fast.backend.command.dto;

import java.time.OffsetDateTime;

/** DB에 저장된 차량 명령의 최소 응답 정보. */
public record VehicleCommandResponse(
        String commandId,
        String vehicleId,
        String command,
        String targetSystem,
        String status,
        OffsetDateTime completedAt,
        String resultMessage,
        OffsetDateTime createdAt) {
}
