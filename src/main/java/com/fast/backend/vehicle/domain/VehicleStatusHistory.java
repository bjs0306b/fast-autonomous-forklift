package com.fast.backend.vehicle.domain;

import java.time.LocalDateTime;

/**
 * {@code vehicle_status_history} 테이블 한 행 — 정상 처리된 차량 상태 메시지 한 건의 이력이다.
 *
 * <p>{@link VehicleCurrentStatus}와 역할이 다르다. 그쪽은 차량당 <b>최신 1행</b>을 upsert로 덮어써
 * "지금 이 차량이 어떤 상태인가"에 답하고, 이 클래스는 <b>append-only</b>로 쌓여 "이 차량이 언제 어떤
 * 상태였는가"에 답한다. 두 저장은 같은 트랜잭션에서 함께 일어난다
 * ({@link com.fast.backend.vehicle.service.VehicleStatusService#updateCurrentStatus}).
 *
 * <p>상태 메시지({@code forklift/{vehicleId}/status})가 실제로 싣고 오는 값만 담는다 — 위치·속도·방향은
 * 별도 토픽·별도 처리 경로라 이 이력에 포함하지 않는다.
 *
 * <p>모든 시각은 <b>Asia/Seoul 벽시계 기준 {@link LocalDateTime}</b>이다
 * ({@link com.fast.backend.common.time.CommunicationTime} 참고). 통신 계층의 {@code OffsetDateTime}
 * (+09:00)과는 Service 경계에서 변환된다.
 */
public class VehicleStatusHistory {

    private Long historyId;
    private String vehicleId;
    private VehicleStatus status;
    private Integer battery;
    /** 송신 측(ROS2/Isaac Sim, 또는 테스트 API 호출자)이 명시한 메시지 생성 시각. 없을 수 있다. */
    private LocalDateTime messageAt;
    /** Spring Boot가 이 상태를 실제로 수신·반영한 시각. */
    private LocalDateTime receivedAt;
    /** DB가 이력 행을 만든 시각. 컬럼 기본값으로 채워진다. */
    private LocalDateTime createdAt;

    public VehicleStatusHistory() {
    }

    public Long getHistoryId() {
        return historyId;
    }

    public void setHistoryId(Long historyId) {
        this.historyId = historyId;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
