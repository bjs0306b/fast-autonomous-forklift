package com.fast.backend.embedded.service;

import com.fast.backend.embedded.domain.EmbeddedCommandStatus;
import com.fast.backend.embedded.domain.EmbeddedCommandType;
import com.fast.backend.embedded.domain.EmbeddedVehicleCommand;
import com.fast.backend.embedded.dto.EmbeddedCommandResultMessage;
import com.fast.backend.embedded.mapper.EmbeddedVehicleCommandMapper;
import com.fast.backend.vehicle.websocket.EmbeddedCommandResultEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code forklift/{id}/command-result} 메시지를 검증·반영한다(prompt29.md 7장·16장
 * {@code EmbeddedCommandResultService}, 20장 비상정지 처리 원칙).
 *
 * <p>{@link EmbeddedCommandStatus#canTransitionTo}가 "잘못된 상태 전이 거부"와 "중복 결과 수신"을
 * 동시에 처리한다 — 이미 종료 상태(SUCCESS 등)인 명령에 같은 결과가 다시 오면
 * {@code isTerminal() == true}라 전이 자체가 거부되므로, 중복 결과를 위한 별도 판정 코드를 추가하지
 * 않았다.
 */
@Service
public class EmbeddedCommandResultService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedCommandResultService.class);

    private final EmbeddedVehicleCommandMapper commandMapper;
    private final VehicleWebSocketBroadcaster broadcaster;

    public EmbeddedCommandResultService(EmbeddedVehicleCommandMapper commandMapper, VehicleWebSocketBroadcaster broadcaster) {
        this.commandMapper = commandMapper;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public void handleResult(EmbeddedCommandResultMessage message) {
        log.debug("Embedded command result received: commandId={}, forkliftId={}, result={}",
                message.commandId(), message.forkliftId(), message.result());
        try {
            if (!isValid(message)) {
                return;
            }

            EmbeddedVehicleCommand existing = commandMapper.findByCommandId(message.commandId()).orElse(null);
            if (existing == null) {
                log.warn("Embedded command result skipped, commandId not found: commandId={}", message.commandId());
                return;
            }
            if (!existing.getForkliftId().equals(message.forkliftId())) {
                log.warn("Embedded command result skipped, forkliftId mismatch: commandId={}, expected={}, actual={}",
                        message.commandId(), existing.getForkliftId(), message.forkliftId());
                return;
            }
            EmbeddedCommandType resultCommandType = EmbeddedCommandType.fromRaw(message.command()).orElse(null);
            if (resultCommandType == null || resultCommandType != existing.getCommand()) {
                log.warn("Embedded command result skipped, command mismatch: commandId={}, expected={}, actual={}",
                        message.commandId(), existing.getCommand(), message.command());
                return;
            }
            EmbeddedCommandStatus newStatus = EmbeddedCommandStatus.fromResultRaw(message.result()).orElse(null);
            if (newStatus == null) {
                log.warn("Embedded command result skipped, unknown result value: commandId={}, result={}",
                        message.commandId(), message.result());
                return;
            }
            if (!existing.getStatus().canTransitionTo(newStatus)) {
                // 종료 상태에서의 재수신(중복 결과)도 이 분기로 걸러진다.
                log.warn("Embedded command result skipped, invalid state transition: commandId={}, {} -> {}",
                        message.commandId(), existing.getStatus(), newStatus);
                return;
            }

            applyResult(existing, message, newStatus);
            commandMapper.update(existing);

            EmbeddedCommandResultEventData data = toEventData(message);
            broadcaster.broadcastEmbeddedCommandResult(message.forkliftId(), data, message.completedAt());
        } catch (RuntimeException e) {
            log.error("Embedded command result processing failed unexpectedly: commandId={}, error={}",
                    message.commandId(), e.getMessage());
        }
    }

    private boolean isValid(EmbeddedCommandResultMessage message) {
        if (message.commandId() == null || message.commandId().isBlank()) {
            log.warn("Embedded command result skipped: commandId is null or blank");
            return false;
        }
        if (message.forkliftId() == null || message.forkliftId().isBlank()) {
            log.warn("Embedded command result skipped: forkliftId is null or blank, commandId={}", message.commandId());
            return false;
        }
        if (message.command() == null || message.command().isBlank()) {
            log.warn("Embedded command result skipped: command is null or blank, commandId={}", message.commandId());
            return false;
        }
        if (message.result() == null || message.result().isBlank()) {
            log.warn("Embedded command result skipped: result is null or blank, commandId={}", message.commandId());
            return false;
        }
        if (message.completedAt() == null) {
            log.warn("Embedded command result skipped: completedAt is null, commandId={}", message.commandId());
            return false;
        }
        return true;
    }

    private void applyResult(EmbeddedVehicleCommand existing, EmbeddedCommandResultMessage message, EmbeddedCommandStatus newStatus) {
        existing.setStatus(newStatus);
        existing.setCompletedAt(message.completedAt());
        existing.setErrorCode(message.errorCode());
        existing.setResultMessage(message.message());
        existing.setStoppedActions(joinStoppedActions(message.stoppedActions()));
        existing.setEmergencyStopApplied(message.emergencyStopApplied());
        existing.setRequiresReset(message.requiresReset());
        existing.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 백엔드가 실제 하드웨어 중단 범위를 추측하지 않는다(20장) — MCU/ROS2가 보낸 값을 검증·필터링 없이
     * 그대로 쉼표로 이어붙여 보존한다.
     */
    private String joinStoppedActions(List<String> stoppedActions) {
        if (stoppedActions == null || stoppedActions.isEmpty()) {
            return null;
        }
        return String.join(",", stoppedActions);
    }

    private EmbeddedCommandResultEventData toEventData(EmbeddedCommandResultMessage message) {
        return new EmbeddedCommandResultEventData(
                message.commandId(),
                message.forkliftId(),
                message.command(),
                message.result(),
                message.forkState(),
                message.limitBottom(),
                message.emergencyStopApplied(),
                message.stoppedActions(),
                message.requiresReset(),
                message.errorCode(),
                message.message(),
                message.completedAt());
    }
}
