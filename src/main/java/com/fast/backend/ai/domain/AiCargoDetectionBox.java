package com.fast.backend.ai.domain;

import java.time.LocalDateTime;

/**
 * {@code ai_cargo_detection_box} 테이블 한 행(prompt26.md 9.2장). {@code analysisId}는
 * {@link AiCargoAnalysis#getId()}(BIGINT PK)를 가리키는 FK다 — MQTT payload의 문자열
 * {@code analysisId}(예: "ANALYSIS-20260722-001")와는 다른 값이다.
 */
public class AiCargoDetectionBox {

    private Long id;
    private Long analysisId;
    private String className;
    private Double confidence;
    private int bboxX;
    private int bboxY;
    private int bboxWidth;
    private int bboxHeight;
    private LocalDateTime createdAt;

    public AiCargoDetectionBox() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(Long analysisId) {
        this.analysisId = analysisId;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public int getBboxX() {
        return bboxX;
    }

    public void setBboxX(int bboxX) {
        this.bboxX = bboxX;
    }

    public int getBboxY() {
        return bboxY;
    }

    public void setBboxY(int bboxY) {
        this.bboxY = bboxY;
    }

    public int getBboxWidth() {
        return bboxWidth;
    }

    public void setBboxWidth(int bboxWidth) {
        this.bboxWidth = bboxWidth;
    }

    public int getBboxHeight() {
        return bboxHeight;
    }

    public void setBboxHeight(int bboxHeight) {
        this.bboxHeight = bboxHeight;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
