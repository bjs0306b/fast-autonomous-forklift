package com.fast.backend.vehicle.dto;

import com.fast.backend.vehicle.domain.VehicleSource;

/**
 * GET /api/vehicles 목록 응답의 항목 하나.
 */
public record VehicleResponse(
        String vehicleId,
        String name,
        VehicleSource source,
        boolean active,
        VehicleStatusResponse status
) {
}
