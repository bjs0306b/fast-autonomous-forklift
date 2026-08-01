package com.fast.backend.monitoring.dto;

import com.fast.backend.vehicle.location.VehicleLocationSnapshot;
import java.time.OffsetDateTime;

/** 백엔드가 최종 반영한 차량 최신 위치 응답. */
public record VehicleLocationLatestResponse(
        String vehicleId,
        Double x,
        Double y,
        Double heading,
        Double speed,
        String frameId,
        OffsetDateTime messageAt
) {
    public static VehicleLocationLatestResponse from(VehicleLocationSnapshot snapshot) {
        return new VehicleLocationLatestResponse(
                snapshot.vehicleId(), snapshot.x(), snapshot.y(), snapshot.heading(),
                snapshot.speed(), snapshot.frameId(), snapshot.messageAt());
    }
}
