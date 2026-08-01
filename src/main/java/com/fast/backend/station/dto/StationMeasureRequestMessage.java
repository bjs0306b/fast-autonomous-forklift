package com.fast.backend.station.dto;

import java.time.OffsetDateTime;

/** 백엔드가 측정 프로그램에 발행하는 화물 측정 요청. */
public record StationMeasureRequestMessage(
        String sessionId,
        String cargoId,
        String taskId,
        String vehicleId,
        int maxAttempts,
        OffsetDateTime requestedAt
) {
}
