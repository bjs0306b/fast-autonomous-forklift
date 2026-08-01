package com.fast.backend.command.domain;

import java.time.LocalDateTime;

/** DB에 저장하는 차량 명령 상태. payload와 요청 부가 정보는 MQTT 메시지에만 포함한다. */
public class VehicleCommand {
    private String commandId;
    private Long taskId;
    private String vehicleId;
    private VehicleCommandType command;
    private VehicleCommandTargetSystem targetSystem;
    private VehicleCommandStatus status;
    private String resultMessage;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getVehicleId() { return vehicleId; }
    public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }
    public VehicleCommandType getCommand() { return command; }
    public void setCommand(VehicleCommandType command) { this.command = command; }
    public VehicleCommandTargetSystem getTargetSystem() { return targetSystem; }
    public void setTargetSystem(VehicleCommandTargetSystem targetSystem) { this.targetSystem = targetSystem; }
    public VehicleCommandStatus getStatus() { return status; }
    public void setStatus(VehicleCommandStatus status) { this.status = status; }
    public String getResultMessage() { return resultMessage; }
    public void setResultMessage(String resultMessage) { this.resultMessage = resultMessage; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
