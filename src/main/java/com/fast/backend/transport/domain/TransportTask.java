package com.fast.backend.transport.domain;

import com.fast.backend.storage.domain.CargoOrientation;

import java.time.LocalDateTime;

/**
 * 운반 작업(prompt46.md 9장). 팔레트를 pickup 위치에서 추천 슬롯(destination)까지 옮기는 한 건의 작업.
 *
 * <p>생성 시점 값: {@code vehicleId=null}, {@code status=PENDING}. 차량은 배정(수동/자동) 단계에서
 * 채워지고 상태가 {@link TaskStatus#ASSIGNED}로 바뀐다.
 *
 * <p><b>단위</b>: source/destination 좌표는 m, heading은 degree, {@code forkHeight}는 m.
 *
 * <p>{@code cargoId}/{@code palletId}/{@code destinationSlotId}는 각각 연관 화물·팔레트·슬롯을 가리킨다.
 */
public class TransportTask {

    private Long id;
    private String taskCode;
    private String cargoId;
    private String palletId;
    private String vehicleId;
    private Double sourceX;
    private Double sourceY;
    private Double sourceHeading;
    private Long destinationSlotId;
    private Double destinationX;
    private Double destinationY;
    private Double destinationHeading;
    private Double forkHeight;
    private CargoOrientation cargoOrientation;
    private TaskStatus status;
    private LocalDateTime assignedAt;
    private LocalDateTime startedAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime completedAt;
    private LocalDateTime failedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public TransportTask() {
    }

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

    public String getPalletId() {
        return palletId;
    }

    public void setPalletId(String palletId) {
        this.palletId = palletId;
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

    public Long getDestinationSlotId() {
        return destinationSlotId;
    }

    public void setDestinationSlotId(Long destinationSlotId) {
        this.destinationSlotId = destinationSlotId;
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

    public CargoOrientation getCargoOrientation() {
        return cargoOrientation;
    }

    public void setCargoOrientation(CargoOrientation cargoOrientation) {
        this.cargoOrientation = cargoOrientation;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
