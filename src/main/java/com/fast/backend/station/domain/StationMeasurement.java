package com.fast.backend.station.domain;

import java.time.LocalDateTime;

/** 화물 측정 파이프라인이 생성하고 DB에 저장하는 최소 측정 결과. */
public class StationMeasurement {

    private Long id;
    private String measurementId;
    private String sessionId;
    private StationMeasurementStatus status;
    private Double cargoHeight;
    private String tippingLevel;
    private Double overhangRatio;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMeasurementId() { return measurementId; }
    public void setMeasurementId(String measurementId) { this.measurementId = measurementId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public StationMeasurementStatus getStatus() { return status; }
    public void setStatus(StationMeasurementStatus status) { this.status = status; }
    public Double getCargoHeight() { return cargoHeight; }
    public void setCargoHeight(Double cargoHeight) { this.cargoHeight = cargoHeight; }
    public String getTippingLevel() { return tippingLevel; }
    public void setTippingLevel(String tippingLevel) { this.tippingLevel = tippingLevel; }
    public Double getOverhangRatio() { return overhangRatio; }
    public void setOverhangRatio(Double overhangRatio) { this.overhangRatio = overhangRatio; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
