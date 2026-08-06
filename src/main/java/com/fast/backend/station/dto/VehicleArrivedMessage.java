package com.fast.backend.station.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/** {@code forklift/{id}/arrived} payload. 차량 ID와 taskId는 선택이며 토픽 ID가 정본이다. */
public record VehicleArrivedMessage(
        @JsonAlias("forkliftId") String vehicleId,
        String taskId,
        Long ts
) {
}
