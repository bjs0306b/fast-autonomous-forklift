package com.fast.backend.vehicle.domain;

import java.time.LocalDateTime;

/**
 * {@code vehicle_current_status} 테이블 한 행 — 차량당 최신 상태 1행만 유지한다(vehicle_id가 PK).
 *
 * <p>battery/position_x/position_y/heading/speed는 모두 nullable이다 — 송신 측이 해당 값을 제공하지
 * 않을 수 있다. {@code heading}의 단위는 prompt32.md 1장 5번 확정에 따라 <b>degree, [0,360)</b>다.
 *
 * <p>{@code forkHeight}/{@code hasCargo}/{@code cargoId}/{@code footprintLength}/{@code footprintWidth}는
 * Isaac Sim 상태 메시지에만 들어오는 확장 필드다(prompt32.md 1장 4번 확정으로 DB 저장 대상이 됐다).
 * ROS2 일반 상태 메시지에는 이 값이 없으므로, 그 경우 <b>기존 값을 그대로 보존</b>한다 — 병합 정책은
 * {@link com.fast.backend.vehicle.service.VehicleStatusService} Javadoc 참고.
 *
 * <p>모든 시각은 <b>Asia/Seoul 벽시계 기준 {@link LocalDateTime}</b>이다
 * ({@link com.fast.backend.common.time.CommunicationTime} 참고). 통신 계층의 {@code OffsetDateTime}
 * (+09:00)과는 Service 경계에서 변환된다.
 */
public class VehicleCurrentStatus {

    private String vehicleId;
    private VehicleStatus status;
    private Integer battery;
    private Double positionX;
    private Double positionY;
    /** degree, [0,360) 정규화된 값(prompt32.md 1장 5번 확정). */
    private Double heading;
    private Double speed;
    /** Isaac 확장: 포크 높이(m). ROS2 상태 메시지에는 없다. */
    private Double forkHeight;
    /** Isaac 확장: 화물 적재 여부. */
    private Boolean hasCargo;
    /** Isaac 확장: 적재된 화물 식별자. */
    private String cargoId;
    /** Isaac 확장: 차량 footprint 길이(m). */
    private Double footprintLength;
    /** Isaac 확장: 차량 footprint 폭(m). */
    private Double footprintWidth;
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

    public Double getForkHeight() {
        return forkHeight;
    }

    public void setForkHeight(Double forkHeight) {
        this.forkHeight = forkHeight;
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

    public Double getFootprintLength() {
        return footprintLength;
    }

    public void setFootprintLength(Double footprintLength) {
        this.footprintLength = footprintLength;
    }

    public Double getFootprintWidth() {
        return footprintWidth;
    }

    public void setFootprintWidth(Double footprintWidth) {
        this.footprintWidth = footprintWidth;
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
