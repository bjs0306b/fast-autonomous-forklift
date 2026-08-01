package com.fast.backend.storage.dto;

import jakarta.validation.constraints.NotBlank;

public record CargoCreateRequest(@NotBlank String cargoId) {
}
