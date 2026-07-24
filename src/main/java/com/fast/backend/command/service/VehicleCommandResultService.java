package com.fast.backend.command.service;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.command.dto.VehicleCommandResultMessage;
import com.fast.backend.command.mapper.VehicleCommandMapper;
import com.fast.backend.command.websocket.VehicleCommandResultEventData;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code forklift/{vehicleId}/command-result} 메시지를 검증·반영한다(prompt32.md 1장 12번 확정 규격).
 *
 * <p><b>확정된 필수 검증 순서</b>
 * <ol>
 *   <li>토픽의 vehicleId와 payload vehicleId 일치 — {@code MqttMessageRouter}가 라우팅 단계에서 수행</li>
 *   <li>commandId 존재(발행 기록이 있는 명령인지)</li>
 *   <li>기존 발행 명령과 vehicleId 일치</li>
 *   <li>targetSystem 일치</li>
 *   <li>commandCategory 일치</li>
 *   <li>command 일치</li>
 *   <li>허용된 상태 전이인지 확인</li>
 *   <li>중복 종료 결과 처리</li>
 * </ol>
 * 7·8번은 {@link VehicleCommandStatus#canTransitionTo}가 함께 처리한다 — 이미 종료 상태인 명령에 같은
 * 결과가 다시 오면 전이 자체가 거부되므로 중복 판정 코드를 따로 두지 않았다.
 *
 * <p><b>하위 호환(prompt32.md 3장 5번)</b>: {@code targetSystem}/{@code commandCategory}가 없는 구
 * 형식 결과도 수신한다. 이 경우 4·5번 검증만 건너뛰고 <b>deprecated 경고 로그</b>를 남긴 뒤 나머지
 * 검증은 그대로 수행한다. WebSocket으로 내보낼 때는 발행 당시 DB에 저장해 둔 값으로 채운다.
 */
@Service
public class VehicleCommandResultService {

    private static final Logger log = LoggerFactory.getLogger(VehicleCommandResultService.class);

    private final VehicleCommandMapper commandMapper;
    private final VehicleWebSocketBroadcaster broadcaster;

    public VehicleCommandResultService(VehicleCommandMapper commandMapper, VehicleWebSocketBroadcaster broadcaster) {
        this.commandMapper = commandMapper;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public void handleResult(VehicleCommandResultMessage message) {
        log.debug("Vehicle command result received: commandId={}, vehicleId={}, result={}",
                message.commandId(), message.vehicleId(), message.result());
        try {
            if (!hasRequiredFields(message)) {
                return;
            }

            // (2) commandId 존재
            VehicleCommand existing = commandMapper.findByCommandId(message.commandId()).orElse(null);
            if (existing == null) {
                log.warn("Command result skipped, commandId not found: commandId={}", message.commandId());
                return;
            }

            // (3) vehicleId 일치
            if (!existing.getVehicleId().equals(message.vehicleId())) {
                log.warn("Command result skipped, vehicleId mismatch: commandId={}, expected={}, actual={}",
                        message.commandId(), existing.getVehicleId(), message.vehicleId());
                return;
            }

            // (4)(5) targetSystem / commandCategory 일치 — 구 형식이면 건너뛴다(하위 호환)
            if (message.isLegacyFormat()) {
                log.warn("Command result received in DEPRECATED legacy format (targetSystem/commandCategory "
                                + "missing). Sender should migrate to the unified command-result envelope: "
                                + "commandId={}, vehicleId={}",
                        message.commandId(), message.vehicleId());
            } else {
                if (!isTargetSystemMatched(existing, message) || !isCategoryMatched(existing, message)) {
                    return;
                }
            }

            // (6) command 일치
            VehicleCommandType resultCommandType = VehicleCommandType.fromRaw(message.command()).orElse(null);
            if (resultCommandType == null || resultCommandType != existing.getCommand()) {
                log.warn("Command result skipped, command mismatch: commandId={}, expected={}, actual={}",
                        message.commandId(), existing.getCommand(), message.command());
                return;
            }

            // (7)(8) 상태 전이 + 중복 종료 결과
            VehicleCommandStatus newStatus = VehicleCommandStatus.fromResultRaw(message.result()).orElse(null);
            if (newStatus == null) {
                log.warn("Command result skipped, unknown result value: commandId={}, result={}",
                        message.commandId(), message.result());
                return;
            }
            if (!existing.getStatus().canTransitionTo(newStatus)) {
                log.warn("Command result skipped, invalid state transition (or duplicate terminal result): "
                                + "commandId={}, {} -> {}",
                        message.commandId(), existing.getStatus(), newStatus);
                return;
            }

            applyResult(existing, message, newStatus);
            commandMapper.update(existing);

            VehicleCommandResultEventData data = toEventData(existing, message);
            broadcaster.broadcastCommandResult(existing.getVehicleId(), data, message.completedAt());
        } catch (RuntimeException e) {
            log.error("Command result processing failed unexpectedly: commandId={}, error={}",
                    message.commandId(), e.getMessage());
        }
    }

    private boolean isTargetSystemMatched(VehicleCommand existing, VehicleCommandResultMessage message) {
        VehicleCommandTargetSystem resultTargetSystem =
                VehicleCommandTargetSystem.fromRaw(message.targetSystem()).orElse(null);
        if (resultTargetSystem == null || resultTargetSystem != existing.getTargetSystem()) {
            log.warn("Command result skipped, targetSystem mismatch: commandId={}, expected={}, actual={}",
                    message.commandId(), existing.getTargetSystem(), message.targetSystem());
            return false;
        }
        return true;
    }

    private boolean isCategoryMatched(VehicleCommand existing, VehicleCommandResultMessage message) {
        VehicleCommandCategory resultCategory =
                VehicleCommandCategory.fromRaw(message.commandCategory()).orElse(null);
        if (resultCategory == null || resultCategory != existing.getCommandCategory()) {
            log.warn("Command result skipped, commandCategory mismatch: commandId={}, expected={}, actual={}",
                    message.commandId(), existing.getCommandCategory(), message.commandCategory());
            return false;
        }
        return true;
    }

    private boolean hasRequiredFields(VehicleCommandResultMessage message) {
        if (message.commandId() == null || message.commandId().isBlank()) {
            log.warn("Command result skipped: commandId is null or blank");
            return false;
        }
        if (message.vehicleId() == null || message.vehicleId().isBlank()) {
            log.warn("Command result skipped: vehicleId is null or blank, commandId={}", message.commandId());
            return false;
        }
        if (message.command() == null || message.command().isBlank()) {
            log.warn("Command result skipped: command is null or blank, commandId={}", message.commandId());
            return false;
        }
        if (message.result() == null || message.result().isBlank()) {
            log.warn("Command result skipped: result is null or blank, commandId={}", message.commandId());
            return false;
        }
        if (message.completedAt() == null) {
            log.warn("Command result skipped: completedAt is null, commandId={}", message.commandId());
            return false;
        }
        return true;
    }

    private void applyResult(
            VehicleCommand existing, VehicleCommandResultMessage message, VehicleCommandStatus newStatus) {
        existing.setStatus(newStatus);
        existing.setCompletedAt(CommunicationTime.toLocal(message.completedAt()));
        existing.setErrorCode(message.errorCode());
        existing.setResultMessage(message.message());
        existing.setStoppedActions(joinStoppedActions(message.stoppedActions()));
        existing.setEmergencyStopApplied(message.emergencyStopApplied());
        existing.setRequiresReset(message.requiresReset());
        existing.setUpdatedAt(LocalDateTime.now(CommunicationTime.ZONE));
    }

    /**
     * 백엔드가 실제 하드웨어 중단 범위를 추측하지 않는다 — ROS2/MCU가 보낸 값을 검증·필터링 없이
     * 그대로 쉼표로 이어붙여 보존한다.
     */
    private String joinStoppedActions(List<String> stoppedActions) {
        if (stoppedActions == null || stoppedActions.isEmpty()) {
            return null;
        }
        return String.join(",", stoppedActions);
    }

    /**
     * 구 형식으로 수신돼 targetSystem/commandCategory가 없더라도, 발행 당시 DB에 저장해 둔 값으로 채워
     * <b>프론트에는 항상 완전한 envelope</b>가 나가도록 한다.
     */
    private VehicleCommandResultEventData toEventData(
            VehicleCommand existing, VehicleCommandResultMessage message) {
        return new VehicleCommandResultEventData(
                message.commandId(),
                existing.getVehicleId(),
                message.command(),
                existing.getTargetSystem() != null ? existing.getTargetSystem().name() : null,
                existing.getCommandCategory() != null ? existing.getCommandCategory().name() : null,
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
