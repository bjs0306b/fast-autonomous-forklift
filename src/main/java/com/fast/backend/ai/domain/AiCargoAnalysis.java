package com.fast.backend.ai.domain;

import java.time.LocalDateTime;

/**
 * {@code ai_cargo_analysis} 테이블 한 행(prompt26.md 9.1장). {@code loadDirection}은
 * loadBalance.direction 배열을 쉼표로 이어붙인 원시 문자열 그대로 보관한다 — List 변환은
 * {@code AiCargoAnalysisService}가 응답 DTO로 변환할 때 수행한다(Domain은 DB 컬럼과 1:1로만 대응).
 */
public class AiCargoAnalysis {

    private Long id;
    private String analysisId;
    private String schemaVersion;
    private String vehicleId;
    private String cargoId;
    private AiAnalysisStatus status;
    private Double distanceCm;
    private Double distanceStdCm;
    private Double widthCm;
    private Double heightCm;
    private Double depthCm;
    private Double volumeCm3;
    private DimensionScale dimensionScale;
    private String loadDirection;
    private String loadMessage;
    private Double ratioHorizontal;
    private Double ratioVertical;
    private String message;
    private LocalDateTime capturedAt;
    private LocalDateTime processedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime createdAt;

    public AiCargoAnalysis() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(String analysisId) {
        this.analysisId = analysisId;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

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

    public AiAnalysisStatus getStatus() {
        return status;
    }

    public void setStatus(AiAnalysisStatus status) {
        this.status = status;
    }

    public Double getDistanceCm() {
        return distanceCm;
    }

    public void setDistanceCm(Double distanceCm) {
        this.distanceCm = distanceCm;
    }

    public Double getDistanceStdCm() {
        return distanceStdCm;
    }

    public void setDistanceStdCm(Double distanceStdCm) {
        this.distanceStdCm = distanceStdCm;
    }

    public Double getWidthCm() {
        return widthCm;
    }

    public void setWidthCm(Double widthCm) {
        this.widthCm = widthCm;
    }

    public Double getHeightCm() {
        return heightCm;
    }

    public void setHeightCm(Double heightCm) {
        this.heightCm = heightCm;
    }

    public Double getDepthCm() {
        return depthCm;
    }

    public void setDepthCm(Double depthCm) {
        this.depthCm = depthCm;
    }

    public Double getVolumeCm3() {
        return volumeCm3;
    }

    public void setVolumeCm3(Double volumeCm3) {
        this.volumeCm3 = volumeCm3;
    }

    public DimensionScale getDimensionScale() {
        return dimensionScale;
    }

    public void setDimensionScale(DimensionScale dimensionScale) {
        this.dimensionScale = dimensionScale;
    }

    public String getLoadDirection() {
        return loadDirection;
    }

    public void setLoadDirection(String loadDirection) {
        this.loadDirection = loadDirection;
    }

    public String getLoadMessage() {
        return loadMessage;
    }

    public void setLoadMessage(String loadMessage) {
        this.loadMessage = loadMessage;
    }

    public Double getRatioHorizontal() {
        return ratioHorizontal;
    }

    public void setRatioHorizontal(Double ratioHorizontal) {
        this.ratioHorizontal = ratioHorizontal;
    }

    public Double getRatioVertical() {
        return ratioVertical;
    }

    public void setRatioVertical(Double ratioVertical) {
        this.ratioVertical = ratioVertical;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(LocalDateTime capturedAt) {
        this.capturedAt = capturedAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
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
