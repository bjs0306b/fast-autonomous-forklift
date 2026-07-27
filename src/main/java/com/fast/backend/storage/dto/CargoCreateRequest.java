package com.fast.backend.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * POST /api/cargos 요청(prompt47.md 7장). 크기 단위는 meter(m).
 * 값이 0 이하이면 검증 실패(@Positive) — 도메인 {@code Cargo.create}도 동일하게 방어한다.
 */
public record CargoCreateRequest(
        @NotBlank String cargoId,
        @Positive double width,
        @Positive double length,
        @Positive double height
) {
}
