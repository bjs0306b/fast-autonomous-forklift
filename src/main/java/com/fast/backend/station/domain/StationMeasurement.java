package com.fast.backend.station.domain;

import java.time.LocalDateTime;

/**
 * {@code station_measurement} 테이블 한 행(prompt16.md 6·7단계). 측정 스테이션 측정 결과의 부모 레코드로,
 * detection boxes는 {@link StationMeasurementBox}(1:N)로 분리 저장하고 pallet은 이 부모의 단일 컬럼
 * 세트로 보존한다(정책 8번 "pallet 의미 별도 유지").
 *
 * <p><b>measured_at 오프셋 보존</b>: DB가 MySQL/H2 공용(DATETIME 중심)이라 오프셋을 직접 담을 수 없어,
 * UTC 변환 시각({@code measuredAtUtc})과 오프셋 분({@code measuredAtOffsetMinutes})을 분리 저장한다
 * (prompt16.md 6단계 권장안). 응답 시 두 값으로 원래 {@code OffsetDateTime}을 손실 없이 복원한다.
 */
public class StationMeasurement {

    private Long id;
    private String measurementId;
    private String stationId;
    private String schemaVersion;
    private LocalDateTime measuredAtUtc;
    private Integer measuredAtOffsetMinutes;
    private StationMeasurementStatus status;

    private Integer boxCount;

    // pallet (단일 세트, nullable)
    private Integer palletBboxX;
    private Integer palletBboxY;
    private Integer palletBboxWidth;
    private Integer palletBboxHeight;
    private Double palletScore;

    // distance
    private Double frontCm;
    private Double distanceStdCm;
    private Integer framesUsed;

    // dimensions (status != ok이면 전부 null)
    private Double heightCm;
    private Double widthCm;
    private Double depthCm;
    private Integer miniatureScale;
    private Double miniatureHeightMm;
    private Double miniatureWidthMm;

    // load_balance (status != ok이면 전부 null)
    private Boolean eccentric;
    private String loadDirection;
    private Double ratioX;
    private Double ratioY;
    private Double magnitude;
    private Double threshold;
    private String loadMessage;

    private LocalDateTime receivedAt;
    private LocalDateTime createdAt;

    public StationMeasurement() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMeasurementId() {
        return measurementId;
    }

    public void setMeasurementId(String measurementId) {
        this.measurementId = measurementId;
    }

    public String getStationId() {
        return stationId;
    }

    public void setStationId(String stationId) {
        this.stationId = stationId;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public LocalDateTime getMeasuredAtUtc() {
        return measuredAtUtc;
    }

    public void setMeasuredAtUtc(LocalDateTime measuredAtUtc) {
        this.measuredAtUtc = measuredAtUtc;
    }

    public Integer getMeasuredAtOffsetMinutes() {
        return measuredAtOffsetMinutes;
    }

    public void setMeasuredAtOffsetMinutes(Integer measuredAtOffsetMinutes) {
        this.measuredAtOffsetMinutes = measuredAtOffsetMinutes;
    }

    public StationMeasurementStatus getStatus() {
        return status;
    }

    public void setStatus(StationMeasurementStatus status) {
        this.status = status;
    }

    public Integer getBoxCount() {
        return boxCount;
    }

    public void setBoxCount(Integer boxCount) {
        this.boxCount = boxCount;
    }

    public Integer getPalletBboxX() {
        return palletBboxX;
    }

    public void setPalletBboxX(Integer palletBboxX) {
        this.palletBboxX = palletBboxX;
    }

    public Integer getPalletBboxY() {
        return palletBboxY;
    }

    public void setPalletBboxY(Integer palletBboxY) {
        this.palletBboxY = palletBboxY;
    }

    public Integer getPalletBboxWidth() {
        return palletBboxWidth;
    }

    public void setPalletBboxWidth(Integer palletBboxWidth) {
        this.palletBboxWidth = palletBboxWidth;
    }

    public Integer getPalletBboxHeight() {
        return palletBboxHeight;
    }

    public void setPalletBboxHeight(Integer palletBboxHeight) {
        this.palletBboxHeight = palletBboxHeight;
    }

    public Double getPalletScore() {
        return palletScore;
    }

    public void setPalletScore(Double palletScore) {
        this.palletScore = palletScore;
    }

    public Double getFrontCm() {
        return frontCm;
    }

    public void setFrontCm(Double frontCm) {
        this.frontCm = frontCm;
    }

    public Double getDistanceStdCm() {
        return distanceStdCm;
    }

    public void setDistanceStdCm(Double distanceStdCm) {
        this.distanceStdCm = distanceStdCm;
    }

    public Integer getFramesUsed() {
        return framesUsed;
    }

    public void setFramesUsed(Integer framesUsed) {
        this.framesUsed = framesUsed;
    }

    public Double getHeightCm() {
        return heightCm;
    }

    public void setHeightCm(Double heightCm) {
        this.heightCm = heightCm;
    }

    public Double getWidthCm() {
        return widthCm;
    }

    public void setWidthCm(Double widthCm) {
        this.widthCm = widthCm;
    }

    public Double getDepthCm() {
        return depthCm;
    }

    public void setDepthCm(Double depthCm) {
        this.depthCm = depthCm;
    }

    public Integer getMiniatureScale() {
        return miniatureScale;
    }

    public void setMiniatureScale(Integer miniatureScale) {
        this.miniatureScale = miniatureScale;
    }

    public Double getMiniatureHeightMm() {
        return miniatureHeightMm;
    }

    public void setMiniatureHeightMm(Double miniatureHeightMm) {
        this.miniatureHeightMm = miniatureHeightMm;
    }

    public Double getMiniatureWidthMm() {
        return miniatureWidthMm;
    }

    public void setMiniatureWidthMm(Double miniatureWidthMm) {
        this.miniatureWidthMm = miniatureWidthMm;
    }

    public Boolean getEccentric() {
        return eccentric;
    }

    public void setEccentric(Boolean eccentric) {
        this.eccentric = eccentric;
    }

    public String getLoadDirection() {
        return loadDirection;
    }

    public void setLoadDirection(String loadDirection) {
        this.loadDirection = loadDirection;
    }

    public Double getRatioX() {
        return ratioX;
    }

    public void setRatioX(Double ratioX) {
        this.ratioX = ratioX;
    }

    public Double getRatioY() {
        return ratioY;
    }

    public void setRatioY(Double ratioY) {
        this.ratioY = ratioY;
    }

    public Double getMagnitude() {
        return magnitude;
    }

    public void setMagnitude(Double magnitude) {
        this.magnitude = magnitude;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public String getLoadMessage() {
        return loadMessage;
    }

    public void setLoadMessage(String loadMessage) {
        this.loadMessage = loadMessage;
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
