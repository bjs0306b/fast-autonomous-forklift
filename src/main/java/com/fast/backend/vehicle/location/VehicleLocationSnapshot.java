package com.fast.backend.vehicle.location;

import java.time.OffsetDateTime;

/**
 * 차량별 "최신 위치 1건" 스냅샷(prompt47.md 3-3). DB에 저장하지 않는 휘발성 값으로,
 * 자동 배정(추후)의 거리 계산 입력으로만 쓴다. 좌표 m, heading degree, speed m/s.
 */
public record VehicleLocationSnapshot(
        String vehicleId,
        Double x,
        Double y,
        Double heading,
        Double speed,
        OffsetDateTime messageAt
) {
}
