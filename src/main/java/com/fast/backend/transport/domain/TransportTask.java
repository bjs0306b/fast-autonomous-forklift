package com.fast.backend.transport.domain;

import java.time.LocalDateTime;

/**
 * {@code transport_task} 한 행 — FR-202 최종 스키마(prompt85).
 *
 * <p>옛 구조에서 달라진 점
 * <ul>
 *   <li>{@code palletId} 제거 — 파렛트 테이블이 사라졌다. 픽업 좌표는 요청이 직접 준다</li>
 *   <li>{@code measurementId} 추가(NOT NULL FK) — 어떤 측정 결과로 배치를 판단했는지 남긴다</li>
 *   <li>{@code destinationSlotId}(BIGINT) → {@code destinationSlotCode}(VARCHAR) — 슬롯 PK 가 코드로 바뀜</li>
 *   <li>{@code cargoOrientation} 제거 — 평면 치수가 없어 방향을 판정할 수 없다</li>
 *   <li>{@code updatedAt} 제거</li>
 * </ul>
 */
public class TransportTask {

    private Long id;
    private String taskCode;
    private String cargoId;
    private String measurementId;
    private String vehicleId;
    private Double sourceX;
    private Double sourceY;
    private Double sourceHeading;
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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskCode() {
        return taskCode;
    }

    public void setTaskCode(String taskCode) {
        this.taskCode = taskCode;
    }

    public String getCargoId() {
        return cargoId;
    }

    public void setCargoId(String cargoId) {
        this.cargoId = cargoId;
    }

    public String getMeasurementId() {
        return measurementId;
    }

    public void setMeasurementId(String measurementId) {
        this.measurementId = measurementId;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Double getSourceX() {
        return sourceX;
    }

    public void setSourceX(Double sourceX) {
        this.sourceX = sourceX;
    }

    public Double getSourceY() {
        return sourceY;
    }

    public void setSourceY(Double sourceY) {
        this.sourceY = sourceY;
    }

    public Double getSourceHeading() {
        return sourceHeading;
    }

    public void setSourceHeading(Double sourceHeading) {
        this.sourceHeading = sourceHeading;
    }

    public String getDestinationSlotCode() {
        return destinationSlotCode;
    }

    public void setDestinationSlotCode(String destinationSlotCode) {
        this.destinationSlotCode = destinationSlotCode;
    }

    public Double getDestinationX() {
        return destinationX;
    }

    public void setDestinationX(Double destinationX) {
        this.destinationX = destinationX;
    }

    public Double getDestinationY() {
        return destinationY;
    }

    public void setDestinationY(Double destinationY) {
        this.destinationY = destinationY;
    }

    public Double getDestinationHeading() {
        return destinationHeading;
    }

    public void setDestinationHeading(Double destinationHeading) {
        this.destinationHeading = destinationHeading;
    }

    public Double getForkHeight() {
        return forkHeight;
    }

    public void setForkHeight(Double forkHeight) {
        this.forkHeight = forkHeight;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(LocalDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getPickedUpAt() {
        return pickedUpAt;
    }

    public void setPickedUpAt(LocalDateTime pickedUpAt) {
        this.pickedUpAt = pickedUpAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(LocalDateTime failedAt) {
        this.failedAt = failedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
