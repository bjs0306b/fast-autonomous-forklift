package com.fast.backend.station.service;

import com.fast.backend.station.config.StationMoveProperties;
import com.fast.backend.station.config.StationSessionProperties;
import com.fast.backend.station.domain.StationState;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StationMeasurementTimeoutServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-02T03:00:00Z"), SEOUL);
    private static final StationSessionProperties SESSION_PROPERTIES = new StationSessionProperties(60L);
    private static final StationMoveProperties MOVE_PROPERTIES = new StationMoveProperties(300L);

    @Test
    void expiredActiveSession_failsMeasuringTaskAndReleasesStation() {
        StationSessionMapper sessionMapper = mock(StationSessionMapper.class);
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        StationMeasurementTimeoutService service = new StationMeasurementTimeoutService(
                sessionMapper, taskMapper, SESSION_PROPERTIES, MOVE_PROPERTIES, CLOCK);

        LocalDateTime acquiredAt = LocalDateTime.of(2026, 8, 2, 11, 58);
        StationState state = new StationState();
        state.setSingletonId(1);
        state.setActiveSessionId("SESSION-1");
        state.setAcquiredAt(acquiredAt);
        TransportTask task = task(1L, "TASK-1", TaskStatus.MEASURING);

        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(state));
        when(taskMapper.findByMeasurementSessionId("SESSION-1")).thenReturn(Optional.of(task));
        when(taskMapper.updateStatusIfCurrent(
                eq(1L), eq(TaskStatus.MEASURING), eq(TaskStatus.FAILED),
                eq(null), any())).thenReturn(1);
        when(sessionMapper.releaseStation("SESSION-1")).thenReturn(1);
        when(taskMapper.findExpiredMeasurementRequests(any())).thenReturn(List.of());
        when(taskMapper.findExpiredMeasurementLaneWaits(any())).thenReturn(List.of());
        when(taskMapper.findExpiredMovesAwaitingResult(any())).thenReturn(List.of());

        service.expireTimedOutMeasurements();

        verify(sessionMapper).releaseStation("SESSION-1");
        verify(taskMapper).updateStatusIfCurrent(
                eq(1L), eq(TaskStatus.MEASURING), eq(TaskStatus.FAILED),
                eq(null), any());
    }

    @Test
    void expiredRequestWithoutSession_failsMeasuringTask() {
        StationSessionMapper sessionMapper = mock(StationSessionMapper.class);
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        StationMeasurementTimeoutService service = new StationMeasurementTimeoutService(
                sessionMapper, taskMapper, SESSION_PROPERTIES, MOVE_PROPERTIES, CLOCK);

        StationState idle = new StationState();
        idle.setSingletonId(1);
        TransportTask task = task(2L, "TASK-2", TaskStatus.MEASURING);
        task.setMeasurementRequestedAt(LocalDateTime.of(2026, 8, 2, 11, 58));

        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(idle));
        when(taskMapper.findExpiredMeasurementRequests(any())).thenReturn(List.of(task));
        when(taskMapper.findExpiredMeasurementLaneWaits(any())).thenReturn(List.of());
        when(taskMapper.findExpiredMovesAwaitingResult(any())).thenReturn(List.of());
        when(taskMapper.updateStatusIfCurrent(
                eq(2L), eq(TaskStatus.MEASURING), eq(TaskStatus.FAILED),
                eq(null), any())).thenReturn(1);

        service.expireTimedOutMeasurements();

        verify(taskMapper).updateStatusIfCurrent(
                eq(2L), eq(TaskStatus.MEASURING), eq(TaskStatus.FAILED),
                eq(null), any());
    }

    @Test
    void measurementLaneWait_expiresAfterMoveTtl() {
        StationSessionMapper sessionMapper = mock(StationSessionMapper.class);
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        StationMeasurementTimeoutService service = new StationMeasurementTimeoutService(
                sessionMapper, taskMapper, SESSION_PROPERTIES, MOVE_PROPERTIES, CLOCK);

        StationState idle = new StationState();
        idle.setSingletonId(1);
        TransportTask task = task(4L, "TASK-4", TaskStatus.MEASURING);
        task.setStartedAt(LocalDateTime.of(2026, 8, 2, 11, 54));

        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(idle));
        when(taskMapper.findExpiredMeasurementRequests(any())).thenReturn(List.of());
        when(taskMapper.findExpiredMeasurementLaneWaits(any())).thenReturn(List.of(task));
        when(taskMapper.findExpiredMovesAwaitingResult(any())).thenReturn(List.of());
        when(taskMapper.failMeasurementLaneWaitIfExpired(
                eq(4L), eq(LocalDateTime.of(2026, 8, 2, 11, 55)), any())).thenReturn(1);

        service.expireTimedOutMeasurements();

        verify(taskMapper).failMeasurementLaneWaitIfExpired(
                eq(4L), eq(LocalDateTime.of(2026, 8, 2, 11, 55)), any());
    }

    @Test
    void moveWithoutResult_expiresAfterSeparateMoveTtl() {
        StationSessionMapper sessionMapper = mock(StationSessionMapper.class);
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        StationMeasurementTimeoutService service = new StationMeasurementTimeoutService(
                sessionMapper, taskMapper, SESSION_PROPERTIES, MOVE_PROPERTIES, CLOCK);

        StationState idle = new StationState();
        idle.setSingletonId(1);
        TransportTask task = task(3L, "TASK-3", TaskStatus.MOVING_TO_PICKUP);
        task.setStartedAt(LocalDateTime.of(2026, 8, 2, 11, 54));

        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(idle));
        when(taskMapper.findExpiredMeasurementRequests(any())).thenReturn(List.of());
        when(taskMapper.findExpiredMeasurementLaneWaits(any())).thenReturn(List.of());
        when(taskMapper.findExpiredMovesAwaitingResult(any())).thenReturn(List.of(task));
        when(taskMapper.failMoveIfAwaitingResult(
                eq(3L), eq(LocalDateTime.of(2026, 8, 2, 11, 55)), any())).thenReturn(1);

        service.expireTimedOutMeasurements();

        verify(taskMapper).findExpiredMovesAwaitingResult(LocalDateTime.of(2026, 8, 2, 11, 55));
        verify(taskMapper).failMoveIfAwaitingResult(
                eq(3L), eq(LocalDateTime.of(2026, 8, 2, 11, 55)), any());
    }

    private TransportTask task(Long id, String taskCode, TaskStatus status) {
        TransportTask task = new TransportTask();
        task.setId(id);
        task.setTaskCode(taskCode);
        task.setCargoId(1L);
        task.setStatus(status);
        return task;
    }
}
