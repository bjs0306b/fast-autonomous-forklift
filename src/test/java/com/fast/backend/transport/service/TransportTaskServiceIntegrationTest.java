package com.fast.backend.transport.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.domain.Pallet;
import com.fast.backend.storage.domain.PalletStatus;
import com.fast.backend.storage.domain.Rack;
import com.fast.backend.storage.domain.RackLevel;
import com.fast.backend.storage.domain.StorageSlot;
import com.fast.backend.storage.domain.StorageSlotStatus;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.storage.mapper.PalletMapper;
import com.fast.backend.storage.mapper.RackLevelMapper;
import com.fast.backend.storage.mapper.RackMapper;
import com.fast.backend.storage.mapper.StorageSlotMapper;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
import com.fast.backend.transport.dto.TransportTaskResponse;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운반 작업 생성·배정·상태전이 통합 테스트(prompt47.md 15장 11~34번). 실제 H2 + MyBatis로 관통한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransportTaskServiceIntegrationTest {

    @Autowired private TransportTaskService service;
    @Autowired private CargoMapper cargoMapper;
    @Autowired private PalletMapper palletMapper;
    @Autowired private RackMapper rackMapper;
    @Autowired private RackLevelMapper rackLevelMapper;
    @Autowired private StorageSlotMapper storageSlotMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void createTask_success_reservesSlotAndReturnsPlacement() {
        seedCargoPallet("C1", "P1");
        Long slotId = insertSlot("A-01-01", 1, 1.0, 1.2, 0.8);

        TransportTaskResponse res = service.createTask(new TransportTaskCreateRequest("C1", "P1"));

        assertThat(res.status().name()).isEqualTo("PENDING");
        assertThat(res.placement().slotCode()).isEqualTo("A-01-01");
        assertThat(res.placement().orientation()).isNotBlank();
        StorageSlot slot = storageSlotMapper.findById(slotId).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(StorageSlotStatus.RESERVED);
        assertThat(slot.getReservedTaskId()).isEqualTo(res.taskId());
    }

    @Test
    void createTask_noFittingSlot_throws() {
        seedCargoPallet("C2", "P2");
        insertSlot("A-99-99", 1, 0.1, 0.1, 0.1); // 너무 작아 들어가지 않음

        assertThatThrownBy(() -> service.createTask(new TransportTaskCreateRequest("C2", "P2")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NO_AVAILABLE_STORAGE_SLOT);
    }

    @Test
    void createTask_duplicatePallet_throws() {
        seedCargoPallet("C3", "P3");
        insertSlot("A-03-01", 1, 1.0, 1.2, 0.8);
        insertSlot("A-03-02", 1, 1.0, 1.2, 0.8);
        service.createTask(new TransportTaskCreateRequest("C3", "P3"));

        assertThatThrownBy(() -> service.createTask(new TransportTaskCreateRequest("C3", "P3")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PALLET_ALREADY_ASSIGNED);
    }

    @Test
    void assign_idleVehicle_success() {
        seedCargoPallet("C4", "P4");
        insertSlot("A-04-01", 1, 1.0, 1.2, 0.8);
        String taskCode = service.createTask(new TransportTaskCreateRequest("C4", "P4")).taskId();
        registerVehicle("REAL-F01", VehicleStatus.IDLE);

        TransportTaskResponse res = service.assign(taskCode, "REAL-F01");

        assertThat(res.status().name()).isEqualTo("ASSIGNED");
        assertThat(res.vehicleId()).isEqualTo("REAL-F01");
        assertThat(palletMapper.findByPalletId("P4").orElseThrow().getStatus()).isEqualTo(PalletStatus.ASSIGNED);
    }

    @Test
    void assign_nonIdleVehicle_throws() {
        seedCargoPallet("C5", "P5");
        insertSlot("A-05-01", 1, 1.0, 1.2, 0.8);
        String taskCode = service.createTask(new TransportTaskCreateRequest("C5", "P5")).taskId();
        registerVehicle("REAL-F02", VehicleStatus.ACTIVE);

        assertThatThrownBy(() -> service.assign(taskCode, "REAL-F02"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VEHICLE_NOT_AVAILABLE);
    }

    @Test
    void assign_alreadyAssignedTask_throws() {
        seedCargoPallet("C6", "P6");
        insertSlot("A-06-01", 1, 1.0, 1.2, 0.8);
        String taskCode = service.createTask(new TransportTaskCreateRequest("C6", "P6")).taskId();
        registerVehicle("REAL-F03", VehicleStatus.IDLE);
        registerVehicle("REAL-F04", VehicleStatus.IDLE);
        service.assign(taskCode, "REAL-F03");

        assertThatThrownBy(() -> service.assign(taskCode, "REAL-F04"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TASK_ALREADY_ASSIGNED);
    }

    @Test
    void assign_vehicleWithActiveTask_throws() {
        seedCargoPallet("C7", "P7");
        seedCargoPallet("C7b", "P7b");
        insertSlot("A-07-01", 1, 1.0, 1.2, 0.8);
        insertSlot("A-07-02", 1, 1.0, 1.2, 0.8);
        String t1 = service.createTask(new TransportTaskCreateRequest("C7", "P7")).taskId();
        String t2 = service.createTask(new TransportTaskCreateRequest("C7b", "P7b")).taskId();
        registerVehicle("REAL-F05", VehicleStatus.IDLE);
        service.assign(t1, "REAL-F05");

        assertThatThrownBy(() -> service.assign(t2, "REAL-F05"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VEHICLE_ALREADY_ASSIGNED);
    }

    @Test
    void changeStatus_invalidTransition_blocked() {
        seedCargoPallet("C8", "P8");
        insertSlot("A-08-01", 1, 1.0, 1.2, 0.8);
        String taskCode = service.createTask(new TransportTaskCreateRequest("C8", "P8")).taskId();

        assertThatThrownBy(() -> service.changeStatus(taskCode, "TRANSPORTING"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TASK_STATUS_TRANSITION);
    }

    @Test
    void fullLifecycle_completed_occupiesSlotAndStoresCargo() {
        seedCargoPallet("C9", "P9");
        Long slotId = insertSlot("A-09-01", 1, 1.0, 1.2, 0.8);
        String taskCode = service.createTask(new TransportTaskCreateRequest("C9", "P9")).taskId();
        registerVehicle("REAL-F06", VehicleStatus.IDLE);
        service.assign(taskCode, "REAL-F06");

        service.changeStatus(taskCode, "MOVING_TO_PICKUP");
        service.changeStatus(taskCode, "PICKING_UP");
        assertThat(palletMapper.findByPalletId("P9").orElseThrow().getStatus()).isEqualTo(PalletStatus.PICKED_UP);
        service.changeStatus(taskCode, "TRANSPORTING");
        assertThat(palletMapper.findByPalletId("P9").orElseThrow().getStatus()).isEqualTo(PalletStatus.TRANSPORTING);
        service.changeStatus(taskCode, "PLACING");
        TransportTaskResponse completed = service.changeStatus(taskCode, "COMPLETED");

        assertThat(completed.status().name()).isEqualTo("COMPLETED");
        StorageSlot slot = storageSlotMapper.findById(slotId).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(StorageSlotStatus.OCCUPIED);
        assertThat(slot.getStoredCargoId()).isEqualTo("C9");
        assertThat(palletMapper.findByPalletId("P9").orElseThrow().getStatus()).isEqualTo(PalletStatus.STORED);
    }

    @Test
    void failed_releasesSlotToEmpty() {
        seedCargoPallet("C10", "P10");
        Long slotId = insertSlot("A-10-01", 1, 1.0, 1.2, 0.8);
        String taskCode = service.createTask(new TransportTaskCreateRequest("C10", "P10")).taskId();
        registerVehicle("REAL-F07", VehicleStatus.IDLE);
        service.assign(taskCode, "REAL-F07");

        service.changeStatus(taskCode, "FAILED");

        StorageSlot slot = storageSlotMapper.findById(slotId).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(StorageSlotStatus.EMPTY);
        assertThat(slot.getReservedTaskId()).isNull();
    }

    @Test
    void cancelled_releasesSlotAndWaitsPallet() {
        seedCargoPallet("C11", "P11");
        Long slotId = insertSlot("A-11-01", 1, 1.0, 1.2, 0.8);
        String taskCode = service.createTask(new TransportTaskCreateRequest("C11", "P11")).taskId();

        service.changeStatus(taskCode, "CANCELLED");

        StorageSlot slot = storageSlotMapper.findById(slotId).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(StorageSlotStatus.EMPTY);
        assertThat(palletMapper.findByPalletId("P11").orElseThrow().getStatus()).isEqualTo(PalletStatus.WAITING);
    }

    // --- helpers ---

    private void seedCargoPallet(String cargoId, String palletId) {
        Cargo cargo = Cargo.create(cargoId, 0.8, 1.0, 0.6);
        cargo.setCreatedAt(NOW);
        cargo.setUpdatedAt(NOW);
        cargoMapper.insert(cargo);

        Pallet pallet = new Pallet();
        pallet.setPalletId(palletId);
        pallet.setCargoId(cargoId);
        pallet.setPickupX(2.5);
        pallet.setPickupY(1.8);
        pallet.setPickupHeading(90.0);
        pallet.setStatus(PalletStatus.WAITING);
        pallet.setCreatedAt(NOW);
        pallet.setUpdatedAt(NOW);
        palletMapper.insert(pallet);
    }

    private Long insertSlot(String slotCode, int level, double w, double l, double h) {
        Rack rack = new Rack();
        rack.setRackCode("RACK-" + slotCode);
        rack.setRackName("r");
        rack.setPositionX(0.0);
        rack.setPositionY(0.0);
        rack.setCreatedAt(NOW);
        rack.setUpdatedAt(NOW);
        rackMapper.insert(rack);

        RackLevel rl = new RackLevel();
        rl.setRackId(rack.getId());
        rl.setLevelNumber(level);
        rl.setClearWidth(w);
        rl.setClearLength(l);
        rl.setClearHeight(h);
        rl.setForkHeight(0.8);
        rl.setCreatedAt(NOW);
        rl.setUpdatedAt(NOW);
        rackLevelMapper.insert(rl);

        StorageSlot slot = new StorageSlot();
        slot.setSlotCode(slotCode);
        slot.setRackLevelId(rl.getId());
        slot.setWidth(w);
        slot.setLength(l);
        slot.setHeight(h);
        slot.setDestinationX(8.2);
        slot.setDestinationY(4.5);
        slot.setDestinationHeading(180.0);
        slot.setStatus(StorageSlotStatus.EMPTY);
        slot.setCreatedAt(NOW);
        slot.setUpdatedAt(NOW);
        storageSlotMapper.insert(slot);
        return slot.getId();
    }

    private void registerVehicle(String vehicleId, VehicleStatus status) {
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId);
        vehicle.setSource(VehicleSource.REAL);
        vehicle.setActive(true);
        vehicle.setCreatedAt(NOW);
        vehicle.setUpdatedAt(NOW);
        vehicleMapper.insert(vehicle);

        VehicleCurrentStatus current = new VehicleCurrentStatus();
        current.setVehicleId(vehicleId);
        current.setStatus(status);
        current.setReceivedAt(NOW);
        current.setUpdatedAt(NOW);
        vehicleCurrentStatusMapper.upsert(current);
    }
}
