package com.fast.backend.vehicle.location;

import java.time.OffsetDateTime;

/** 차량 한 대의 최신 위치. 좌표는 m, 방향은 degree를 사용한다. */
public record VehicleLocationSnapshot(
        String vehicleId,
        Double x,
        Double y,
        Double heading,
        Double speed,
        String frameId,
        OffsetDateTime messageAt,
        OffsetDateTime receivedAt
) {
}
