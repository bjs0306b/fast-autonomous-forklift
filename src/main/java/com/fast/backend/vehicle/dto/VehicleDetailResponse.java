package com.fast.backend.vehicle.dto;

import com.fast.backend.vehicle.domain.VehicleSource;

import java.time.LocalDateTime;

/**
 * GET /api/vehicles/{vehicleId} 상세 응답. 기본 정보 + 최신 상태를 함께 제공한다.
 */
public record VehicleDetailResponse(
        String vehicleId,
        String name,
        VehicleSource source,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        VehicleStatusResponse status
) {
}
