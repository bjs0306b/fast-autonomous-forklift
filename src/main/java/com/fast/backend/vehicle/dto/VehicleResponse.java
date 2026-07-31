package com.fast.backend.vehicle.dto;


/**
 * GET /api/vehicles 목록 응답의 항목 하나.
 */
public record VehicleResponse(
        String vehicleId,
        String name,
        boolean active,
        VehicleStatusResponse status
) {
}
