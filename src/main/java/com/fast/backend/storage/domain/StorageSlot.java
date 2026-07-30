package com.fast.backend.storage.domain;

/**
 * {@code storage_slot} 한 행 — FR-202 최종 스키마(prompt85).
 *
 * <p>옛 구조와 달라진 점
 * <ul>
 *   <li>PK 가 대리키 {@code id} → 자연키 {@code slotCode}</li>
 *   <li>랙 계층({@code rack_level_id})이 사라졌다 — 슬롯이 최상위다</li>
 *   <li>{@code width}/{@code length}/{@code height} → {@code usableHeight}/{@code forkHeight}.
 *       <b>평면 치수가 없다</b></li>
 *   <li>{@code reservedTaskId} 가 문자열(task_code) → {@code Long}(transport_task.id FK)</li>
 *   <li>{@code createdAt}/{@code updatedAt} 이 없다</li>
 * </ul>
 */
public class StorageSlot {

    private String slotCode;
    /** 화물을 넣을 수 있는 수직 가용 높이(m). */
    private Double usableHeight;
    /** 적재 시 목표 포크 높이(m). */
    private Double forkHeight;
    private Double destinationX;
    private Double destinationY;
    private Double destinationHeading;
    private StorageSlotStatus status;
    /** 이 슬롯을 예약한 운반 작업의 내부 id. RESERVED 일 때만 값이 있다. */
    private Long reservedTaskId;
    /** 적재된 화물. OCCUPIED 일 때만 값이 있다. */
    private String storedCargoId;

    public String getSlotCode() {
        return slotCode;
    }

    public void setSlotCode(String slotCode) {
        this.slotCode = slotCode;
    }

    public Double getUsableHeight() {
        return usableHeight;
    }

    public void setUsableHeight(Double usableHeight) {
        this.usableHeight = usableHeight;
    }

    public Double getForkHeight() {
        return forkHeight;
    }

    public void setForkHeight(Double forkHeight) {
        this.forkHeight = forkHeight;
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

    public Long getReservedTaskId() {
        return reservedTaskId;
    }

    public void setReservedTaskId(Long reservedTaskId) {
        this.reservedTaskId = reservedTaskId;
    }

    public String getStoredCargoId() {
        return storedCargoId;
    }

    public void setStoredCargoId(String storedCargoId) {
        this.storedCargoId = storedCargoId;
    }
}
