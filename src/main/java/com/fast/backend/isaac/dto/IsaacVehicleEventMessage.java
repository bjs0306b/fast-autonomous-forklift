package com.fast.backend.isaac.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/** {@code fast/v1/vehicle/{id}/event}의 상태 전이·오류 알림. 미정 필드는 관대하게 수용한다. */
public record IsaacVehicleEventMessage(
        String vehicleId,
        Long ts,
        String state,
        @JsonAlias("type") String event,
        String message,
        String taskId
) {
}
