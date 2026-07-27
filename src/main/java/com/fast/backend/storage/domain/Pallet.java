package com.fast.backend.storage.domain;

import java.time.LocalDateTime;

/**
 * 팔레트 정보(prompt46.md 4장). 화물({@link Cargo})을 실어 나르는 단위이며, 카메라·AI가 인식한
 * pickup 위치를 함께 보관한다.
 *
 * <p><b>단위</b>: {@code pickupX}/{@code pickupY}는 m, {@code pickupHeading}은 degree [0,360)로
 * 기존 차량 위치 규격(prompt32.md 1장 5번)과 동일하게 다룬다.
 *
 * <p>{@code cargoId}는 연관 {@link Cargo}의 업무 식별자(cargo_id)를 가리킨다 — DB에서는 FK로 연결한다.
 */
public class Pallet {

    private Long id;
    private String palletId;
    private String cargoId;
    private Double pickupX;
    private Double pickupY;
    private Double pickupHeading;
    private PalletStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Pallet() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPalletId() {
        return palletId;
    }

    public void setPalletId(String palletId) {
        this.palletId = palletId;
    }

    public String getCargoId() {
        return cargoId;
    }

    public void setCargoId(String cargoId) {
        this.cargoId = cargoId;
    }

    public Double getPickupX() {
        return pickupX;
    }

    public void setPickupX(Double pickupX) {
        this.pickupX = pickupX;
    }

    public Double getPickupY() {
        return pickupY;
    }

    public void setPickupY(Double pickupY) {
        this.pickupY = pickupY;
    }

    public Double getPickupHeading() {
        return pickupHeading;
    }

    public void setPickupHeading(Double pickupHeading) {
        this.pickupHeading = pickupHeading;
    }

    public PalletStatus getStatus() {
        return status;
    }

    public void setStatus(PalletStatus status) {
        this.status = status;
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
