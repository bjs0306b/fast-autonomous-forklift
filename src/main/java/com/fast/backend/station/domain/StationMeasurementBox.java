package com.fast.backend.station.domain;

import java.time.LocalDateTime;

/**
 * {@code station_measurement_box} 테이블 한 행(prompt16.md 6·7단계). 측정 결과 1건에 detection box 0~N개가
 * 붙는다(1:N). {@code boxOrder}는 payload 배열 순서를 보존한다. bbox 좌표는 nullable이다 —
 * {@code bbox_px}는 관제 오버레이 미사용 시 생략 가능하기 때문이다(정책 7번).
 */
public class StationMeasurementBox {

    private Long id;
    private Long stationMeasurementId;
    private Integer boxOrder;
    private Integer bboxX;
    private Integer bboxY;
    private Integer bboxWidth;
    private Integer bboxHeight;
    private Double score;
    private LocalDateTime createdAt;

    public StationMeasurementBox() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStationMeasurementId() {
        return stationMeasurementId;
    }

    public void setStationMeasurementId(Long stationMeasurementId) {
        this.stationMeasurementId = stationMeasurementId;
    }

    public Integer getBoxOrder() {
        return boxOrder;
    }

    public void setBoxOrder(Integer boxOrder) {
        this.boxOrder = boxOrder;
    }

    public Integer getBboxX() {
        return bboxX;
    }

    public void setBboxX(Integer bboxX) {
        this.bboxX = bboxX;
    }

    public Integer getBboxY() {
        return bboxY;
    }

    public void setBboxY(Integer bboxY) {
        this.bboxY = bboxY;
    }

    public Integer getBboxWidth() {
        return bboxWidth;
    }

    public void setBboxWidth(Integer bboxWidth) {
        this.bboxWidth = bboxWidth;
    }

    public Integer getBboxHeight() {
        return bboxHeight;
    }

    public void setBboxHeight(Integer bboxHeight) {
        this.bboxHeight = bboxHeight;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
