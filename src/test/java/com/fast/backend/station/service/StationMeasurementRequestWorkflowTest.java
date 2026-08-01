package com.fast.backend.station.service;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.station.config.StationMeasurementRequestProperties;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.dto.StationMeasureRequestMessage;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StationMeasurementRequestWorkflowTest {

    @Test
    void successfulMove_opensSessionAndPublishesCorrelatedRequest() {
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        StationMeasurementService measurementService = mock(StationMeasurementService.class);
        StationMeasureRequestPublisher publisher = mock(StationMeasureRequestPublisher.class);
        StationMeasurementRequestWorkflow workflow = new StationMeasurementRequestWorkflow(
                taskMapper, measurementService, publisher, new StationMeasurementRequestProperties(3));

        TransportTask task = new TransportTask();
        task.setId(7L);
        task.setTaskCode("TASK-01");
        task.setCargoId("CARGO-01");
        task.setVehicleId("FORKLIFT-01");
        task.setStatus(TaskStatus.MOVING_TO_PICKUP);
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task));
        when(measurementService.openSession("CARGO-01"))
                .thenReturn(new StationSession("SESSION-01", "CARGO-01"));
        when(taskMapper.startMeasurement(7L, "SESSION-01")).thenReturn(1);

        VehicleCommand command = new VehicleCommand();
        command.setCommandId("COMMAND-01");
        command.setTaskId(7L);
        command.setCommand(VehicleCommandType.MOVE);

        workflow.handleMoveResult(command, VehicleCommandStatus.SUCCESS);

        ArgumentCaptor<StationMeasureRequestMessage> captor =
                ArgumentCaptor.forClass(StationMeasureRequestMessage.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo("SESSION-01");
        assertThat(captor.getValue().cargoId()).isEqualTo("CARGO-01");
        assertThat(captor.getValue().taskId()).isEqualTo("TASK-01");
        assertThat(captor.getValue().vehicleId()).isEqualTo("FORKLIFT-01");
        assertThat(captor.getValue().maxAttempts()).isEqualTo(3);
    }
}
