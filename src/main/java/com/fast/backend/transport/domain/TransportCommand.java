package com.fast.backend.transport.domain;

import java.time.LocalDateTime;

/**
 * 차량에 발행한 MQTT 운반 명령(prompt48.md 3·6장). {@link TransportTask}(업무 단위)와 분리된 명령 단위로,
 * command_id로 차량이 회신하는 command-result를 역추적한다. 하나의 Task에 여러 command가 연결될 수 있다.
 *
 * <p>{@code stage}는 ROS2가 단계별 결과를 줄 때만 채워지며 현재 규격이 미확정이라 nullable이다.
 * {@code payload}는 발행한 MQTT JSON 원문(감사·재발행 참고용).
 */
public class TransportCommand {

    private Long id;
    private String commandId;
    private Long taskId;
    private String taskCode;
    private String vehicleId;
    private String commandType;
    private String stage;
    private TransportCommandStatus status;
    private String payload;
    private String failureReason;
    private LocalDateTime publishedAt;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public TransportCommand() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getTaskCode() {
        return taskCode;
    }

    public void setTaskCode(String taskCode) {
        this.taskCode = taskCode;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public String getCommandType() {
        return commandType;
    }

    public void setCommandType(String commandType) {
        this.commandType = commandType;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public TransportCommandStatus getStatus() {
        return status;
    }

    public void setStatus(TransportCommandStatus status) {
        this.status = status;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public LocalDateTime getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(LocalDateTime acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
