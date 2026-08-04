package com.fast.backend.monitoring.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;
import java.time.OffsetDateTime;
import java.util.List;

/** 관제 대시보드 초기화에 사용하는 상태 스냅샷. */
public record DashboardResponse(List<VehicleView> vehicles, List<TaskView> tasks) {

    /**
     * @param hasCargo    화물 적재 여부. 확인할 근거가 없으면 {@code null}(= 화면에서 "확인 불가").
     *                    {@code false} 와 {@code null} 을 구분하려고 원시 타입을 쓰지 않는다.
     * @param cargoId     차량이 싣고 있는(또는 실을) 화물 식별자. 없으면 {@code null}.
     * @param cargoHeight {@code cargoId} 화물의 측정 높이(m, 팔레트 제외). 측정 결과가 없으면 {@code null}.
     */
    public record VehicleView(
            String vehicleId,
            String name,
            boolean active,
            VehicleStatus status,
            LocationView location,
            CurrentTaskView currentTask,
            Boolean hasCargo,
            Long cargoId,
            Double cargoHeight,
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
