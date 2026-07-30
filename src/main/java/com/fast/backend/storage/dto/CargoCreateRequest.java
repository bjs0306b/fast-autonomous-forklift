package com.fast.backend.storage.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/cargos 요청.
 *
 * <p>FR-202 최종 스키마(prompt85)에서 치수 컬럼이 사라져 <b>등록 시 크기를 받지 않는다</b>.
 * 화물 높이는 측정 스테이션이 보내는 측정 결과({@code station_measurement.cargo_height})에서만 온다.
 */
public record CargoCreateRequest(
        @NotBlank String cargoId
) {
}
