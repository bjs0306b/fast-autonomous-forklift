package com.fast.backend.station.service;

import com.fast.backend.station.config.StationSessionProperties;
import com.fast.backend.station.domain.StationState;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** 측정 요청 또는 활성 측정 세션이 TTL을 넘으면 작업을 실패 처리하고 측정 차선을 비운다. */
@Service
public class StationMeasurementTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementTimeoutService.class);

    private final StationSessionMapper sessionMapper;
    private final TransportTaskMapper taskMapper;
    private final StationSessionProperties properties;
    private final Clock clock;

    public StationMeasurementTimeoutService(
            StationSessionMapper sessionMapper,
            TransportTaskMapper taskMapper,
            StationSessionProperties properties,
            Clock clock) {
        this.sessionMapper = sessionMapper;
        this.taskMapper = taskMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 한 번의 트랜잭션에서 만료된 세션과 세션을 열지 못한 측정 요청을 정리한다.
     * 상태 전이는 조건부 UPDATE로 처리해 정상 측정 완료와 경합해도 완료 작업을 실패로 덮지 않는다.
     */
    @Transactional
    public void expireTimedOutMeasurements() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiredBefore = now.minus(properties.ttl());

        expireActiveSession(expiredBefore, now);
        expireUnopenedRequests(expiredBefore, now);
    }

    private void expireActiveSession(LocalDateTime expiredBefore, LocalDateTime failedAt) {
        StationState state = sessionMapper.findStateForUpdate().orElse(null);
        if (state == null || !state.isOccupied() || !state.isExpired(expiredBefore)) {
            return;
        }

        String sessionId = state.getActiveSessionId();
        taskMapper.findByMeasurementSessionId(sessionId).ifPresent(task -> {
            int updated = taskMapper.updateStatusIfCurrent(
                    task.getId(), TaskStatus.MEASURING, TaskStatus.FAILED,
                    null, failedAt);
            if (updated == 1) {
                log.warn("Measurement task expired during session: taskId={}, sessionId={}",
                        task.getTaskCode(), sessionId);
            }
        });

        if (sessionMapper.releaseStation(sessionId) == 1) {
            log.warn("Station session expired automatically: sessionId={}, acquiredAt={}, ttlSeconds={}",
                    sessionId, state.getAcquiredAt(), properties.ttlSeconds());
        }
    }

    private void expireUnopenedRequests(LocalDateTime expiredBefore, LocalDateTime failedAt) {
        List<TransportTask> expired = taskMapper.findExpiredMeasurementRequests(expiredBefore);
        for (TransportTask task : expired) {
            if (taskMapper.updateStatusIfCurrent(
                    task.getId(), TaskStatus.MOVING_TO_PICKUP, TaskStatus.FAILED,
                    null, failedAt) == 1) {
                log.warn("Measurement request expired before session open: taskId={}, cargoId={}, requestedAt={}",
                        task.getTaskCode(), task.getCargoId(), task.getMeasurementRequestedAt());
            }
        }
    }
}
