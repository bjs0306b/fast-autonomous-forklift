package com.fast.backend.vehicle.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;

import java.time.OffsetDateTime;

/**
 * GET /api/vehicles/{vehicleId}/status-history 응답 항목(prompt22.md).
 *
 * <p>Isaac 확장 필드(forkHeight/hasCargo/cargoId/footprintLength/footprintWidth)를 함께 내려준다
 * (prompt32.md 1장 4번). 시각은 {@code +09:00} {@link OffsetDateTime}이다(1장 6번).
 */
public record VehicleStatusHistoryResponse(
        Long id,
        String vehicleId,
        VehicleStatus status,
        Integer battery,
        Double positionX,
        Double positionY,
        Double heading,
        Double speed,
        Double forkHeight,
        Boolean hasCargo,
        String cargoId,
        Double footprintLength,
        Double footprintWidth,
        OffsetDateTime messageAt,
        OffsetDateTime receivedAt,
        OffsetDateTime createdAt
) {
}
