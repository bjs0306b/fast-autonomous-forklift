package com.fast.backend.vehicle.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;

import java.time.LocalDateTime;

/**
 * GET /api/vehicles/{vehicleId}/status-history 응답 항목(prompt22.md).
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
        LocalDateTime messageAt,
        LocalDateTime receivedAt,
        LocalDateTime createdAt
) {
}
