package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.StorageSlotStatus;

/** 적재 위치 선정에 사용하는 조회 결과. */
public class StorageSlotPlacementRow {
    private String slotCode;
    private double usableHeight;
    private double forkHeight;
    private double destinationX;
    private double destinationY;
    private double destinationHeading;
    private StorageSlotStatus status;

    public String getSlotCode() { return slotCode; }
    public void setSlotCode(String slotCode) { this.slotCode = slotCode; }
    public double getUsableHeight() { return usableHeight; }
    public void setUsableHeight(double usableHeight) { this.usableHeight = usableHeight; }
    public double getForkHeight() { return forkHeight; }
    public void setForkHeight(double forkHeight) { this.forkHeight = forkHeight; }
    public double getDestinationX() { return destinationX; }
    public void setDestinationX(double destinationX) { this.destinationX = destinationX; }
    public double getDestinationY() { return destinationY; }
    public void setDestinationY(double destinationY) { this.destinationY = destinationY; }
    public double getDestinationHeading() { return destinationHeading; }
    public void setDestinationHeading(double destinationHeading) { this.destinationHeading = destinationHeading; }
    public StorageSlotStatus getStatus() { return status; }
    public void setStatus(StorageSlotStatus status) { this.status = status; }
}
