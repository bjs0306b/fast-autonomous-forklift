package com.fast.backend.station.service;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.station.dto.StationMeasureRequestMessage;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StationMeasurementRequestWorkflowTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-03T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void successfulMove_publishesCargoMeasurementRequest() {
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        StationMeasureRequestPublisher publisher = mock(StationMeasureRequestPublisher.class);
        StationMeasurementRequestWorkflow workflow = new StationMeasurementRequestWorkflow(
                taskMapper, publisher, CLOCK);

        TransportTask task = new TransportTask();
        task.setId(7L);
        task.setTaskCode("TASK-01");
        task.setCargoId(1L);
        task.setVehicleId("FORKLIFT-01");
        task.setStatus(TaskStatus.MOVING_TO_PICKUP);
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task));
        when(taskMapper.markMeasurementRequested(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);

        VehicleCommand command = new VehicleCommand();
        command.setCommandId("COMMAND-01");
        command.setTaskId(7L);
        command.setCommand(VehicleCommandType.MOVE);

        workflow.handleMoveResult(command, VehicleCommandStatus.SUCCESS);

        ArgumentCaptor<StationMeasureRequestMessage> captor =
                ArgumentCaptor.forClass(StationMeasureRequestMessage.class);
        ArgumentCaptor<LocalDateTime> requestedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(publisher).publish(captor.capture());
        verify(taskMapper).markMeasurementRequested(org.mockito.ArgumentMatchers.eq(7L),
                requestedAtCaptor.capture());
        assertThat(captor.getValue().cargoId()).isEqualTo(1L);
        assertThat(requestedAtCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 3, 12, 0));
    }
}
