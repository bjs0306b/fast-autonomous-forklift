package com.fast.backend.vehicle.domain;

import java.time.LocalDateTime;

/**
 * {@code vehicle} 테이블 한 행에 대응하는 도메인 객체. MyBatis가 조회 결과를 이 객체로 매핑하고,
 * 등록 시에는 이 객체를 그대로 insert 파라미터로 사용한다(id는 useGeneratedKeys로 insert 후 채워짐).
 *
 * <p>차량 종류(예: 지게차 모델)처럼 이번 Story에서 확정하지 않은 정보는 {@code vehicleType}처럼
 * nullable 필드로만 자리를 잡아두고, 실제 사용하는 로직은 아직 두지 않았다(prompt16.md 4장 마지막 조건).
 */
public class Vehicle {

    private Long id;
    private String vehicleId;
    private String name;
    private VehicleSource source;
    /** 차량 종류. 팀 합의 전까지 사용하지 않는 후속 확장 컬럼(nullable). */
    private String vehicleType;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Vehicle() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public VehicleSource getSource() {
        return source;
    }

    public void setSource(VehicleSource source) {
        this.source = source;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(String vehicleType) {
        this.vehicleType = vehicleType;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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
