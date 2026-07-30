package com.fast.backend.transport.dto;

import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;

import java.time.LocalDateTime;

/**
 * 운반 작업 응답 — FR-202 최종 스키마(prompt85).
 *
 * <p>옛 응답에서 빠진 것: {@code palletId}(파렛트 개념 제거), {@code cargo}(치수 컬럼이 없어 실어
 * 보낼 값이 없다), {@code placement.rackCode/levelNumber}(랙 계층 제거),
 * {@code placement.orientation}(평면 치수가 없어 방향 판정 불가).
 * 새로 들어간 것: {@code measurementId} — 이 배치를 어떤 측정 결과로 판단했는지.
 */
public record TransportTaskResponse(
        String taskId,
        String cargoId,
        String measurementId,
        String vehicleId,
        TaskStatus status,
        Pickup pickup,
        Placement placement,
        LocalDateTime createdAt,
        LocalDateTime assignedAt,
        LocalDateTime startedAt,
        LocalDateTime pickedUpAt,
        LocalDateTime completedAt,
        LocalDateTime failedAt
) {

    public record Pickup(Double x, Double y, Double heading) {
    }

    public record Placement(
            String slotCode,
            Double destinationX,
            Double destinationY,
            Double destinationHeading,
            Double forkHeight
    ) {
    }

    public static TransportTaskResponse of(TransportTask task) {
        return new TransportTaskResponse(
                task.getTaskCode(),
                task.getCargoId(),
                task.getMeasurementId(),
                task.getVehicleId(),
                task.getStatus(),
                new Pickup(task.getSourceX(), task.getSourceY(), task.getSourceHeading()),
                new Placement(
                        task.getDestinationSlotCode(),
                        task.getDestinationX(),
                        task.getDestinationY(),
                        task.getDestinationHeading(),
                        task.getForkHeight()),
                task.getCreatedAt(),
                task.getAssignedAt(),
                task.getStartedAt(),
                task.getPickedUpAt(),
                task.getCompletedAt(),
                task.getFailedAt());
    }
}
