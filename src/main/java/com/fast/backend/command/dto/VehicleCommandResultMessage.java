package com.fast.backend.command.dto;

import java.time.OffsetDateTime;

/** ROS2 MQTT 브리지가 발행하는 명령 결과 메시지. */
public record VehicleCommandResultMessage(
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
