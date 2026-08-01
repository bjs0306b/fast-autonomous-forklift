package com.fast.backend.vehicle.dto;

import jakarta.validation.constraints.NotBlank;

public record VehicleCreateRequest(
        @NotBlank String vehicleId,
        @NotBlank String name
) {
}
