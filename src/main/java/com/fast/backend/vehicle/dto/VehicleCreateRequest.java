package com.fast.backend.vehicle.dto;

import com.fast.backend.vehicle.domain.VehicleSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/vehicles 요청 바디.
 *
 * <p>{@code source}가 잘못된 문자열(enum에 없는 값)이면 Jackson 역직렬화 단계에서 실패하고,
 * {@link com.fast.backend.common.exception.GlobalExceptionHandler#handleHttpMessageNotReadable}이
 * 이를 잡아 {@code JSON_PARSE_ERROR}(400)로 응답한다 — 별도 검증 코드를 추가하지 않아도 된다.
 */
public record VehicleCreateRequest(
        @NotBlank String vehicleId,
        @NotBlank String name,
        @NotNull VehicleSource source
) {
}
