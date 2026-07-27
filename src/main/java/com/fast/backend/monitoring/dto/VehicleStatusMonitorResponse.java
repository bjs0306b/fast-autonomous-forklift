package com.fast.backend.monitoring.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;

import java.time.OffsetDateTime;

/**
 * 대시보드 차량 현재 상태 응답(prompt50.md 8장). 상태 이력이 아니라 <b>차량당 현재 상태 1건</b>이다.
 * battery는 기존 필드를 유지하되 대시보드에서는 선택적으로 노출한다.
 */
public record VehicleStatusMonitorResponse(
        String vehicleId,
        VehicleStatus status,
        Integer battery,
        OffsetDateTime messageAt,
        OffsetDateTime receivedAt
) {
}
