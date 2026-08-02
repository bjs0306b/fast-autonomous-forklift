package com.fast.backend.station.service;

import com.fast.backend.station.config.StationMoveProperties;
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

/** MOVE 결과, 측정 요청 또는 활성 측정 세션이 각 TTL을 넘으면 작업을 실패 처리하고 차선을 비운다. */
@Service
public class StationMeasurementTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementTimeoutService.class);

    private final StationSessionMapper sessionMapper;
    private final TransportTaskMapper taskMapper;
    private final StationSessionProperties sessionProperties;
    private final StationMoveProperties moveProperties;
    private final Clock clock;

    public StationMeasurementTimeoutService(
            StationSessionMapper sessionMapper,
            TransportTaskMapper taskMapper,
            StationSessionProperties sessionProperties,
            StationMoveProperties moveProperties,
            Clock clock) {
        this.sessionMapper = sessionMapper;
        this.taskMapper = taskMapper;
        this.sessionProperties = sessionProperties;
        this.moveProperties = moveProperties;
        this.clock = clock;
    }

    /**
     * 한 번의 트랜잭션에서 MOVE 결과 대기, 세션 생성 대기, 활성 세션 만료를 정리한다.
     * 상태 전이는 조건부 UPDATE로 처리해 정상 측정 완료와 경합해도 완료 작업을 실패로 덮지 않는다.
     */
    @Transactional
    public void expireTimedOutMeasurements() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime sessionExpiredBefore = now.minus(sessionProperties.ttl());
        LocalDateTime moveExpiredBefore = now.minus(moveProperties.ttl());

        expireActiveSession(sessionExpiredBefore, now);
        expireUnopenedRequests(sessionExpiredBefore, now);
        expireMovesAwaitingResult(moveExpiredBefore, now);
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
                    sessionId, state.getAcquiredAt(), sessionProperties.ttlSeconds());
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

    private void expireMovesAwaitingResult(LocalDateTime expiredBefore, LocalDateTime failedAt) {
        List<TransportTask> expired = taskMapper.findExpiredMovesAwaitingResult(expiredBefore);
        for (TransportTask task : expired) {
            if (taskMapper.failMoveIfAwaitingResult(task.getId(), expiredBefore, failedAt) == 1) {
                log.warn("MOVE result timed out: taskId={}, cargoId={}, startedAt={}, ttlSeconds={}",
                        task.getTaskCode(), task.getCargoId(), task.getStartedAt(), moveProperties.ttlSeconds());
            }
        }
    }
}
