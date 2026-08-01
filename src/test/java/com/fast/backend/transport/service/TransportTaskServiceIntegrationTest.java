package com.fast.backend.transport.service;

import com.fast.backend.station.domain.StationSession;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.domain.StorageSlot;
import com.fast.backend.storage.domain.StorageSlotStatus;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.storage.mapper.StorageSlotMapper;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
import com.fast.backend.transport.dto.TransportTaskResponse;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.station.service.StationMeasurementService;
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
class TransportTaskServiceIntegrationTest {

    @Autowired private TransportTaskService service;
    @Autowired private CargoMapper cargoMapper;
    @Autowired private StorageSlotMapper slotMapper;
    @Autowired private TransportTaskMapper taskMapper;
    @Autowired private StationMeasurementService measurementService;

    @Test
    void measurementResult_completesPendingPlacementAndOwnsReservation() {
        LocalDateTime now = LocalDateTime.now();
        Cargo cargo = new Cargo();
        cargo.setCargoId("CARGO-TRANSPORT");
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
                task.getId(), TaskStatus.PENDING, TaskStatus.ASSIGNED, null, null, null)).isEqualTo(1);
        assertThat(taskMapper.updateStatusIfCurrent(
                task.getId(), TaskStatus.ASSIGNED, TaskStatus.MOVING_TO_PICKUP, now, null, null)).isEqualTo(1);
        StationSession session = measurementService.openSession(cargo.getCargoId());
        assertThat(taskMapper.startMeasurement(task.getId(), session.getSessionId())).isEqualTo(1);
        measurementService.create(new com.fast.backend.station.dto.StationMeasurementCreateRequest(
                session.getSessionId(), "MEASUREMENT-TRANSPORT", "ok",
                0.50, "safe", 0.02));

        TransportTask completed = taskMapper.findById(task.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(TaskStatus.PICKING_UP);
        assertThat(completed.getMeasurementId()).isEqualTo("MEASUREMENT-TRANSPORT");
        assertThat(completed.getDestinationSlotCode()).isEqualTo("SLOT-TRANSPORT");
        StorageSlot reserved = slotMapper.findBySlotCode("SLOT-TRANSPORT").orElseThrow();
        assertThat(reserved.getStatus()).isEqualTo(StorageSlotStatus.RESERVED);
        assertThat(reserved.getReservedTaskId()).isEqualTo(task.getId());
    }
}
