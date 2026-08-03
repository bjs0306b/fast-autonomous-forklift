package com.fast.backend.vehicle.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;
import java.time.OffsetDateTime;

/** REST와 WebSocket API로 전달하는 차량 현재 상태. */
public record VehicleStatusResponse(
        VehicleStatus status,
        Double positionX,
        Double positionY,
        String positionFrame,
        Double heading,
        Double speed,
        Boolean hasCargo,
        String cargoId,
        OffsetDateTime messageAt,
        OffsetDateTime receivedAt
) {
    public static VehicleStatusResponse unknown() {
        return new VehicleStatusResponse(
                VehicleStatus.UNKNOWN,
                null, null, null, null, null,
                null, null, null, null);
    }
}
