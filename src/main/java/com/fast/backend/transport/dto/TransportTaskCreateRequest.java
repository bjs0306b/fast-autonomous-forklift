package com.fast.backend.transport.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /api/transport-tasks 요청(prompt47.md 8장). */
public record TransportTaskCreateRequest(
        @NotBlank String cargoId,
        @NotBlank String palletId
) {
}
