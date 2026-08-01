package com.fast.backend.storage.dto;

import com.fast.backend.storage.domain.Cargo;
import java.time.LocalDateTime;

public record CargoResponse(String cargoId, LocalDateTime createdAt) {
    public static CargoResponse from(Cargo cargo) {
        return new CargoResponse(cargo.getCargoId(), cargo.getCreatedAt());
    }
}
