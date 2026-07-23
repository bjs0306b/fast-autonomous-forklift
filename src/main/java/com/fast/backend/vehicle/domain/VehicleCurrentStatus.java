package com.fast.backend.vehicle.domain;

import java.time.LocalDateTime;

/**
 * {@code vehicle_current_status} 테이블 한 행 — 차량당 최신 상태 1행만 유지한다(vehicle_id가 PK).
 *
 * <p>battery/position_x/position_y/heading/speed는 모두 nullable이다. 배터리 제공 여부, 위치 JSON 구조,
 * heading의 단위(degree/radian)가 아직 팀 합의 전이기 때문이다(prompt16.md 6장). 이 클래스에 필드를
 * 추가하는 것은 쉽지만, 일단 값이 들어오기 시작하면 DB 컬럼을 되돌리기는 어려우므로 합의 전까지는
 * 지금 이 5개 필드 밖의 값을 저장하지 않는다.
 */
public class VehicleCurrentStatus {

    private String vehicleId;
    private VehicleStatus status;
    private Integer battery;
    private Double positionX;
    private Double positionY;
    /** degree/radian 단위 미확정(팀 합의 필요). 현재는 원시 double 값을 그대로 저장한다. */
    private Double heading;
    private Double speed;
    /** 송신 측(ROS2/Isaac Sim, 또는 테스트 API 호출자)이 명시한 메시지 생성 시각. */
    private LocalDateTime messageAt;
    /** Spring Boot가 이 상태를 실제로 수신·반영한 시각. */
    private LocalDateTime receivedAt;
    private LocalDateTime updatedAt;

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

    public Integer getBattery() {
        return battery;
    }

    public void setBattery(Integer battery) {
        this.battery = battery;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
