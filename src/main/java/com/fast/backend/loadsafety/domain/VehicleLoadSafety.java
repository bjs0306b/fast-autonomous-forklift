package com.fast.backend.loadsafety.domain;

import java.time.LocalDateTime;

/**
 * 차량별 <b>최신</b> 적재 안전 상태({@code vehicle_load_safety}). 이 프로젝트의
 * {@code vehicle_current_status}/{@code vehicle_fork_current_status}와 동일하게 <b>차량당 1행 upsert</b>
 * 구조다 — 관제 화면이 필요로 하는 것은 "지금 이 차량의 적재가 안전한가"이고, 이력 테이블은
 * prompt63.md가 요구하지 않았다(요구되면 {@code vehicle_status_history} 패턴으로 별도 추가).
 *
 * <p>측정값은 전부 nullable이다. 비전이 화물을 못 찾으면 {@code cargoHeight}가 없고, IMU가 없는 차량은
 * {@code roll}/{@code pitch}가 없다 — 값이 없다는 사실 자체를 0으로 위조하지 않는다.
 */
public class VehicleLoadSafety {

    private String vehicleId;
    private String cargoId;
    private Double forkHeight;
    private Double cargoHeight;
    private Double roll;
    private Double pitch;
    private Double loadOffsetX;
    private Double loadOffsetY;
    private LoadSafetyRiskLevel riskLevel;
    private String riskCode;
    private String message;
    private LoadSafetySource source;
    private LocalDateTime detectedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime updatedAt;

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public String getCargoId() {
        return cargoId;
    }

    public void setCargoId(String cargoId) {
        this.cargoId = cargoId;
    }

    public Double getForkHeight() {
        return forkHeight;
    }

    public void setForkHeight(Double forkHeight) {
        this.forkHeight = forkHeight;
    }

    public Double getCargoHeight() {
        return cargoHeight;
    }

    public void setCargoHeight(Double cargoHeight) {
        this.cargoHeight = cargoHeight;
    }

    public Double getRoll() {
        return roll;
    }

    public void setRoll(Double roll) {
        this.roll = roll;
    }

    public Double getPitch() {
        return pitch;
    }

    public void setPitch(Double pitch) {
        this.pitch = pitch;
    }

    public Double getLoadOffsetX() {
        return loadOffsetX;
    }

    public void setLoadOffsetX(Double loadOffsetX) {
        this.loadOffsetX = loadOffsetX;
    }

    public Double getLoadOffsetY() {
        return loadOffsetY;
    }

    public void setLoadOffsetY(Double loadOffsetY) {
        this.loadOffsetY = loadOffsetY;
    }

    public LoadSafetyRiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(LoadSafetyRiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getRiskCode() {
        return riskCode;
    }

    public void setRiskCode(String riskCode) {
        this.riskCode = riskCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LoadSafetySource getSource() {
        return source;
    }

    public void setSource(LoadSafetySource source) {
        this.source = source;
    }

    public LocalDateTime getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(LocalDateTime detectedAt) {
        this.detectedAt = detectedAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
