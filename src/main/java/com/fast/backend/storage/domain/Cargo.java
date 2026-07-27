package com.fast.backend.storage.domain;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;

import java.time.LocalDateTime;

/**
 * 화물 정보(prompt46.md 4장). 카메라·AI 서버가 인식해 전달한 화물의 크기를 저장한다.
 *
 * <p><b>단위</b>: {@code width}/{@code length}/{@code height}는 모두 <b>meter(m)</b>다 — 이 프로젝트는
 * 좌표·크기를 m로 다룬다(차량 위치 규격 prompt32.md 1장 5번과 동일). 단위 명시가 없던 부분이라
 * prompt46.md 4장 지침에 따라 m로 확정하고 JavaDoc·문서에 기록한다.
 *
 * <p><b>적재 가능 판정은 volume이 아니라 가로·세로·높이를 각각 비교</b>한다(prompt46.md 4장). {@code volume}은
 * Best Fit 추천에서 낭비 부피(wastedVolume) 계산 등 참고용으로만 쓰고, 슬롯 진입 가능 여부 판단에는
 * 쓰지 않는다.
 *
 * <p>MyBatis 매핑을 위해 무인자 생성자와 setter를 둔다. 크기 검증(0 초과)과 volume 계산은
 * {@link #create(String, double, double, double)} 팩토리가 담당한다.
 */
public class Cargo {

    private Long id;
    private String cargoId;
    private double width;
    private double length;
    private double height;
    private double volume;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Cargo() {
    }

    /**
     * 크기를 검증(모두 0 초과)하고 volume(=width*length*height)을 계산한 Cargo를 만든다.
     * 하나라도 0 이하이면 {@link ErrorCode#CARGO_DIMENSION_INVALID}로 거부한다.
     */
    public static Cargo create(String cargoId, double width, double length, double height) {
        if (cargoId == null || cargoId.isBlank()) {
            throw new BusinessException(ErrorCode.CARGO_DIMENSION_INVALID, "cargoId는 필수입니다.");
        }
        if (width <= 0 || length <= 0 || height <= 0) {
            throw new BusinessException(ErrorCode.CARGO_DIMENSION_INVALID,
                    "width/length/height는 0보다 커야 합니다: " + width + "x" + length + "x" + height);
        }
        Cargo cargo = new Cargo();
        cargo.cargoId = cargoId;
        cargo.width = width;
        cargo.length = length;
        cargo.height = height;
        cargo.volume = width * length * height;
        return cargo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCargoId() {
        return cargoId;
    }

    public void setCargoId(String cargoId) {
        this.cargoId = cargoId;
    }

    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
    }

    public double getLength() {
        return length;
    }

    public void setLength(double length) {
        this.length = length;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public double getVolume() {
        return volume;
    }

    public void setVolume(double volume) {
        this.volume = volume;
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
