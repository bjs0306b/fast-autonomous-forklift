package com.fast.backend.transport.service;

import com.fast.backend.station.domain.StationSession;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.domain.StorageSlot;
import com.fast.backend.storage.domain.StorageSlotStatus;
import com.fast.backend.storage.event.NextCargoCreationRequestedEvent;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.storage.mapper.StorageSlotMapper;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
import com.fast.backend.transport.dto.TransportTaskResponse;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.station.service.StationMeasurementService;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
class TransportTaskServiceIntegrationTest {

    @Autowired private TransportTaskService service;
    @Autowired private CargoMapper cargoMapper;
    @Autowired private StorageSlotMapper slotMapper;
    @Autowired private TransportTaskMapper taskMapper;
    @Autowired private StationMeasurementService measurementService;
    @Autowired private TransportSchedulingService schedulingService;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleStatusMapper;
    @Autowired private ApplicationEvents applicationEvents;

    @Test
    void automaticScheduling_assignsOldestPendingTaskToIdleVehicle() {
        LocalDateTime now = LocalDateTime.now();
        Long oldCargoId = insertCargo(now);
        Long newCargoId = insertCargo(now);

        TransportTask oldTask = pendingTask(
                "TASK-SCHEDULE-OLD", oldCargoId, now.minusMinutes(1));
        TransportTask newTask = pendingTask(
                "TASK-SCHEDULE-NEW", newCargoId, now);
        taskMapper.insert(oldTask);
        taskMapper.insert(newTask);

        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId("FORKLIFT-SCHEDULE");
        vehicle.setName("자동 배정 테스트 차량");
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        vehicle.setUpdatedAt(now);
        vehicleMapper.insert(vehicle);

        VehicleCurrentStatus status = new VehicleCurrentStatus();
        status.setVehicleId(vehicle.getVehicleId());
        status.setStatus(VehicleStatus.IDLE);
        status.setReceivedAt(now);
        vehicleStatusMapper.upsert(status);

        schedulingService.matchNext();

        TransportTask assigned = taskMapper.findById(oldTask.getId()).orElseThrow();
        TransportTask waiting = taskMapper.findById(newTask.getId()).orElseThrow();
        assertThat(assigned.getStatus()).isEqualTo(TaskStatus.ASSIGNED);
        assertThat(assigned.getVehicleId()).isEqualTo(vehicle.getVehicleId());
        assertThat(waiting.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(waiting.getVehicleId()).isNull();
    }

    @Test
    void measurementResult_completesPendingPlacementAndOwnsReservation() {
        LocalDateTime now = LocalDateTime.now();
        Cargo cargo = new Cargo();
        cargo.setCreatedAt(now);
        cargoMapper.insert(cargo);
        StorageSlot slot = new StorageSlot();
        slot.setSlotCode("SLOT-TRANSPORT");
        slot.setUsableHeight(1.00);
        slot.setForkHeight(0.45);
        slot.setDestinationX(4.0);
        slot.setDestinationY(5.0);
        slot.setDestinationHeading(180.0);
        slot.setStatus(StorageSlotStatus.EMPTY);
        slotMapper.insert(slot);

        TransportTaskResponse response =
                service.createTask(new TransportTaskCreateRequest(cargo.getCargoId()));

        assertThat(response.measurementId()).isNull();
        assertThat(response.placement()).isNull();
        TransportTask task = taskMapper.findByTaskCode(response.taskId()).orElseThrow();
        assertThat(taskMapper.updateStatusIfCurrent(
                task.getId(), TaskStatus.PENDING, TaskStatus.ASSIGNED, null, null)).isEqualTo(1);
        assertThat(taskMapper.updateStatusIfCurrent(
                task.getId(), TaskStatus.ASSIGNED, TaskStatus.MOVING_TO_PICKUP, now, null)).isEqualTo(1);
        assertThat(taskMapper.updateStatusIfCurrent(
                task.getId(), TaskStatus.MOVING_TO_PICKUP, TaskStatus.MEASURING, null, null)).isEqualTo(1);
        assertThat(taskMapper.markMeasurementRequested(
                task.getId(), now, now.minusMinutes(5))).isEqualTo(1);
        StationSession session = measurementService.openSession(cargo.getCargoId());
        TransportTask measuring = taskMapper.findById(task.getId()).orElseThrow();
        assertThat(measuring.getStatus()).isEqualTo(TaskStatus.MEASURING);
        assertThat(measuring.getMeasurementSessionId()).isEqualTo(session.getSessionId());
        measurementService.create(new com.fast.backend.station.dto.StationMeasurementCreateRequest(
                session.getSessionId(), "MEASUREMENT-TRANSPORT", "ok",
                0.50, null, "safe", 0.02));

        TransportTask completed = taskMapper.findById(task.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(TaskStatus.PICKING_UP);
        assertThat(completed.getMeasurementId()).isEqualTo("MEASUREMENT-TRANSPORT");
        assertThat(completed.getDestinationSlotCode()).isEqualTo("SLOT-TRANSPORT");
        StorageSlot reserved = slotMapper.findBySlotCode("SLOT-TRANSPORT").orElseThrow();
        assertThat(reserved.getStatus()).isEqualTo(StorageSlotStatus.RESERVED);
        assertThat(reserved.getReservedTaskId()).isEqualTo(task.getId());

        assertThat(applicationEvents.stream(NextCargoCreationRequestedEvent.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.previousCargoId()).isEqualTo(cargo.getCargoId());
                    assertThat(event.measurementId()).isEqualTo("MEASUREMENT-TRANSPORT");
                });
    }

    private Long insertCargo(LocalDateTime createdAt) {
        Cargo cargo = new Cargo();
        cargo.setCreatedAt(createdAt);
        cargoMapper.insert(cargo);
        return cargo.getCargoId();
    }

    private TransportTask pendingTask(String taskCode, Long cargoId, LocalDateTime createdAt) {
        TransportTask task = new TransportTask();
        task.setTaskCode(taskCode);
        task.setCargoId(cargoId);
        task.setStatus(TaskStatus.PENDING);
        task.setCreatedAt(createdAt);
        return task;
    }
}
