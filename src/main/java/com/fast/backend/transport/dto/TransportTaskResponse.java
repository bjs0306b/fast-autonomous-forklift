package com.fast.backend.transport.dto;

import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.StorageSlotPlacementRow;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;

import java.time.LocalDateTime;

/**
 * 운반 작업 응답(prompt47.md 8·10장). 생성/상세/배정/상태변경 응답이 공유한다.
 * {@code cargo}/{@code placement}는 정보가 있을 때만 채워진다(목록 응답 등에서는 null일 수 있다).
 */
public record TransportTaskResponse(
        String taskId,
        String cargoId,
        String palletId,
        String vehicleId,
        TaskStatus status,
        Pickup pickup,
        CargoView cargo,
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

    public record CargoView(double width, double length, double height, double volume) {
    }

    public record Placement(
            String rackCode,
            Integer levelNumber,
            String slotCode,
            Long slotId,
            String orientation,
            Double destinationX,
            Double destinationY,
            Double destinationHeading,
            Double forkHeight
    ) {
    }

    public static TransportTaskResponse of(TransportTask task, Cargo cargo, StorageSlotPlacementRow slot) {
        Placement placement = new Placement(
                slot != null ? slot.getRackCode() : null,
                slot != null ? slot.getLevelNumber() : null,
                slot != null ? slot.getSlotCode() : null,
                task.getDestinationSlotId(),
                task.getCargoOrientation() != null ? task.getCargoOrientation().name() : null,
                task.getDestinationX(), task.getDestinationY(), task.getDestinationHeading(),
                task.getForkHeight());
        CargoView cargoView = cargo != null
                ? new CargoView(cargo.getWidth(), cargo.getLength(), cargo.getHeight(), cargo.getVolume())
                : null;
        return new TransportTaskResponse(
                task.getTaskCode(), task.getCargoId(), task.getPalletId(), task.getVehicleId(), task.getStatus(),
                new Pickup(task.getSourceX(), task.getSourceY(), task.getSourceHeading()),
                cargoView, placement,
                task.getCreatedAt(), task.getAssignedAt(), task.getStartedAt(), task.getPickedUpAt(),
                task.getCompletedAt(), task.getFailedAt());
    }
}
