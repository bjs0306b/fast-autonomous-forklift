package com.fast.backend.vehicle.dto;

public record VehicleResponse(
        String vehicleId,
        String name,
        boolean active,
        VehicleStatusResponse status
) {
}
