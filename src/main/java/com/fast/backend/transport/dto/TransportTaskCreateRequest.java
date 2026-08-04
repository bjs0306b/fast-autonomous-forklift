package com.fast.backend.transport.dto;

import jakarta.validation.constraints.NotNull;

public record TransportTaskCreateRequest(@NotNull Long cargoId) {
}
