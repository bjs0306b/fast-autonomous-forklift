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
import com.fast.backend.station.service.StationMeasurementRequestWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ROS2 MQTT 브리지가 보낸 명령 결과를 검증하고 저장한다. */
@Service
public class VehicleCommandResultService {

    private static final Logger log = LoggerFactory.getLogger(VehicleCommandResultService.class);

    private final VehicleCommandMapper commandMapper;
    private final VehicleWebSocketBroadcaster broadcaster;
    private final StationMeasurementRequestWorkflow measurementRequestWorkflow;

    public VehicleCommandResultService(
            VehicleCommandMapper commandMapper,
            VehicleWebSocketBroadcaster broadcaster,
            StationMeasurementRequestWorkflow measurementRequestWorkflow) {
        this.commandMapper = commandMapper;
        this.broadcaster = broadcaster;
        this.measurementRequestWorkflow = measurementRequestWorkflow;
    }

    @Transactional
    public void handleResult(VehicleCommandResultMessage message) {
        if (!hasRequiredFields(message)) {
            return;
        }
        VehicleCommand existing = commandMapper.findByCommandIdForUpdate(message.commandId()).orElse(null);
        if (existing == null) {
            log.warn("Command result ignored: unknown commandId={}", message.commandId());
            return;
        }
        if (!existing.getVehicleId().equals(message.vehicleId())) {
            log.warn("Command result ignored: vehicle mismatch, commandId={}, expected={}, actual={}",
                    message.commandId(), existing.getVehicleId(), message.vehicleId());
            return;
        }

        VehicleCommandType resultCommand = VehicleCommandType.fromRaw(message.command()).orElse(null);
        VehicleCommandTargetSystem resultTarget =
                VehicleCommandTargetSystem.fromRaw(message.targetSystem()).orElse(null);
        VehicleCommandCategory resultCategory =
                VehicleCommandCategory.fromRaw(message.commandCategory()).orElse(null);
        if (resultCommand != existing.getCommand()
                || resultTarget != existing.getTargetSystem()
                || resultCategory != existing.getCommand().requiredCategory()) {
            log.warn("Command result ignored: contract mismatch, commandId={}", message.commandId());
            return;
        }

        VehicleCommandStatus nextStatus = VehicleCommandStatus.fromResultRaw(message.result()).orElse(null);
        if (nextStatus == null || !existing.getStatus().canTransitionTo(nextStatus)) {
            log.warn("Command result ignored: invalid transition, commandId={}, current={}, requested={}",
                    message.commandId(), existing.getStatus(), message.result());
            return;
        }

        existing.setStatus(nextStatus);
        existing.setResultMessage(message.message());
        existing.setCompletedAt(nextStatus.isCompleted()
                ? CommunicationTime.toLocal(message.completedAt()) : null);
        commandMapper.update(existing);
        if (nextStatus.isCompleted()) {
            measurementRequestWorkflow.handleMoveResult(existing, nextStatus);
        }

        broadcaster.broadcastCommandResult(existing.getVehicleId(), new VehicleCommandResultEventData(
                message.commandId(), message.vehicleId(), message.targetSystem(), message.commandCategory(),
                message.command(), message.result(), message.message(), message.completedAt()), message.completedAt());
    }

    private boolean hasRequiredFields(VehicleCommandResultMessage message) {
        return message != null
                && hasText(message.commandId())
                && hasText(message.vehicleId())
                && hasText(message.targetSystem())
                && hasText(message.commandCategory())
                && hasText(message.command())
                && hasText(message.result())
                && message.completedAt() != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
