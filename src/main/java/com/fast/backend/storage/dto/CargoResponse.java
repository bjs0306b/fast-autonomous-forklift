package com.fast.backend.storage.dto;

import com.fast.backend.storage.domain.Cargo;

import java.time.LocalDateTime;

/** 화물 응답. FR-202 최종 스키마에 맞춰 치수 필드가 없다(prompt85). */
public record CargoResponse(
        String cargoId,
        LocalDateTime createdAt
) {
    public static CargoResponse from(Cargo cargo) {
        return new CargoResponse(cargo.getCargoId(), cargo.getCreatedAt());
    }
}
