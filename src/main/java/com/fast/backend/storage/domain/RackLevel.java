package com.fast.backend.storage.domain;

import java.time.LocalDateTime;

/**
 * 선반의 한 층(prompt46.md 5장). 같은 {@link Rack} 안에서 {@code levelNumber}는 중복되지 않는다
 * (DB unique(rack_id, level_number)).
 *
 * <ul>
 *   <li>{@code clearWidth} — 해당 층의 유효 가로(m)</li>
 *   <li>{@code clearLength} — 해당 층의 유효 깊이(m)</li>
 *   <li>{@code clearHeight} — 화물을 넣을 수 있는 내부 유효 높이(m)</li>
 *   <li>{@code forkHeight} — 지게차가 이 층에 적재할 때 필요한 포크 높이(m). 실제 값은 팀 협의 대상
 *       (prompt46.md 20장)</li>
 * </ul>
 */
public class RackLevel {

    private Long id;
    private Long rackId;
    private int levelNumber;
    private double clearWidth;
    private double clearLength;
    private double clearHeight;
    private Double forkHeight;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public RackLevel() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRackId() {
        return rackId;
    }

    public void setRackId(Long rackId) {
        this.rackId = rackId;
    }

    public int getLevelNumber() {
        return levelNumber;
    }

    public void setLevelNumber(int levelNumber) {
        this.levelNumber = levelNumber;
    }

    public double getClearWidth() {
        return clearWidth;
    }

    public void setClearWidth(double clearWidth) {
        this.clearWidth = clearWidth;
    }

    public double getClearLength() {
        return clearLength;
    }

    public void setClearLength(double clearLength) {
        this.clearLength = clearLength;
    }

    public double getClearHeight() {
        return clearHeight;
    }

    public void setClearHeight(double clearHeight) {
        this.clearHeight = clearHeight;
    }

    public Double getForkHeight() {
        return forkHeight;
    }

    public void setForkHeight(Double forkHeight) {
        this.forkHeight = forkHeight;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
