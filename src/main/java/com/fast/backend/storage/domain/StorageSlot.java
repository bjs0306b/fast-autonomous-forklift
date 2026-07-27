package com.fast.backend.storage.domain;

import java.time.LocalDateTime;

/**
 * 선반 층({@link RackLevel}) 안의 개별 적재 슬롯(prompt46.md 5장). 화물이 실제로 들어가는 최소 단위.
 *
 * <ul>
 *   <li>{@code width}/{@code length}/{@code height} — 슬롯 내부 유효 크기(m). 모두 0 초과여야 한다.</li>
 *   <li>{@code destinationX}/{@code destinationY} — 이 슬롯에 적재하기 위해 지게차가 정차할 좌표(m).
 *       {@code destinationHeading}은 정차 방향(degree). 실제 값은 팀 협의 대상(prompt46.md 20장).</li>
 *   <li>{@code status} — {@link StorageSlotStatus}. 추천 대상은 EMPTY뿐이다.</li>
 *   <li>{@code reservedTaskId} — 이 슬롯을 예약한 운반 작업의 taskCode(예약 시 설정, 해제 시 null).</li>
 *   <li>{@code storedCargoId} — 실제 적재된 화물의 cargo_id(OCCUPIED 시 설정).</li>
 * </ul>
 */
public class StorageSlot {

    private Long id;
    private String slotCode;
    private Long rackLevelId;
    private double width;
    private double length;
    private double height;
    private Double destinationX;
    private Double destinationY;
    private Double destinationHeading;
    private StorageSlotStatus status;
    private String reservedTaskId;
    private String storedCargoId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public StorageSlot() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSlotCode() {
        return slotCode;
    }

    public void setSlotCode(String slotCode) {
        this.slotCode = slotCode;
    }

    public Long getRackLevelId() {
        return rackLevelId;
    }

    public void setRackLevelId(Long rackLevelId) {
        this.rackLevelId = rackLevelId;
    }

    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
    }

    public double getLength() {
        return length;
    }

    public void setLength(double length) {
        this.length = length;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
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

    public StorageSlotStatus getStatus() {
        return status;
    }

    public void setStatus(StorageSlotStatus status) {
        this.status = status;
    }

    public String getReservedTaskId() {
        return reservedTaskId;
    }

    public void setReservedTaskId(String reservedTaskId) {
        this.reservedTaskId = reservedTaskId;
    }

    public String getStoredCargoId() {
        return storedCargoId;
    }

    public void setStoredCargoId(String storedCargoId) {
        this.storedCargoId = storedCargoId;
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
