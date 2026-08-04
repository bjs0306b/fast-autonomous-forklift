package com.fast.backend.station.service;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/** 측정 위치 MOVE 결과를 운반 작업의 측정 대기 상태로 연결한다. */
@Service
public class StationMeasurementRequestWorkflow {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementRequestWorkflow.class);

    private final TransportTaskMapper taskMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public StationMeasurementRequestWorkflow(
            TransportTaskMapper taskMapper,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.taskMapper = taskMapper;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public void handleMoveResult(VehicleCommand command, VehicleCommandStatus resultStatus) {
        if (command.getTaskId() == null || command.getCommand() != VehicleCommandType.MOVE) {
            return;
        }
        TransportTask task = taskMapper.findById(command.getTaskId()).orElse(null);
        if (task == null || task.getStatus() != TaskStatus.MOVING_TO_PICKUP) {
            log.warn("Task-linked MOVE result ignored: commandId={}, taskId={}, taskStatus={}",
                    command.getCommandId(), command.getTaskId(), task == null ? null : task.getStatus());
            return;
        }
        if (resultStatus != VehicleCommandStatus.SUCCESS) {
            failTask(task);
            return;
        }

        if (taskMapper.updateStatusIfCurrent(
                task.getId(), TaskStatus.MOVING_TO_PICKUP, TaskStatus.MEASURING,
                null, null) != 1) {
            log.warn("Station measurement wait ignored: task state changed, taskId={}",
                    task.getTaskCode());
            return;
        }
        log.info("Station measurement wait started: taskId={}, cargoId={}, vehicleId={}",
                task.getTaskCode(), task.getCargoId(), task.getVehicleId());
        eventPublisher.publishEvent(new StationMeasurementReadyEvent(task.getId()));
    }

    private void failTask(TransportTask task) {
        LocalDateTime now = LocalDateTime.now(clock);
        taskMapper.updateStatusIfCurrent(
                task.getId(), TaskStatus.MOVING_TO_PICKUP, TaskStatus.FAILED,
                null, now);
    }
}
