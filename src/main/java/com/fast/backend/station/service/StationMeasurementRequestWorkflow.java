package com.fast.backend.station.service;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.station.config.StationMeasurementRequestProperties;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.dto.StationMeasureRequestMessage;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 측정 위치 MOVE 결과를 운반 작업 상태와 화물 측정 요청으로 연결한다. */
@Service
public class StationMeasurementRequestWorkflow {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementRequestWorkflow.class);

    private final TransportTaskMapper taskMapper;
    private final StationMeasurementService measurementService;
    private final StationMeasureRequestPublisher requestPublisher;
    private final StationMeasurementRequestProperties properties;

    public StationMeasurementRequestWorkflow(
            TransportTaskMapper taskMapper,
            StationMeasurementService measurementService,
            StationMeasureRequestPublisher requestPublisher,
            StationMeasurementRequestProperties properties) {
        this.taskMapper = taskMapper;
        this.measurementService = measurementService;
        this.requestPublisher = requestPublisher;
        this.properties = properties;
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

        StationSession session = null;
        try {
            session = measurementService.openSession(task.getCargoId());
            if (taskMapper.startMeasurement(task.getId(), session.getSessionId()) != 1) {
                measurementService.forceReleaseSession(session.getSessionId());
                return;
            }
            requestPublisher.publish(new StationMeasureRequestMessage(
                    session.getSessionId(), task.getCargoId(), task.getTaskCode(), task.getVehicleId(),
                    properties.maxAttempts(), CommunicationTime.nowOffset()));
            log.info("Station measurement requested: taskId={}, sessionId={}, cargoId={}, vehicleId={}",
                    task.getTaskCode(), session.getSessionId(), task.getCargoId(), task.getVehicleId());
        } catch (RuntimeException exception) {
            if (session != null) {
                try {
                    measurementService.forceReleaseSession(session.getSessionId());
                } catch (RuntimeException releaseFailure) {
                    log.error("Failed to release measurement session after request failure: sessionId={}, error={}",
                            session.getSessionId(), releaseFailure.getMessage());
                }
            }
            failTask(task);
            log.error("Station measurement request failed: taskId={}, error={}",
                    task.getTaskCode(), exception.getMessage());
        }
    }

    private void failTask(TransportTask task) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.updateStatusIfCurrent(
                task.getId(), TaskStatus.MOVING_TO_PICKUP, TaskStatus.FAILED,
                null, null, now);
        if (updated == 0) {
            taskMapper.updateStatusIfCurrent(
                    task.getId(), TaskStatus.MEASURING, TaskStatus.FAILED,
                    null, null, now);
        }
    }
}
