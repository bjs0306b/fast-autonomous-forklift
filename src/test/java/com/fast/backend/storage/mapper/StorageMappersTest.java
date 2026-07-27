package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.domain.Pallet;
import com.fast.backend.storage.domain.PalletStatus;
import com.fast.backend.storage.domain.Rack;
import com.fast.backend.storage.domain.RackLevel;
import com.fast.backend.storage.domain.StorageSlot;
import com.fast.backend.storage.domain.StorageSlotStatus;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 적재·운반 Mapper와 schema.sql(H2 MySQL 호환)이 실제로 맞물리는지 검증(prompt47.md 15장 1~10번).
 * 각 테스트는 클래스 트랜잭션 롤백으로 독립적이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StorageMappersTest {

    @Autowired private CargoMapper cargoMapper;
    @Autowired private PalletMapper palletMapper;
    @Autowired private RackMapper rackMapper;
    @Autowired private RackLevelMapper rackLevelMapper;
    @Autowired private StorageSlotMapper storageSlotMapper;
    @Autowired private TransportTaskMapper transportTaskMapper;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void cargo_insertAndFind() {
        Cargo cargo = insertCargo("CARGO-M1", 0.8, 1.0, 0.6);
        assertThat(cargo.getId()).isNotNull();
        assertThat(cargoMapper.findByCargoId("CARGO-M1")).isPresent();
        assertThat(cargoMapper.existsByCargoId("CARGO-M1")).isTrue();
    }

    @Test
    void cargo_duplicateCargoId_violatesUnique() {
        insertCargo("CARGO-DUP", 0.8, 1.0, 0.6);
        assertThrows(DataIntegrityViolationException.class, () -> insertCargo("CARGO-DUP", 0.5, 0.5, 0.5));
    }

    @Test
    void pallet_insertAndFind() {
        insertCargo("CARGO-P1", 0.8, 1.0, 0.6);
        Pallet pallet = insertPallet("PALLET-P1", "CARGO-P1");
        assertThat(pallet.getId()).isNotNull();
        assertThat(palletMapper.findByPalletId("PALLET-P1")).isPresent();
        assertThat(palletMapper.existsByPalletId("PALLET-P1")).isTrue();
    }

    @Test
    void rackLevelSlot_insertAndPlacementQuery() {
        Long slotId = insertSlot("A-01-01", 1.0, 1.2, 0.8, StorageSlotStatus.EMPTY);
        assertThat(storageSlotMapper.findById(slotId)).isPresent();

        List<StorageSlotPlacementRow> rows = storageSlotMapper.findAllEmptySlotsForPlacement();
        assertThat(rows).extracting(StorageSlotPlacementRow::getSlotCode).contains("A-01-01");
        StorageSlotPlacementRow row = rows.stream()
                .filter(r -> r.getSlotCode().equals("A-01-01")).findFirst().orElseThrow();
        assertThat(row.getRackCode()).isNotBlank();
        assertThat(row.getSlotWidth()).isEqualTo(1.0);
    }

    @Test
    void reserveIfEmpty_succeedsOnceThenFails() {
        Long slotId = insertSlot("A-01-02", 1.0, 1.2, 0.8, StorageSlotStatus.EMPTY);

        int first = storageSlotMapper.reserveIfEmpty(slotId, "TASK-X", NOW);
        int second = storageSlotMapper.reserveIfEmpty(slotId, "TASK-Y", NOW);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0); // 이미 RESERVED → 조건부 UPDATE 실패
        assertThat(storageSlotMapper.findById(slotId).orElseThrow().getStatus())
                .isEqualTo(StorageSlotStatus.RESERVED);
    }

    @Test
    void task_insertAndActiveChecks() {
        insertCargo("CARGO-T1", 0.8, 1.0, 0.6);
        insertPallet("PALLET-T1", "CARGO-T1");
        Long slotId = insertSlot("A-02-01", 1.0, 1.2, 0.8, StorageSlotStatus.EMPTY);

        TransportTask task = new TransportTask();
        task.setTaskCode("TASK-INS-1");
        task.setCargoId("CARGO-T1");
        task.setPalletId("PALLET-T1");
        task.setVehicleId("REAL-F01");
        task.setDestinationSlotId(slotId);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setCreatedAt(NOW);
        task.setUpdatedAt(NOW);
        transportTaskMapper.insert(task);

        assertThat(transportTaskMapper.findByTaskCode("TASK-INS-1")).isPresent();
        assertThat(transportTaskMapper.existsActiveTaskByVehicleId("REAL-F01")).isTrue();
        assertThat(transportTaskMapper.existsActiveTaskByPalletId("PALLET-T1")).isTrue();
        assertThat(transportTaskMapper.existsOpenTaskByPalletId("PALLET-T1")).isTrue();
        assertThat(transportTaskMapper.existsActiveTaskBySlotId(slotId)).isTrue();
    }

    // --- helpers ---

    private Cargo insertCargo(String cargoId, double w, double l, double h) {
        Cargo cargo = Cargo.create(cargoId, w, l, h);
        cargo.setCreatedAt(NOW);
        cargo.setUpdatedAt(NOW);
        cargoMapper.insert(cargo);
        return cargo;
    }

    private Pallet insertPallet(String palletId, String cargoId) {
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
        return pallet;
    }

    private Long insertSlot(String slotCode, double w, double l, double h, StorageSlotStatus status) {
        Rack rack = new Rack();
        rack.setRackCode("RACK-" + slotCode);
        rack.setRackName("test rack");
        rack.setPositionX(0.0);
        rack.setPositionY(0.0);
        rack.setCreatedAt(NOW);
        rack.setUpdatedAt(NOW);
        rackMapper.insert(rack);

        RackLevel level = new RackLevel();
        level.setRackId(rack.getId());
        level.setLevelNumber(1);
        level.setClearWidth(w);
        level.setClearLength(l);
        level.setClearHeight(h);
        level.setForkHeight(0.8);
        level.setCreatedAt(NOW);
        level.setUpdatedAt(NOW);
        rackLevelMapper.insert(level);

        StorageSlot slot = new StorageSlot();
        slot.setSlotCode(slotCode);
        slot.setRackLevelId(level.getId());
        slot.setWidth(w);
        slot.setLength(l);
        slot.setHeight(h);
        slot.setDestinationX(8.2);
        slot.setDestinationY(4.5);
        slot.setDestinationHeading(180.0);
        slot.setStatus(status);
        slot.setCreatedAt(NOW);
        slot.setUpdatedAt(NOW);
        storageSlotMapper.insert(slot);
        return slot.getId();
    }
}
