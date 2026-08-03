package com.fast.backend.transport.service;

import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransportSchedulingServiceTest {

    private final TransportTaskMapper taskMapper = mock(TransportTaskMapper.class);
    private final VehicleCurrentStatusMapper vehicleStatusMapper = mock(VehicleCurrentStatusMapper.class);
    private final TransportTaskService taskService = mock(TransportTaskService.class);
    private final TransportSchedulingService service = new TransportSchedulingService(
            taskMapper, vehicleStatusMapper, taskService);

    @Test
    void oldestPendingTaskIsMatchedToAvailableIdleVehicle() {
        TransportTask task = new TransportTask();
        task.setTaskCode("TASK-OLD");
        when(taskMapper.findOldestPending()).thenReturn(Optional.of(task));
        when(vehicleStatusMapper.findFirstAvailableIdleVehicleId())
                .thenReturn(Optional.of("FORKLIFT-01"));

        service.matchNext();

        verify(taskService).assign("TASK-OLD", "FORKLIFT-01");
    }

    @Test
    void noPendingTaskDoesNotLookForVehicle() {
        when(taskMapper.findOldestPending()).thenReturn(Optional.empty());

        service.matchNext();

        verify(vehicleStatusMapper, never()).findFirstAvailableIdleVehicleId();
        verify(taskService, never()).assign(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void noAvailableVehicleKeepsTaskPending() {
        TransportTask task = new TransportTask();
        task.setTaskCode("TASK-WAIT");
        when(taskMapper.findOldestPending()).thenReturn(Optional.of(task));
        when(vehicleStatusMapper.findFirstAvailableIdleVehicleId()).thenReturn(Optional.empty());

        service.matchNext();

        verify(taskService, never()).assign(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
