package com.fast.backend.monitoring.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대시보드 초기 조회 통합 응답(prompt50.md 10장). vehicleId 기준으로 현재 상태와 최신 위치만 결합하고,
 * 최신 운반 작업 상태 목록을 함께 담는다. 위치가 없는 차량도 목록에서 사라지지 않으며 location은 nullable이다.
 */
public record DashboardResponse(
        List<VehicleView> vehicles,
        List<TaskView> tasks
) {

    public record VehicleView(
            String vehicleId,
            VehicleStatus status,
            LocationView location,
            OffsetDateTime lastUpdatedAt
    ) {
    }

    public record LocationView(Double x, Double y, Double heading, Double speed, String frameId) {
    }

    public record TaskView(
            String taskId,
            String vehicleId,
            String status,
            String commandStatus,
            OffsetDateTime updatedAt
    ) {
    }
}
