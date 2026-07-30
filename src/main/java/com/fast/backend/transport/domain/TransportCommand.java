package com.fast.backend.transport.domain;

import java.time.LocalDateTime;

/**
 * {@code transport_command} 한 행 — FR-202 최종 스키마(prompt85).
 *
 * <p>7컬럼으로 줄었다. 제거된 것: 대리키 {@code id}(이제 {@code commandId} 가 PK),
 * {@code taskCode}(taskId 로 조인), {@code commandType}·{@code stage}·{@code payload}(발행 내용은
 * DB 에 남기지 않는다), {@code publishedAt}·{@code acknowledgedAt}(중간 시각 미보관), {@code updatedAt}.
 *
 * <p>발행 시각·ACK 시각이 사라져 "얼마나 걸렸는지"는 더 이상 DB 로 추적할 수 없다 — 상태 전이만 남는다.
 */
public class TransportCommand {

    private String commandId;
    private Long taskId;
    private String vehicleId;
    private TransportCommandStatus status;
    private String failureReason;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public TransportCommandStatus getStatus() {
        return status;
    }

    public void setStatus(TransportCommandStatus status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
