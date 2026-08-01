package com.fast.backend.transport.service;

import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.domain.StorageSlot;
import com.fast.backend.storage.domain.StorageSlotStatus;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.storage.mapper.StorageSlotMapper;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
import com.fast.backend.transport.dto.TransportTaskResponse;
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
    @Autowired private StationSessionMapper sessionMapper;
    @Autowired private StationMeasurementMapper measurementMapper;
    @Autowired private StorageSlotMapper slotMapper;

    @Test
    void createTask_usesLatestEligibleMeasurementAndOwnsReservation() {
        LocalDateTime now = LocalDateTime.now();
        Cargo cargo = new Cargo();
        cargo.setCargoId("CARGO-TRANSPORT");
        cargo.setCreatedAt(now);
        cargoMapper.insert(cargo);
        sessionMapper.insert(new StationSession("SESSION-TRANSPORT", cargo.getCargoId()));

        StationMeasurement measurement = new StationMeasurement();
        measurement.setMeasurementId("MEASUREMENT-TRANSPORT");
        measurement.setSessionId("SESSION-TRANSPORT");
        measurement.setStatus(StationMeasurementStatus.OK);
        measurement.setCargoHeight(0.50);
        measurement.setTippingLevel("SAFE");
        measurement.setOverhangRatio(0.02);
        measurement.setCreatedAt(now);
        measurementMapper.insert(measurement);

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

        assertThat(response.measurementId()).isEqualTo("MEASUREMENT-TRANSPORT");
        assertThat(response.placement().slotCode()).isEqualTo("SLOT-TRANSPORT");
        StorageSlot reserved = slotMapper.findBySlotCode("SLOT-TRANSPORT").orElseThrow();
        assertThat(reserved.getStatus()).isEqualTo(StorageSlotStatus.RESERVED);
        assertThat(reserved.getReservedTaskId()).isNotNull();
    }
}
