package com.fast.backend.transport.dto;

import jakarta.validation.constraints.NotBlank;

public record TransportTaskCreateRequest(@NotBlank String cargoId) {
}
