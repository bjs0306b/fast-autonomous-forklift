package com.fast.backend.storage.domain;

import java.time.LocalDateTime;

/** 화물 식별 정보. 치수는 이 테이블이 아니라 측정 결과에 저장한다. */
public class Cargo {
    private String cargoId;
    private LocalDateTime createdAt;

    public String getCargoId() { return cargoId; }
    public void setCargoId(String cargoId) { this.cargoId = cargoId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
