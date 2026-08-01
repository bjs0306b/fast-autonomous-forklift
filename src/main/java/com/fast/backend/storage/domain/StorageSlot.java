package com.fast.backend.storage.domain;

/** 사전에 등록한 팔레트 적재 위치 한 곳. */
public class StorageSlot {
    private String slotCode;
    private double usableHeight;
    private double forkHeight;
    private double destinationX;
    private double destinationY;
    private double destinationHeading;
    private StorageSlotStatus status;
    private Long reservedTaskId;
    private String storedCargoId;

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
    public Long getReservedTaskId() { return reservedTaskId; }
    public void setReservedTaskId(Long reservedTaskId) { this.reservedTaskId = reservedTaskId; }
    public String getStoredCargoId() { return storedCargoId; }
    public void setStoredCargoId(String storedCargoId) { this.storedCargoId = storedCargoId; }
}
