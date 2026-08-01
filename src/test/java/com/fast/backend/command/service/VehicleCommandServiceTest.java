package com.fast.backend.command.service;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.dto.VehicleCommandDestination;
import com.fast.backend.command.dto.VehicleCommandRequest;
import com.fast.backend.command.mapper.VehicleCommandMapper;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleCommandServiceTest {

    @Test
    void taskLinkedMove_storesInternalTaskIdAndStartsPickupMove() {
        VehicleMapper vehicleMapper = mock(VehicleMapper.class);
        VehicleCommandMapper commandMapper = mock(VehicleCommandMapper.class);
        VehicleCommandPublisher publisher = mock(VehicleCommandPublisher.class);
        TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
        VehicleCommandService service = new VehicleCommandService(
                vehicleMapper, commandMapper, publisher, taskMapper);

        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId("FORKLIFT-01");
        vehicle.setActive(true);
        when(vehicleMapper.findByVehicleId("FORKLIFT-01")).thenReturn(Optional.of(vehicle));

        TransportTask task = new TransportTask();
        task.setId(7L);
        task.setTaskCode("TASK-01");
        task.setCargoId("CARGO-01");
        task.setVehicleId("FORKLIFT-01");
        task.setStatus(TaskStatus.ASSIGNED);
        when(taskMapper.findByTaskCode("TASK-01")).thenReturn(Optional.of(task));
        when(taskMapper.existsMeasurementLaneBusy()).thenReturn(false);
        when(taskMapper.updateStatusIfCurrent(
                eq(7L), eq(TaskStatus.ASSIGNED), eq(TaskStatus.MOVING_TO_PICKUP),
                any(LocalDateTime.class), eq(null), eq(null))).thenReturn(1);

        service.issueCommand("FORKLIFT-01", new VehicleCommandRequest(
                "MOVE", null, null,
                new VehicleCommandDestination(1.0, 2.0, 90.0, "map"), "TASK-01"));

        ArgumentCaptor<VehicleCommand> commandCaptor = ArgumentCaptor.forClass(VehicleCommand.class);
        verify(commandMapper).insert(commandCaptor.capture());
        assertThat(commandCaptor.getValue().getTaskId()).isEqualTo(7L);
        verify(publisher).publish(any());
    }
}
