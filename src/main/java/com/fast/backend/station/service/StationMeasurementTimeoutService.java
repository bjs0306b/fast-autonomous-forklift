package com.fast.backend.station.service;

import com.fast.backend.station.config.StationMoveProperties;
import com.fast.backend.station.config.StationSessionProperties;
import com.fast.backend.station.domain.StationState;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.transport.domain.TaskFailureCode;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.transport.websocket.TransportTaskBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** MOVE 결과·설비 대기·측정 요청·활성 세션이 각 TTL을 넘으면 작업을 실패 처리하고 차선을 비운다. */
@Service
public class StationMeasurementTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementTimeoutService.class);

    private final StationSessionMapper sessionMapper;
    private final TransportTaskMapper taskMapper;
    private final StationSessionProperties sessionProperties;
    private final StationMoveProperties moveProperties;
    private final TransportTaskBroadcaster broadcaster;
    private final Clock clock;

    public StationMeasurementTimeoutService(
            StationSessionMapper sessionMapper,
            TransportTaskMapper taskMapper,
            StationSessionProperties sessionProperties,
            StationMoveProperties moveProperties,
            TransportTaskBroadcaster broadcaster,
            Clock clock) {
        this.sessionMapper = sessionMapper;
        this.taskMapper = taskMapper;
        this.sessionProperties = sessionProperties;
        this.moveProperties = moveProperties;
        this.broadcaster = broadcaster;
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
        expireMeasurementLaneWaits(moveExpiredBefore, now);
        expireMovesAwaitingResult(moveExpiredBefore, now);
    }

    private void expireActiveSession(LocalDateTime expiredBefore, LocalDateTime failedAt) {
        StationState state = sessionMapper.findStateForUpdate().orElse(null);
        if (state == null || !state.isOccupied() || !state.isExpired(expiredBefore)) {
            return;
        }

        String sessionId = state.getActiveSessionId();
        taskMapper.findByMeasurementSessionId(sessionId).ifPresent(task -> {
            // MEASURING 일 때만 실패로 넘긴다. TTL 직전에 정상 결과가 도착해 PICKING_UP 으로 넘어간
            // 작업은 여기서 0행이 되어 무응답 실패로 덮이지 않는다.
            int updated = taskMapper.failWithCode(
                    task.getId(), TaskStatus.MEASURING, failedAt, TaskFailureCode.MEASUREMENT_NO_RESPONSE);
            if (updated == 1) {
                notifyFailed(task);
                log.warn("Measurement task expired during session: taskId={}, sessionId={}, failureCode={}",
                        task.getTaskCode(), sessionId, TaskFailureCode.MEASUREMENT_NO_RESPONSE);
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
            if (taskMapper.failWithCode(
                    task.getId(), TaskStatus.MEASURING, failedAt,
                    TaskFailureCode.MEASUREMENT_NO_RESPONSE) == 1) {
                notifyFailed(task);
                log.warn("Measurement request expired before session open: taskId={}, cargoId={}, requestedAt={}",
                        task.getTaskCode(), task.getCargoId(), task.getMeasurementRequestedAt());
            }
        }
    }

    private void expireMeasurementLaneWaits(LocalDateTime expiredBefore, LocalDateTime failedAt) {
        List<TransportTask> expired = taskMapper.findExpiredMeasurementLaneWaits(expiredBefore);
        for (TransportTask task : expired) {
            if (taskMapper.failMeasurementLaneWaitIfExpired(
                    task.getId(), expiredBefore, failedAt) == 1) {
                log.warn("Measurement lane wait timed out: taskId={}, cargoId={}, startedAt={}, ttlSeconds={}",
                        task.getTaskCode(), task.getCargoId(), task.getStartedAt(), moveProperties.ttlSeconds());
            }
        }
    }

    private void expireMovesAwaitingResult(LocalDateTime expiredBefore, LocalDateTime failedAt) {
        List<TransportTask> expired = taskMapper.findExpiredMovesAwaitingResult(expiredBefore);
        for (TransportTask task : expired) {
            if (taskMapper.failMoveIfAwaitingResult(
                    task.getId(), expiredBefore, failedAt, TaskFailureCode.MEASUREMENT_NO_RESPONSE) == 1) {
                notifyFailed(task);
                log.warn("MOVE result timed out: taskId={}, cargoId={}, startedAt={}, ttlSeconds={}",
                        task.getTaskCode(), task.getCargoId(), task.getStartedAt(), moveProperties.ttlSeconds());
            }
        }
    }

    /**
     * TTL 실패도 실시간으로 알린다.
     *
     * <p>이전에는 만료 처리가 DB 상태만 바꾸고 아무것도 보내지 않아, 관제 화면은 다음 새로고침
     * 전까지 실패를 몰랐다 — "조용히 사라지는 실패"의 실제 원인이다. 기존 토픽·이벤트 타입을
     * 그대로 쓰고 원인 코드만 얹는다.
     *
     * <p>조건부 UPDATE 가 1행을 바꾼 경우에만 호출되므로 같은 세션에 중복 이벤트가 나가지 않는다.
     */
    private void notifyFailed(TransportTask task) {
        broadcaster.broadcastAfterCommit(
                "TRANSPORT_TASK_FAILED", task.getTaskCode(), TaskStatus.FAILED.name(),
                task.getVehicleId(), TaskFailureCode.MEASUREMENT_NO_RESPONSE);
    }
}
