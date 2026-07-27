package com.fast.backend.storage.dto;

import com.fast.backend.storage.domain.Pallet;
import com.fast.backend.storage.domain.PalletStatus;

import java.time.LocalDateTime;

public record PalletResponse(
        String palletId,
        String cargoId,
        PalletStatus status,
        Pickup pickup,
        LocalDateTime createdAt
) {
    public record Pickup(Double x, Double y, Double heading) {
    }

    public static PalletResponse from(Pallet pallet) {
        return new PalletResponse(
                pallet.getPalletId(), pallet.getCargoId(), pallet.getStatus(),
                new Pickup(pallet.getPickupX(), pallet.getPickupY(), pallet.getPickupHeading()),
                pallet.getCreatedAt());
    }
}
