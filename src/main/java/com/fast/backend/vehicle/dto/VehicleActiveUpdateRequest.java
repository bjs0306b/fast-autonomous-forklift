package com.fast.backend.vehicle.dto;

import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/vehicles/{vehicleId}/active 요청 바디.
 */
public record VehicleActiveUpdateRequest(
        @NotNull Boolean active
) {
}
