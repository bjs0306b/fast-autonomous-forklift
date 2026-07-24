package com.fast.backend.vehicle.domain;

import java.time.LocalDateTime;

/**
 * {@code vehicle_status_history} 테이블 한 행 — 차량 상태 변경 이력을 누적 저장한다(prompt22.md).
 * {@link VehicleCurrentStatus}와 달리 vehicle_id당 여러 행이 쌓이며, id(AUTO_INCREMENT)가 PK다.
 *
 * <p>Isaac 확장 필드(forkHeight/hasCargo/cargoId/footprintLength/footprintWidth)도 함께 누적한다
 * (prompt32.md 1장 4번). 한 행에는 <b>메시지 수신 당시의 유효 상태</b>가 저장된다 — ROS2 상태 메시지처럼
 * Isaac 필드를 담지 않는 메시지가 오면, 그 시점에 보존되고 있던 기존 Isaac 값이 그대로 기록된다
 * ({@link com.fast.backend.vehicle.service.VehicleStatusService}의 병합 정책 결과와 항상 일치한다).
 */
public class VehicleStatusHistory {

    private Long id;
    private String vehicleId;
    private VehicleStatus status;
    private Integer battery;
    private Double positionX;
    private Double positionY;
    /** degree, [0,360) 정규화된 값(prompt32.md 1장 5번 확정). */
    private Double heading;
    private Double speed;
    private Double forkHeight;
    private Boolean hasCargo;
    private String cargoId;
    private Double footprintLength;
    private Double footprintWidth;
    private LocalDateTime messageAt;
    private LocalDateTime receivedAt;
    private LocalDateTime createdAt;

    public VehicleStatusHistory() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public VehicleStatus getStatus() {
        return status;
    }

    public void setStatus(VehicleStatus status) {
        this.status = status;
    }

    public Integer getBattery() {
        return battery;
    }

    public void setBattery(Integer battery) {
        this.battery = battery;
    }

    public Double getPositionX() {
        return positionX;
    }

    public void setPositionX(Double positionX) {
        this.positionX = positionX;
    }

    public Double getPositionY() {
        return positionY;
    }

    public void setPositionY(Double positionY) {
        this.positionY = positionY;
    }

    public Double getHeading() {
        return heading;
    }

    public void setHeading(Double heading) {
        this.heading = heading;
    }

    public Double getSpeed() {
        return speed;
    }

    public void setSpeed(Double speed) {
        this.speed = speed;
    }

    public Double getForkHeight() {
        return forkHeight;
    }

    public void setForkHeight(Double forkHeight) {
        this.forkHeight = forkHeight;
    }

    public Boolean getHasCargo() {
        return hasCargo;
    }

    public void setHasCargo(Boolean hasCargo) {
        this.hasCargo = hasCargo;
    }

    public String getCargoId() {
        return cargoId;
    }

    public void setCargoId(String cargoId) {
        this.cargoId = cargoId;
    }

    public Double getFootprintLength() {
        return footprintLength;
    }

    public void setFootprintLength(Double footprintLength) {
        this.footprintLength = footprintLength;
    }

    public Double getFootprintWidth() {
        return footprintWidth;
    }

    public void setFootprintWidth(Double footprintWidth) {
        this.footprintWidth = footprintWidth;
    }

    public LocalDateTime getMessageAt() {
        return messageAt;
    }

    public void setMessageAt(LocalDateTime messageAt) {
        this.messageAt = messageAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
