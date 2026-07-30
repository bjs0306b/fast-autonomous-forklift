package com.fast.backend.station.domain;

import java.time.LocalDateTime;

/**
 * {@code station_measurement} 한 행 — FR-202 세션 기반 구조(prompt85).
 *
 * <p>옛 구조(31컬럼: station_id/measured_at/bbox/miniature/eccentric/ratio ...)에서 8컬럼으로 줄었다.
 * 측정 원본 상세값은 더 이상 보관하지 않는다 — 저장하는 것은 <b>배치 판단에 필요한 결과</b>뿐이다.
 * 상세값이 필요하면 WebSocket 브로드캐스트로 흐르는 원본 메시지를 쓰거나, 별도 요구사항으로 다뤄야 한다.
 *
 * <p>{@code sequenceNo}는 <b>수신 순서</b>이지 센서 측정 시각이 아니다. 활성 세션의 최신 행은
 * {@code (session_id, sequence_no DESC)} 로 고른다.
 */
public class StationMeasurement {

    private Long sequenceNo;
    private String measurementId;
    private String sessionId;
    private StationMeasurementStatus status;
    /** 팔레트를 제외한 화물 높이(cm). 측정 실패 시 null. */
    private Double cargoHeight;
    /** 전복 위험 등급(SAFE/WARNING/DANGER). 스테이션이 판정해 보내기 전까지는 null. */
    private String tippingLevel;
    /** 팔레트 기준 화물 돌출 비율. 판정 전이면 null. */
    private Double overhangRatio;
    private LocalDateTime createdAt;

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Long sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public String getMeasurementId() {
        return measurementId;
    }

    public void setMeasurementId(String measurementId) {
        this.measurementId = measurementId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public StationMeasurementStatus getStatus() {
        return status;
    }

    public void setStatus(StationMeasurementStatus status) {
        this.status = status;
    }

    public Double getCargoHeight() {
        return cargoHeight;
    }

    public void setCargoHeight(Double cargoHeight) {
        this.cargoHeight = cargoHeight;
    }

    public String getTippingLevel() {
        return tippingLevel;
    }

    public void setTippingLevel(String tippingLevel) {
        this.tippingLevel = tippingLevel;
    }

    public Double getOverhangRatio() {
        return overhangRatio;
    }

    public void setOverhangRatio(Double overhangRatio) {
        this.overhangRatio = overhangRatio;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
