package com.fast.backend.monitoring.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;

import java.time.OffsetDateTime;

/**
 * 대시보드 차량 현재 상태 응답(prompt50.md 8장). 상태 이력이 아니라 <b>차량당 현재 상태 1건</b>이다.
 */
public record VehicleStatusMonitorResponse(
        String vehicleId,
        VehicleStatus status,
        OffsetDateTime messageAt,
        OffsetDateTime receivedAt
) {
}
