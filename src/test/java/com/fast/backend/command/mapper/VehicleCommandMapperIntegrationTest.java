package com.fast.backend.command.mapper;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VehicleCommandMapperIntegrationTest {

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private VehicleCommandMapper commandMapper;

    @Test
    void commandFields_roundTripAndResultUpdate() {
        insertVehicle("FORKLIFT-COMMAND");
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 1, 10, 0);
        VehicleCommand command = new VehicleCommand();
        command.setCommandId("COMMAND-1");
        command.setVehicleId("FORKLIFT-COMMAND");
        command.setCommand(VehicleCommandType.MOVE);
        command.setTargetSystem(VehicleCommandTargetSystem.ROS2);
        command.setStatus(VehicleCommandStatus.PUBLISHED);
        command.setCreatedAt(createdAt);
        commandMapper.insert(command);

        VehicleCommand inserted = commandMapper.findByCommandId("COMMAND-1").orElseThrow();
        assertThat(inserted.getCommand()).isEqualTo(VehicleCommandType.MOVE);
        assertThat(inserted.getTargetSystem()).isEqualTo(VehicleCommandTargetSystem.ROS2);
        assertThat(inserted.getCreatedAt()).isEqualTo(createdAt);

        LocalDateTime completedAt = createdAt.plusSeconds(5);
        inserted.setStatus(VehicleCommandStatus.SUCCESS);
        inserted.setResultMessage("goal reached");
        inserted.setCompletedAt(completedAt);
        commandMapper.update(inserted);

        VehicleCommand completed = commandMapper.findByCommandId("COMMAND-1").orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(VehicleCommandStatus.SUCCESS);
        assertThat(completed.getResultMessage()).isEqualTo("goal reached");
        assertThat(completed.getCompletedAt()).isEqualTo(completedAt);
    }

    private void insertVehicle(String vehicleId) {
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId);
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        vehicle.setUpdatedAt(now);
        vehicleMapper.insert(vehicle);
    }
}
