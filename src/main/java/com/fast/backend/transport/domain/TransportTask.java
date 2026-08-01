package com.fast.backend.transport.domain;

import java.time.LocalDateTime;
import java.util.stream.Stream;

/** 측정 결과와 목적지 스냅샷을 고정해 관리하는 운반 작업. */
public class TransportTask {
    private Long id;
    private String taskCode;
    private String cargoId;
    private String measurementSessionId;
    private String measurementId;
    private String vehicleId;
    private String destinationSlotCode;
    private Double destinationX;
    private Double destinationY;
    private Double destinationHeading;
    private Double forkHeight;
    private TaskStatus status;
    private LocalDateTime assignedAt;
    private LocalDateTime startedAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime completedAt;
    private LocalDateTime failedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
    public String getCargoId() { return cargoId; }
    public void setCargoId(String cargoId) { this.cargoId = cargoId; }
    public String getMeasurementSessionId() { return measurementSessionId; }
    public void setMeasurementSessionId(String measurementSessionId) { this.measurementSessionId = measurementSessionId; }
    public String getMeasurementId() { return measurementId; }
    public void setMeasurementId(String measurementId) { this.measurementId = measurementId; }
    public String getVehicleId() { return vehicleId; }
    public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }
    public String getDestinationSlotCode() { return destinationSlotCode; }
    public void setDestinationSlotCode(String destinationSlotCode) { this.destinationSlotCode = destinationSlotCode; }
    public Double getDestinationX() { return destinationX; }
    public void setDestinationX(Double destinationX) { this.destinationX = destinationX; }
    public Double getDestinationY() { return destinationY; }
    public void setDestinationY(Double destinationY) { this.destinationY = destinationY; }
    public Double getDestinationHeading() { return destinationHeading; }
    public void setDestinationHeading(Double destinationHeading) { this.destinationHeading = destinationHeading; }
    public Double getForkHeight() { return forkHeight; }
    public void setForkHeight(Double forkHeight) { this.forkHeight = forkHeight; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public LocalDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(LocalDateTime assignedAt) { this.assignedAt = assignedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getPickedUpAt() { return pickedUpAt; }
    public void setPickedUpAt(LocalDateTime pickedUpAt) { this.pickedUpAt = pickedUpAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(LocalDateTime failedAt) { this.failedAt = failedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /** 별도 updated_at 없이 대시보드에 제공하는 최신 작업 상태 변경 시각. */
    public LocalDateTime getUpdatedAt() {
        return Stream.of(completedAt, failedAt, pickedUpAt, startedAt, assignedAt, createdAt)
                .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
    }
}
