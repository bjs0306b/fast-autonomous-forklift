package com.fast.backend.vehicle.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/vehicles 요청 바디.
 *
 * {@link com.fast.backend.common.exception.GlobalExceptionHandler#handleHttpMessageNotReadable}이
 * 이를 잡아 {@code JSON_PARSE_ERROR}(400)로 응답한다 — 별도 검증 코드를 추가하지 않아도 된다.
 */
public record VehicleCreateRequest(
        @NotBlank String vehicleId,
        @NotBlank String name
) {
}
