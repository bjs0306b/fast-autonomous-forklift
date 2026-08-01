package com.fast.backend.monitoring.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;
import java.time.OffsetDateTime;
import java.util.List;

/** 관제 대시보드 초기화에 사용하는 상태 스냅샷. */
public record DashboardResponse(List<VehicleView> vehicles, List<TaskView> tasks) {

    public record VehicleView(
            String vehicleId,
            String name,
            boolean active,
            VehicleStatus status,
            LocationView location,
            CurrentTaskView currentTask,
            OffsetDateTime lastUpdatedAt) {
    }

    public record LocationView(
            Double x,
            Double y,
            Double heading,
            Double speed,
            String frameId,
            OffsetDateTime messageAt,
            OffsetDateTime receivedAt) {
    }

    public record CurrentTaskView(String taskId, String status, OffsetDateTime updatedAt) {
    }

    public record TaskView(String taskId, String vehicleId, String status, OffsetDateTime updatedAt) {
    }
}
