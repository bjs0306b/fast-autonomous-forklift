package com.fast.backend.station.dto;

import com.fast.backend.station.domain.StationMeasurementStatus;
import java.time.LocalDateTime;

/** REST와 WebSocket 소비자에게 전달하는 측정 결과. */
public record StationMeasurementResponse(
        String measurementId,
        String sessionId,
        String cargoId,
        StationMeasurementStatus status,
        Double cargoHeight,
        String tippingLevel,
        Double overhangRatio,
        boolean placementEligible,
        LocalDateTime createdAt
) {
}
