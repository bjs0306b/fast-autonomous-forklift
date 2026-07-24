package com.fast.backend.command.domain;

import java.time.LocalDateTime;

/**
 * {@code embedded_vehicle_command} 테이블 한 행(구 {@code EmbeddedVehicleCommand}를 통합 명령 도메인으로
 * 이관하고 prompt32.md 확정 필드를 추가했다).
 *
 * <p><b>테이블·컬럼 이름을 바꾸지 않은 이유</b>: 이제 이 테이블은 임베디드 명령만이 아니라 ROS2 이동
 * 명령까지 모두 저장하므로 이름이 도메인과 어긋난다. 그럼에도 {@code embedded_vehicle_command}와
 * {@code forklift_id}를 유지한 것은 prompt32.md 3장 3번이 "기존 forklift_id 유지 정책"을 명시적으로
 * 허용했고, 이름 변경은 운영 DB 데이터 이관 위험을 만드는 데 비해 얻는 게 이름 일관성뿐이기 때문이다.
 * <b>애플리케이션 계층(이 클래스, DTO, JSON)은 확정 규격대로 {@code vehicleId}를 쓰고</b>, Mapper XML이
 * {@code forklift_id} 컬럼과 매핑한다.
 *
 * <p>{@code stoppedActions}는 쉼표 구분 문자열로 저장한다(JSON 컬럼 미사용 원칙, 원소 최대 3개).
 * {@code payloadJson}은 명령별로 구조가 다른 payload를 직렬화한 JSON <b>문자열</b>이다 — MySQL JSON
 * 컬럼 타입을 쓰지 않은 이유는 {@code schema.sql} 주석 참고(H2 테스트 호환).
 *
 * <p>모든 시각은 Asia/Seoul 벽시계 {@link LocalDateTime}이다
 * ({@link com.fast.backend.common.time.CommunicationTime}).
 */
public class VehicleCommand {

    private Long id;
    private String commandId;
    private String vehicleId;
    private VehicleCommandType command;
    private VehicleCommandTargetSystem targetSystem;
    private VehicleCommandCategory commandCategory;
    private String payloadJson;
    private String reason;
    private VehicleCommandStatus status;
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

    public VehicleCommand() {
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

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public VehicleCommandType getCommand() {
        return command;
    }

    public void setCommand(VehicleCommandType command) {
        this.command = command;
    }

    public VehicleCommandTargetSystem getTargetSystem() {
        return targetSystem;
    }

    public void setTargetSystem(VehicleCommandTargetSystem targetSystem) {
        this.targetSystem = targetSystem;
    }

    public VehicleCommandCategory getCommandCategory() {
        return commandCategory;
    }

    public void setCommandCategory(VehicleCommandCategory commandCategory) {
        this.commandCategory = commandCategory;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public VehicleCommandStatus getStatus() {
        return status;
    }

    public void setStatus(VehicleCommandStatus status) {
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
