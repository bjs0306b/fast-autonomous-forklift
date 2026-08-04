package com.fast.backend.station.service;

import com.fast.backend.station.domain.StationState;
import com.fast.backend.station.dto.StationMeasureRequestMessage;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.station.config.StationMoveProperties;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StationMeasurementRequestDispatchServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-03T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final StationMoveProperties MOVE_PROPERTIES = new StationMoveProperties(300L);

    @Test
    void occupiedStation_keepsReadyTaskWaiting() {
        StationSessionMapper sessionMapper = mock(StationSessionMapper.class);
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        StationMeasureRequestPublisher publisher = mock(StationMeasureRequestPublisher.class);
        StationMeasurementRequestDispatchService service = new StationMeasurementRequestDispatchService(
                sessionMapper, taskMapper, publisher, MOVE_PROPERTIES, CLOCK);

        StationState occupied = new StationState();
        occupied.setSingletonId(1);
        occupied.setActiveSessionId("SESSION-OLD");
        occupied.setAcquiredAt(LocalDateTime.of(2026, 8, 3, 11, 59, 30));
        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(occupied));

        service.dispatchIfStationAvailable();

        verify(taskMapper, never()).findOldestMeasurementAwaitingRequest();
        verify(publisher, never()).publish(any());
    }

    @Test
    void idleStation_publishesOldestReadyTask() {
        StationSessionMapper sessionMapper = mock(StationSessionMapper.class);
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        StationMeasureRequestPublisher publisher = mock(StationMeasureRequestPublisher.class);
        StationMeasurementRequestDispatchService service = new StationMeasurementRequestDispatchService(
                sessionMapper, taskMapper, publisher, MOVE_PROPERTIES, CLOCK);

        StationState idle = new StationState();
        idle.setSingletonId(1);
        TransportTask task = task();
        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(idle));
        when(taskMapper.findOldestMeasurementAwaitingRequest()).thenReturn(Optional.of(task));
        when(taskMapper.markMeasurementRequested(eq(7L), any(), any())).thenReturn(1);

        service.dispatchIfStationAvailable();

        ArgumentCaptor<LocalDateTime> requestedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<StationMeasureRequestMessage> message =
                ArgumentCaptor.forClass(StationMeasureRequestMessage.class);
        verify(taskMapper).markMeasurementRequested(
                eq(7L), requestedAt.capture(),
                eq(LocalDateTime.of(2026, 8, 3, 11, 55)));
        verify(publisher).publish(message.capture());
        assertThat(requestedAt.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 3, 12, 0));
        assertThat(message.getValue().cargoId()).isEqualTo(1L);
    }

    @Test
    void publishFailure_failsReadyTask() {
        StationSessionMapper sessionMapper = mock(StationSessionMapper.class);
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        StationMeasureRequestPublisher publisher = mock(StationMeasureRequestPublisher.class);
        StationMeasurementRequestDispatchService service = new StationMeasurementRequestDispatchService(
                sessionMapper, taskMapper, publisher, MOVE_PROPERTIES, CLOCK);

        StationState idle = new StationState();
        idle.setSingletonId(1);
        TransportTask task = task();
        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(idle));
        when(taskMapper.findOldestMeasurementAwaitingRequest()).thenReturn(Optional.of(task));
        when(taskMapper.markMeasurementRequested(eq(7L), any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher).publish(any());

        service.dispatchIfStationAvailable();

        verify(taskMapper).updateStatusIfCurrent(
                eq(7L), eq(TaskStatus.MEASURING), eq(TaskStatus.FAILED),
                eq(null), eq(LocalDateTime.of(2026, 8, 3, 12, 0)));
    }

    @Test
    void expiredLaneWait_isFailedWithoutPublishing() {
        StationSessionMapper sessionMapper = mock(StationSessionMapper.class);
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        StationMeasureRequestPublisher publisher = mock(StationMeasureRequestPublisher.class);
        StationMeasurementRequestDispatchService service = new StationMeasurementRequestDispatchService(
                sessionMapper, taskMapper, publisher, MOVE_PROPERTIES, CLOCK);

        StationState idle = new StationState();
        idle.setSingletonId(1);
        TransportTask task = task();
        task.setStartedAt(LocalDateTime.of(2026, 8, 3, 11, 55));
        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(idle));
        when(taskMapper.findOldestMeasurementAwaitingRequest()).thenReturn(Optional.of(task));
        when(taskMapper.failMeasurementLaneWaitIfExpired(
                eq(7L), eq(LocalDateTime.of(2026, 8, 3, 11, 55)), any())).thenReturn(1);

        service.dispatchIfStationAvailable();

        verify(taskMapper).markMeasurementRequested(
                eq(7L), eq(LocalDateTime.of(2026, 8, 3, 12, 0)),
                eq(LocalDateTime.of(2026, 8, 3, 11, 55)));
        verify(taskMapper).failMeasurementLaneWaitIfExpired(
                eq(7L), eq(LocalDateTime.of(2026, 8, 3, 11, 55)),
                eq(LocalDateTime.of(2026, 8, 3, 12, 0)));
        verify(publisher, never()).publish(any());
    }

    private TransportTask task() {
        TransportTask task = new TransportTask();
        task.setId(7L);
        task.setTaskCode("TASK-01");
        task.setCargoId(1L);
        task.setVehicleId("FORKLIFT-01");
        task.setStatus(TaskStatus.MEASURING);
        task.setStartedAt(LocalDateTime.of(2026, 8, 3, 11, 59));
        return task;
    }
}
