package com.fast.backend.command.service;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.command.dto.VehicleCommandResultMessage;
import com.fast.backend.command.mapper.VehicleCommandMapper;
import com.fast.backend.station.service.StationMeasurementRequestWorkflow;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleCommandResultServiceTest {

    @Test
    void successfulTaskMove_startsMeasurementWorkflow() {
        VehicleCommandMapper mapper = mock(VehicleCommandMapper.class);
        VehicleWebSocketBroadcaster broadcaster = mock(VehicleWebSocketBroadcaster.class);
        StationMeasurementRequestWorkflow workflow = mock(StationMeasurementRequestWorkflow.class);
        VehicleCommandResultService service = new VehicleCommandResultService(mapper, broadcaster, workflow);

        VehicleCommand command = new VehicleCommand();
        command.setCommandId("COMMAND-01");
        command.setTaskId(7L);
        command.setVehicleId("FORKLIFT-01");
        command.setCommand(VehicleCommandType.MOVE);
        command.setTargetSystem(VehicleCommandTargetSystem.ROS2);
        command.setStatus(VehicleCommandStatus.PUBLISHED);
        when(mapper.findByCommandIdForUpdate("COMMAND-01")).thenReturn(Optional.of(command));

        OffsetDateTime completedAt = OffsetDateTime.parse("2026-08-02T10:00:00+09:00");
        service.handleResult(new VehicleCommandResultMessage(
                "COMMAND-01", "FORKLIFT-01", "ROS2", "MOVE", "MOVE",
                "SUCCESS", "goal reached", completedAt));

        verify(mapper).update(command);
        verify(workflow).handleMoveResult(command, VehicleCommandStatus.SUCCESS);
    }
}
