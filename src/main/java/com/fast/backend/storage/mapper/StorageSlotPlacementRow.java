package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.StorageSlotStatus;

/**
 * 적재 후보 조회용 Row DTO(prompt47.md 6장). storage_slot + rack_level + rack 조인 결과를 담아
 * Service에서 {@link com.fast.backend.storage.placement.PlacementCandidate}로 변환한다 —
 * {@code PlacementService}의 순수 알고리즘 특성을 유지하기 위해 Mapper 결과를 알고리즘 입력과 분리한다.
 */
public class StorageSlotPlacementRow {

    private Long slotId;
    private String slotCode;
    private Long rackId;
    private String rackCode;
    private Long rackLevelId;
    private int levelNumber;
    private double slotWidth;
    private double slotLength;
    private double slotHeight;
    private Double destinationX;
    private Double destinationY;
    private Double destinationHeading;
    private Double forkHeight;
    private StorageSlotStatus status;

    public Long getSlotId() {
        return slotId;
    }

    public void setSlotId(Long slotId) {
        this.slotId = slotId;
    }

    public String getSlotCode() {
        return slotCode;
    }

    public void setSlotCode(String slotCode) {
        this.slotCode = slotCode;
    }

    public Long getRackId() {
        return rackId;
    }

    public void setRackId(Long rackId) {
        this.rackId = rackId;
    }

    public String getRackCode() {
        return rackCode;
    }

    public void setRackCode(String rackCode) {
        this.rackCode = rackCode;
    }

    public Long getRackLevelId() {
        return rackLevelId;
    }

    public void setRackLevelId(Long rackLevelId) {
        this.rackLevelId = rackLevelId;
    }

    public int getLevelNumber() {
        return levelNumber;
    }

    public void setLevelNumber(int levelNumber) {
        this.levelNumber = levelNumber;
    }

    public double getSlotWidth() {
        return slotWidth;
    }

    public void setSlotWidth(double slotWidth) {
        this.slotWidth = slotWidth;
    }

    public double getSlotLength() {
        return slotLength;
    }

    public void setSlotLength(double slotLength) {
        this.slotLength = slotLength;
    }

    public double getSlotHeight() {
        return slotHeight;
    }

    public void setSlotHeight(double slotHeight) {
        this.slotHeight = slotHeight;
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

    public StorageSlotStatus getStatus() {
        return status;
    }

    public void setStatus(StorageSlotStatus status) {
        this.status = status;
    }
}
