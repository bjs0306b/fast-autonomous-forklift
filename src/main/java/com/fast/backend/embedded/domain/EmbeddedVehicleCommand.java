package com.fast.backend.embedded.domain;

import java.time.LocalDateTime;

/**
 * {@code embedded_vehicle_command} 테이블 한 행(prompt29.md 17장). {@code stoppedActions}는
 * {@code loadBalance.direction}(prompt26.md 저장 방식과 동일한 이유 — JSON 컬럼을 쓴 적이 없는
 * 프로젝트라 새로 도입하지 않고, 자식 테이블을 두기엔 원소가 최대 3개뿐이라 과하다)를 쉼표로 이어붙인
 * 문자열로 저장한다.
 */
public class EmbeddedVehicleCommand {

    private Long id;
    private String commandId;
    private String forkliftId;
    private EmbeddedCommandType command;
    private String reason;
    private EmbeddedCommandStatus status;
    private LocalDateTime issuedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime completedAt;
    private String errorCode;
    private String resultMessage;
    private String stoppedActions;
    private Boolean emergencyStopApplied;
    private Boolean requiresReset;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public EmbeddedVehicleCommand() {
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

    public String getForkliftId() {
        return forkliftId;
    }

    public void setForkliftId(String forkliftId) {
        this.forkliftId = forkliftId;
    }

    public EmbeddedCommandType getCommand() {
        return command;
    }

    public void setCommand(EmbeddedCommandType command) {
        this.command = command;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public EmbeddedCommandStatus getStatus() {
        return status;
    }

    public void setStatus(EmbeddedCommandStatus status) {
        this.status = status;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(LocalDateTime issuedAt) {
        this.issuedAt = issuedAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getResultMessage() {
        return resultMessage;
    }

    public void setResultMessage(String resultMessage) {
        this.resultMessage = resultMessage;
    }

    public String getStoppedActions() {
        return stoppedActions;
    }

    public void setStoppedActions(String stoppedActions) {
        this.stoppedActions = stoppedActions;
    }

    public Boolean getEmergencyStopApplied() {
        return emergencyStopApplied;
    }

    public void setEmergencyStopApplied(Boolean emergencyStopApplied) {
        this.emergencyStopApplied = emergencyStopApplied;
    }

    public Boolean getRequiresReset() {
        return requiresReset;
    }

    public void setRequiresReset(Boolean requiresReset) {
        this.requiresReset = requiresReset;
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
