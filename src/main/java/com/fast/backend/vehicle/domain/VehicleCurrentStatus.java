package com.fast.backend.vehicle.domain;

import java.time.LocalDateTime;

/**
 * {@code vehicle_current_status} 테이블 한 행 — 차량당 최신 상태 1행만 유지한다(vehicle_id가 PK).
 *
 * <p>상태 토픽과 위치 토픽이 서로 다른 필드를 갱신하므로 대부분의 측정값은 nullable이다.
 * {@code heading}은 degree, 범위는 {@code [0,360)}다.
 *
 * <p>모든 시각은 <b>Asia/Seoul 벽시계 기준 {@link LocalDateTime}</b>이다
 * ({@link com.fast.backend.common.time.CommunicationTime} 참고). 통신 계층의 {@code OffsetDateTime}
 * (+09:00)과는 Service 경계에서 변환된다.
 */
public class VehicleCurrentStatus {

    private String vehicleId;
    private VehicleStatus status;
    private Double positionX;
    private Double positionY;
    private String positionFrame;
    /** degree, [0,360) 정규화된 값. */
    private Double heading;
    private Double speed;
    /** 화물 적재 여부. */
    private Boolean hasCargo;
    /** 적재된 화물 식별자. */
    private String cargoId;
    /** 송신 측(ROS2/Isaac Sim, 또는 테스트 API 호출자)이 명시한 메시지 생성 시각. */
    private LocalDateTime messageAt;
    /** Spring Boot가 이 상태를 실제로 수신·반영한 시각. */
    private LocalDateTime receivedAt;

    public VehicleCurrentStatus() {
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public VehicleStatus getStatus() {
        return status;
    }

    public void setStatus(VehicleStatus status) {
        this.status = status;
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

    public String getPositionFrame() { return positionFrame; }

    public void setPositionFrame(String positionFrame) { this.positionFrame = positionFrame; }

    public Double getHeading() {
        return heading;
    }

    public void setHeading(Double heading) {
        this.heading = heading;
    }

    public Double getSpeed() {
        return speed;
    }

    public void setSpeed(Double speed) {
        this.speed = speed;
    }

    public Boolean getHasCargo() {
        return hasCargo;
    }

    public void setHasCargo(Boolean hasCargo) {
        this.hasCargo = hasCargo;
    }

    public String getCargoId() {
        return cargoId;
    }

    public void setCargoId(String cargoId) {
        this.cargoId = cargoId;
    }

    public LocalDateTime getMessageAt() {
        return messageAt;
    }

    public void setMessageAt(LocalDateTime messageAt) {
        this.messageAt = messageAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

}
