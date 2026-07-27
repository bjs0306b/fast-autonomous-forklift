package com.fast.backend.transport.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.storage.domain.StorageSlot;
import com.fast.backend.storage.mapper.StorageSlotMapper;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportCommand;
import com.fast.backend.transport.domain.TransportCommandStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.dto.TransportCommandMessage;
import com.fast.backend.transport.mapper.TransportCommandMapper;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.transport.service.TransportTaskService;
import com.fast.backend.transport.websocket.TransportTaskBroadcaster;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 디스패치의 <b>트랜잭션 경계 단계</b>(prompt48.md 8·9장). 오케스트레이터
 * {@link TransportDispatchService}가 "CREATED 커밋 → publish → PUBLISHED/PUBLISH_FAILED 커밋" 순서를
 * 지키도록, 각 DB 단계를 별도 {@code @Transactional} 메서드로 분리해 프록시 커밋 경계를 명확히 한다.
 */
@Service
public class TransportDispatchTxService {

    static final String COMMAND_TYPE_TRANSPORT = "TRANSPORT";

    private final TransportTaskMapper transportTaskMapper;
    private final TransportCommandMapper transportCommandMapper;
    private final StorageSlotMapper storageSlotMapper;
    private final VehicleMapper vehicleMapper;
    private final VehicleCurrentStatusMapper vehicleCurrentStatusMapper;
    private final TransportTaskService transportTaskService;
    private final TransportTaskBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    public TransportDispatchTxService(
            TransportTaskMapper transportTaskMapper, TransportCommandMapper transportCommandMapper,
            StorageSlotMapper storageSlotMapper, VehicleMapper vehicleMapper,
            VehicleCurrentStatusMapper vehicleCurrentStatusMapper, TransportTaskService transportTaskService,
            TransportTaskBroadcaster broadcaster, ObjectMapper objectMapper) {
        this.transportTaskMapper = transportTaskMapper;
        this.transportCommandMapper = transportCommandMapper;
        this.storageSlotMapper = storageSlotMapper;
        this.vehicleMapper = vehicleMapper;
        this.vehicleCurrentStatusMapper = vehicleCurrentStatusMapper;
        this.transportTaskService = transportTaskService;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    /** 검증 + TransportCommand CREATED insert(커밋). 발행할 MQTT 메시지를 반환한다. */
    @Transactional
    public TransportCommandMessage createCommand(String taskCode) {
        TransportTask task = transportTaskMapper.findByTaskCode(taskCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSPORT_TASK_NOT_FOUND,
                        "존재하지 않는 운반 작업입니다: " + taskCode));
        if (task.getStatus() != TaskStatus.ASSIGNED) {
            throw new BusinessException(ErrorCode.TASK_NOT_ASSIGNED,
                    "ASSIGNED 상태만 디스패치할 수 있습니다: " + taskCode + ", status=" + task.getStatus());
        }
        if (task.getVehicleId() == null || task.getVehicleId().isBlank()) {
            throw new BusinessException(ErrorCode.TASK_NOT_ASSIGNED, "vehicleId가 없는 작업입니다: " + taskCode);
        }
        if (!vehicleMapper.existsByVehicleId(task.getVehicleId())) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_FOUND, "등록되지 않은 차량입니다: " + task.getVehicleId());
        }
        requirePublishableVehicle(task.getVehicleId());
        if (transportCommandMapper.existsActiveByTaskId(task.getId())) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_DISPATCHED,
                    "이미 진행 중인 운반 명령이 있는 작업입니다: " + taskCode);
        }

        String commandId = "TCMD-" + UUID.randomUUID();
        String slotCode = task.getDestinationSlotId() == null ? null
                : storageSlotMapper.findById(task.getDestinationSlotId()).map(StorageSlot::getSlotCode).orElse(null);

        TransportCommandMessage message = new TransportCommandMessage(
                commandId, taskCode, task.getVehicleId(), COMMAND_TYPE_TRANSPORT,
                new TransportCommandMessage.Pickup(
                        task.getPalletId(), task.getSourceX(), task.getSourceY(), task.getSourceHeading()),
                new TransportCommandMessage.Destination(
                        slotCode, task.getDestinationX(), task.getDestinationY(), task.getDestinationHeading(),
                        task.getForkHeight(),
                        task.getCargoOrientation() == null ? null : task.getCargoOrientation().name()),
                CommunicationTime.nowOffset());

        LocalDateTime now = LocalDateTime.now();
        TransportCommand command = new TransportCommand();
        command.setCommandId(commandId);
        command.setTaskId(task.getId());
        command.setTaskCode(taskCode);
        command.setVehicleId(task.getVehicleId());
        command.setCommandType(COMMAND_TYPE_TRANSPORT);
        command.setStatus(TransportCommandStatus.CREATED);
        command.setPayload(serialize(message));
        command.setCreatedAt(now);
        command.setUpdatedAt(now);
        transportCommandMapper.insert(command);
        return message;
    }

    /** publish 성공 후: command PUBLISHED + Task를 시작 상태(MOVING_TO_PICKUP)로 전이(커밋 후 WS 알림). */
    @Transactional
    public void confirmPublished(String commandId, String taskCode) {
        LocalDateTime now = LocalDateTime.now();
        int published = transportCommandMapper.markPublished(commandId, now, now);
        if (published != 1) {
            throw new BusinessException(ErrorCode.INVALID_TRANSPORT_COMMAND_STATUS,
                    "PUBLISHED 전이에 실패했습니다(현재 CREATED 아님): commandId=" + commandId);
        }
        // MQTT 발행이 성공한 이후에만 Task를 시작 상태로 바꾼다(prompt48.md 9장). 기존 전이 로직 재사용.
        transportTaskService.changeStatus(taskCode, TaskStatus.MOVING_TO_PICKUP.name());
        TransportTask task = transportTaskMapper.findByTaskCode(taskCode).orElse(null);
        broadcaster.broadcastAfterCommit("TASK_DISPATCHED", taskCode,
                task == null ? null : task.getStatus().name(), task == null ? null : task.getVehicleId());
    }

    /** publish 실패: command PUBLISH_FAILED. Task는 ASSIGNED 유지(재디스패치 가능). */
    @Transactional
    public void markPublishFailed(String commandId, String reason) {
        transportCommandMapper.markPublishFailed(commandId, truncate(reason), LocalDateTime.now());
    }

    private void requirePublishableVehicle(String vehicleId) {
        VehicleCurrentStatus current = vehicleCurrentStatusMapper.findByVehicleId(vehicleId).orElse(null);
        if (current == null) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_AVAILABLE,
                    "차량 상태 정보가 없어 디스패치할 수 없습니다: " + vehicleId);
        }
        VehicleStatus s = current.getStatus();
        if (s == VehicleStatus.OFFLINE || s == VehicleStatus.ERROR || s == VehicleStatus.ESTOP) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_AVAILABLE,
                    "명령을 발행할 수 없는 차량 상태입니다: " + vehicleId + ", status=" + s);
        }
    }

    private String serialize(TransportCommandMessage message) {
        try {
            return truncate(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
