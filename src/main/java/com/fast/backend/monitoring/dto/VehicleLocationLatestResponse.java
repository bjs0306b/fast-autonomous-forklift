package com.fast.backend.monitoring.dto;

import com.fast.backend.vehicle.location.VehicleLocationSnapshot;

import java.time.OffsetDateTime;

/**
 * 대시보드 차량 최신 위치 응답(prompt50.md 9장). 메모리 스냅샷 1건을 그대로 반영한다(DB 저장 없음).
 */
public record VehicleLocationLatestResponse(
        String vehicleId,
        String source,
        Double x,
        Double y,
        Double heading,
        Double speed,
        String frameId,
        OffsetDateTime messageAt
) {
    public static VehicleLocationLatestResponse from(VehicleLocationSnapshot s) {
        return new VehicleLocationLatestResponse(
                s.vehicleId(), s.source(), s.x(), s.y(), s.heading(), s.speed(), s.frameId(), s.messageAt());
    }
}
