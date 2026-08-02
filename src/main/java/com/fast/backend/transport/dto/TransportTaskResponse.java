package com.fast.backend.transport.dto;

import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import java.time.LocalDateTime;

public record TransportTaskResponse(
        String taskId,
        String cargoId,
        String measurementSessionId,
        String measurementId,
        String vehicleId,
        TaskStatus status,
        Placement placement,
        LocalDateTime createdAt,
        LocalDateTime assignedAt,
        LocalDateTime startedAt,
        LocalDateTime measurementRequestedAt,
        LocalDateTime completedAt,
        LocalDateTime failedAt
) {
    public record Placement(
            String slotCode,
            Double destinationX,
            Double destinationY,
            Double destinationHeading,
            Double forkHeight) {
    }

    public static TransportTaskResponse from(TransportTask task) {
        Placement placement = task.getDestinationSlotCode() == null ? null
                : new Placement(task.getDestinationSlotCode(), task.getDestinationX(), task.getDestinationY(),
                        task.getDestinationHeading(), task.getForkHeight());
        return new TransportTaskResponse(
                task.getTaskCode(), task.getCargoId(), task.getMeasurementSessionId(),
                task.getMeasurementId(), task.getVehicleId(),
                task.getStatus(), placement,
                task.getCreatedAt(), task.getAssignedAt(), task.getStartedAt(),
                task.getMeasurementRequestedAt(),
                task.getCompletedAt(), task.getFailedAt());
    }
}
