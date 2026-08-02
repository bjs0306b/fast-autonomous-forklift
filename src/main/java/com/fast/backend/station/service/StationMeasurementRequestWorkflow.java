package com.fast.backend.station.service;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.station.dto.StationMeasureRequestMessage;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/** 측정 위치 MOVE 결과를 운반 작업 상태와 화물 측정 요청으로 연결한다. */
@Service
public class StationMeasurementRequestWorkflow {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementRequestWorkflow.class);

    private final TransportTaskMapper taskMapper;
    private final StationMeasureRequestPublisher requestPublisher;
    private final Clock clock;

    public StationMeasurementRequestWorkflow(
            TransportTaskMapper taskMapper,
            StationMeasureRequestPublisher requestPublisher,
            Clock clock) {
        this.taskMapper = taskMapper;
        this.requestPublisher = requestPublisher;
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

        try {
            LocalDateTime requestedAt = LocalDateTime.now(clock);
            if (taskMapper.markMeasurementRequested(task.getId(), requestedAt) != 1) {
                log.warn("Station measurement request ignored: task state changed, taskId={}",
                        task.getTaskCode());
                return;
            }
            requestPublisher.publish(new StationMeasureRequestMessage(task.getCargoId()));
            log.info("Station measurement requested: taskId={}, cargoId={}, vehicleId={}",
                    task.getTaskCode(), task.getCargoId(), task.getVehicleId());
        } catch (RuntimeException exception) {
            failTask(task);
            log.error("Station measurement request failed: taskId={}, error={}",
                    task.getTaskCode(), exception.getMessage());
        }
    }

    private void failTask(TransportTask task) {
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = taskMapper.updateStatusIfCurrent(
                task.getId(), TaskStatus.MOVING_TO_PICKUP, TaskStatus.FAILED,
                null, now);
        if (updated == 0) {
            taskMapper.updateStatusIfCurrent(
                    task.getId(), TaskStatus.MEASURING, TaskStatus.FAILED,
                    null, now);
        }
    }
}
