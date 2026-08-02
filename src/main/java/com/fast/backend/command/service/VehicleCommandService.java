package com.fast.backend.command.service;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.command.dto.VehicleCommandDestination;
import com.fast.backend.command.dto.VehicleCommandMessage;
import com.fast.backend.command.dto.VehicleCommandPayload;
import com.fast.backend.command.dto.VehicleCommandRequest;
import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.command.mapper.VehicleCommandMapper;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class VehicleCommandService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 200;
    private static final Set<String> ALLOWED_FRAMES = Set.of("map", "odom");

    private final VehicleMapper vehicleMapper;
    private final VehicleCommandMapper commandMapper;
    private final VehicleCommandPublisher publisher;
    private final TransportTaskMapper transportTaskMapper;

    public VehicleCommandService(
            VehicleMapper vehicleMapper,
            VehicleCommandMapper commandMapper,
            VehicleCommandPublisher publisher,
            TransportTaskMapper transportTaskMapper) {
        this.vehicleMapper = vehicleMapper;
        this.commandMapper = commandMapper;
        this.publisher = publisher;
        this.transportTaskMapper = transportTaskMapper;
    }

    @Transactional
    public VehicleCommandResponse issueCommand(String vehicleId, VehicleCommandRequest request) {
        Vehicle vehicle = vehicleMapper.findByVehicleId(vehicleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND));
        if (!vehicle.isActive()) {
            throw new BusinessException(ErrorCode.VEHICLE_INACTIVE);
        }

        VehicleCommandType command = VehicleCommandType.fromRaw(request.command())
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_TYPE_INVALID));
        VehicleCommandTargetSystem target = resolveTarget(request.targetSystem(), command);
        VehicleCommandCategory category = resolveCategory(request.commandCategory(), command);
        VehicleCommandPayload payload = buildPayload(command, request.destination());
        TransportTask linkedTask = resolveLinkedTask(request.taskId(), vehicleId, command);
        OffsetDateTime issuedAt = CommunicationTime.nowOffset();

        VehicleCommand entity = new VehicleCommand();
        entity.setCommandId(UUID.randomUUID().toString());
        entity.setTaskId(linkedTask == null ? null : linkedTask.getId());
        entity.setVehicleId(vehicleId);
        entity.setCommand(command);
        entity.setTargetSystem(target);
        entity.setStatus(VehicleCommandStatus.PENDING);
        entity.setCreatedAt(CommunicationTime.toLocal(issuedAt));
        commandMapper.insert(entity);

        try {
            publisher.publish(new VehicleCommandMessage(
                    entity.getCommandId(), vehicleId, target, category, command,
                    payload, issuedAt));
            entity.setStatus(VehicleCommandStatus.PUBLISHED);
        } catch (RuntimeException exception) {
            entity.setStatus(VehicleCommandStatus.PUBLISH_FAILED);
            entity.setResultMessage(exception.getMessage());
            if (linkedTask != null) {
                transportTaskMapper.updateStatusIfCurrent(
                        linkedTask.getId(), TaskStatus.MOVING_TO_PICKUP, TaskStatus.FAILED,
                        null, CommunicationTime.toLocal(issuedAt));
            }
        }
        commandMapper.update(entity);
        return toResponse(entity);
    }

    private TransportTask resolveLinkedTask(String taskCode, String vehicleId, VehicleCommandType command) {
        if (taskCode == null || taskCode.isBlank()) {
            return null;
        }
        if (command != VehicleCommandType.MOVE) {
            throw new BusinessException(ErrorCode.COMMAND_COMBINATION_INVALID,
                    "taskId는 측정 위치로 이동하는 MOVE 명령에만 연결할 수 있습니다.");
        }
        TransportTask task = transportTaskMapper.findByTaskCode(taskCode.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSPORT_TASK_NOT_FOUND));
        if (!vehicleId.equals(task.getVehicleId())) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_AVAILABLE,
                    "운반 작업에 배정된 차량과 명령 대상 차량이 다릅니다.");
        }
        if (task.getStatus() != TaskStatus.ASSIGNED) {
            throw new BusinessException(ErrorCode.INVALID_TASK_STATUS_TRANSITION,
                    "ASSIGNED 상태 작업만 측정 위치 이동 명령을 시작할 수 있습니다.");
        }
        // station_state의 단일 행을 잠가 동시 MOVE 요청 두 건이 모두 빈 측정 차선을 보는 경합을 막는다.
        transportTaskMapper.lockMeasurementLane();
        if (transportTaskMapper.existsMeasurementLaneBusy()) {
            throw new BusinessException(ErrorCode.STATION_ALREADY_OCCUPIED,
                    "다른 차량이 측정 위치로 이동 중이거나 측정 중입니다.");
        }
        LocalDateTime now = CommunicationTime.nowLocal();
        if (transportTaskMapper.updateStatusIfCurrent(
                task.getId(), TaskStatus.ASSIGNED, TaskStatus.MOVING_TO_PICKUP,
                now, null) != 1) {
            throw new BusinessException(ErrorCode.INVALID_TASK_STATUS_TRANSITION);
        }
        task.setStatus(TaskStatus.MOVING_TO_PICKUP);
        task.setStartedAt(now);
        return task;
    }

    @Transactional(readOnly = true)
    public VehicleCommandResponse findByCommandId(String vehicleId, String commandId) {
        VehicleCommand command = commandMapper.findByCommandId(commandId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_NOT_FOUND));
        if (!vehicleId.equals(command.getVehicleId())) {
            throw new BusinessException(ErrorCode.COMMAND_NOT_FOUND);
        }
        return toResponse(command);
    }

    @Transactional(readOnly = true)
    public List<VehicleCommandResponse> findRecentByVehicleId(String vehicleId, int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.COMMAND_LIMIT_INVALID);
        }
        if (!vehicleMapper.existsByVehicleId(vehicleId)) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_FOUND);
        }
        return commandMapper.findRecentByVehicleId(vehicleId, limit).stream().map(this::toResponse).toList();
    }

    private VehicleCommandTargetSystem resolveTarget(String raw, VehicleCommandType command) {
        if (raw == null || raw.isBlank()) {
            return command.requiredTargetSystem();
        }
        VehicleCommandTargetSystem target = VehicleCommandTargetSystem.fromRaw(raw)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_COMBINATION_INVALID));
        if (target != command.requiredTargetSystem()) {
            throw new BusinessException(ErrorCode.COMMAND_COMBINATION_INVALID);
        }
        return target;
    }

    private VehicleCommandCategory resolveCategory(String raw, VehicleCommandType command) {
        if (raw == null || raw.isBlank()) {
            return command.requiredCategory();
        }
        VehicleCommandCategory category = VehicleCommandCategory.fromRaw(raw)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_COMBINATION_INVALID));
        if (category != command.requiredCategory()) {
            throw new BusinessException(ErrorCode.COMMAND_COMBINATION_INVALID);
        }
        return category;
    }

    private VehicleCommandPayload buildPayload(
            VehicleCommandType command, VehicleCommandDestination destination) {
        if (!command.isDestinationRequired()) {
            return VehicleCommandPayload.empty();
        }
        if (destination == null || destination.x() == null || destination.y() == null
                || !Double.isFinite(destination.x()) || !Double.isFinite(destination.y())) {
            throw new BusinessException(ErrorCode.COMMAND_DESTINATION_INVALID);
        }
        String frame = destination.frameId() == null || destination.frameId().isBlank()
                ? "map" : destination.frameId().trim();
        if (!ALLOWED_FRAMES.contains(frame)) {
            throw new BusinessException(ErrorCode.COMMAND_DESTINATION_INVALID);
        }
        Double heading = destination.heading();
        if (heading != null && !Double.isFinite(heading)) {
            throw new BusinessException(ErrorCode.COMMAND_DESTINATION_INVALID);
        }
        if (heading != null) {
            heading = ((heading % 360.0) + 360.0) % 360.0;
        }
        return VehicleCommandPayload.ofDestination(
                new VehicleCommandDestination(destination.x(), destination.y(), heading, frame));
    }

    private VehicleCommandResponse toResponse(VehicleCommand entity) {
        return new VehicleCommandResponse(
                entity.getCommandId(), entity.getVehicleId(),
                entity.getCommand() == null ? null : entity.getCommand().name(),
                entity.getTargetSystem() == null ? null : entity.getTargetSystem().name(),
                entity.getStatus() == null ? null : entity.getStatus().name(),
                CommunicationTime.toOffset(entity.getCompletedAt()), entity.getResultMessage(),
                CommunicationTime.toOffset(entity.getCreatedAt()));
    }
}
