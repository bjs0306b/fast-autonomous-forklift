package com.fast.backend.storage.domain;

import java.time.LocalDateTime;

/**
 * 선반(Rack) — 여러 층({@link RackLevel})을 갖는 물리 구조물(prompt46.md 5장).
 *
 * <p><b>단위</b>: {@code positionX}/{@code positionY}는 m다. 실제 선반 좌표·규격은 팀 협의 대상이며
 * (prompt46.md 20장), 여기서는 구조만 정의한다.
 */
public class Rack {

    private Long id;
    private String rackCode;
    private String rackName;
    private Double positionX;
    private Double positionY;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Rack() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRackCode() {
        return rackCode;
    }

    public void setRackCode(String rackCode) {
        this.rackCode = rackCode;
    }

    public String getRackName() {
        return rackName;
    }

    public void setRackName(String rackName) {
        this.rackName = rackName;
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
