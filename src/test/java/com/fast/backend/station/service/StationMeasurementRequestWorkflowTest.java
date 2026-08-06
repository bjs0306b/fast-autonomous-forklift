package com.fast.backend.station.service;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StationMeasurementRequestWorkflowTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-03T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void successfulMove_marksTaskAsWaitingForMeasurement() {
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        StationMeasurementRequestWorkflow workflow = new StationMeasurementRequestWorkflow(
                taskMapper, eventPublisher, CLOCK);

        TransportTask task = new TransportTask();
        task.setId(7L);
        task.setTaskCode("TASK-01");
        task.setCargoId(1L);
        task.setVehicleId("FORKLIFT-01");
        task.setStatus(TaskStatus.MOVING_TO_PICKUP);
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task));
        when(taskMapper.updateStatusIfCurrent(
                7L, TaskStatus.MOVING_TO_PICKUP, TaskStatus.MEASURING,
                null, null)).thenReturn(1);

        VehicleCommand command = new VehicleCommand();
        command.setCommandId("COMMAND-01");
        command.setTaskId(7L);
        command.setCommand(VehicleCommandType.MOVE);

        workflow.handleMoveResult(command, VehicleCommandStatus.SUCCESS);

        verify(taskMapper).updateStatusIfCurrent(
                eq(7L), eq(TaskStatus.MOVING_TO_PICKUP), eq(TaskStatus.MEASURING),
                eq(null), eq(null));
        ArgumentCaptor<StationMeasurementReadyEvent> event =
                ArgumentCaptor.forClass(StationMeasurementReadyEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().taskId()).isEqualTo(7L);
    }

    @Test
    void arrivedTopic_marksTheMatchingVehicleTaskAsWaitingForMeasurement() {
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        StationMeasurementRequestWorkflow workflow = new StationMeasurementRequestWorkflow(
                taskMapper, eventPublisher, CLOCK);
        TransportTask task = new TransportTask();
        task.setId(8L);
        task.setTaskCode("TASK-ARRIVED");
        task.setCargoId(2L);
        task.setVehicleId("SIM-F02");
        task.setStatus(TaskStatus.MOVING_TO_PICKUP);
        when(taskMapper.findActiveTasksWithVehicle()).thenReturn(List.of(task));
        when(taskMapper.updateStatusIfCurrent(
                8L, TaskStatus.MOVING_TO_PICKUP, TaskStatus.MEASURING,
                null, null)).thenReturn(1);

        workflow.handleArrival("SIM-F02", "TASK-ARRIVED");

        verify(taskMapper).updateStatusIfCurrent(
                8L, TaskStatus.MOVING_TO_PICKUP, TaskStatus.MEASURING,
                null, null);
        verify(eventPublisher).publishEvent(new StationMeasurementReadyEvent(8L));
    }
}
