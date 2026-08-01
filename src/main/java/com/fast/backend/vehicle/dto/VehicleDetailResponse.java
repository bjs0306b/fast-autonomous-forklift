package com.fast.backend.vehicle.dto;

import java.time.LocalDateTime;

public record VehicleDetailResponse(
        String vehicleId,
        String name,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        VehicleStatusResponse status
) {
}
