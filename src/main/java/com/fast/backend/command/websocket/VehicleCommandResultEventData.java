package com.fast.backend.command.websocket;

import java.time.OffsetDateTime;

/** 관제 화면으로 전달하는 차량 명령 결과. */
public record VehicleCommandResultEventData(
        String commandId,
        String vehicleId,
        String targetSystem,
        String commandCategory,
        String command,
        String result,
        String message,
        OffsetDateTime completedAt
) {
}
