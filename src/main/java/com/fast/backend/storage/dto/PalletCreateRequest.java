package com.fast.backend.storage.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/pallets 요청(prompt47.md 7장). pickup 좌표 단위 m, heading degree [0,360).
 * heading 범위 검증은 Service에서 수행한다(선택값이라 null 허용).
 */
public record PalletCreateRequest(
        @NotBlank String palletId,
        @NotBlank String cargoId,
        Pickup pickup
) {
    public record Pickup(Double x, Double y, Double heading) {
    }
}
