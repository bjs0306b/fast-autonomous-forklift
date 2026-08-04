package com.fast.backend.station.service;

import com.fast.backend.station.domain.StationState;
import com.fast.backend.station.dto.StationMeasureRequestMessage;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.station.config.StationMoveProperties;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/** 측정 위치에 도착해 대기 중인 작업의 AI 측정 요청을 한 건씩 발행한다. */
@Service
public class StationMeasurementRequestDispatchService {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementRequestDispatchService.class);

    private final StationSessionMapper sessionMapper;
    private final TransportTaskMapper taskMapper;
    private final StationMeasureRequestPublisher requestPublisher;
    private final StationMoveProperties moveProperties;
    private final Clock clock;

    public StationMeasurementRequestDispatchService(
            StationSessionMapper sessionMapper,
            TransportTaskMapper taskMapper,
            StationMeasureRequestPublisher requestPublisher,
            StationMoveProperties moveProperties,
            Clock clock) {
        this.sessionMapper = sessionMapper;
        this.taskMapper = taskMapper;
        this.requestPublisher = requestPublisher;
        this.moveProperties = moveProperties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchIfStationAvailable() {
        StationState state = sessionMapper.findStateForUpdate().orElse(null);
        if (state == null) {
            log.error("Station measurement request dispatch skipped: station_state singleton is missing");
            return;
        }
        if (state.isOccupied()) {
            return;
        }

        TransportTask task = taskMapper.findOldestMeasurementAwaitingRequest().orElse(null);
        if (task == null) {
            return;
        }

        LocalDateTime requestedAt = LocalDateTime.now(clock);
        LocalDateTime waitExpiredBefore = requestedAt.minus(moveProperties.ttl());
        if (taskMapper.markMeasurementRequested(task.getId(), requestedAt, waitExpiredBefore) != 1) {
            if (task.getStartedAt() == null) {
                taskMapper.updateStatusIfCurrent(
                        task.getId(), TaskStatus.MEASURING, TaskStatus.FAILED,
                        null, requestedAt);
                log.error("Station measurement wait failed: startedAt is missing, taskId={}",
                        task.getTaskCode());
                return;
            }
            if (!task.getStartedAt().isAfter(waitExpiredBefore)) {
                taskMapper.failMeasurementLaneWaitIfExpired(
                        task.getId(), waitExpiredBefore, requestedAt);
                log.warn("Station measurement wait timed out before dispatch: taskId={}, cargoId={}, "
                                + "startedAt={}, ttlSeconds={}",
                        task.getTaskCode(), task.getCargoId(), task.getStartedAt(),
                        moveProperties.ttlSeconds());
                return;
            }
            log.warn("Station measurement request ignored: task state changed, taskId={}",
                    task.getTaskCode());
            return;
        }

        try {
            requestPublisher.publish(new StationMeasureRequestMessage(task.getCargoId()));
            log.info("Station measurement requested: taskId={}, cargoId={}, vehicleId={}",
                    task.getTaskCode(), task.getCargoId(), task.getVehicleId());
        } catch (RuntimeException exception) {
            taskMapper.updateStatusIfCurrent(
                    task.getId(), TaskStatus.MEASURING, TaskStatus.FAILED,
                    null, requestedAt);
            log.error("Station measurement request failed: taskId={}, error={}",
                    task.getTaskCode(), exception.getMessage());
        }
    }
}
