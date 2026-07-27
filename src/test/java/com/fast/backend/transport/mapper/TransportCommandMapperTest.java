package com.fast.backend.transport.mapper;

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
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportCommand;
import com.fast.backend.transport.domain.TransportCommandStatus;
import com.fast.backend.transport.domain.TransportTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TransportCommand Mapper + schema.sql 검증(prompt48.md 20장 1~8번). 조건부 UPDATE 멱등성 포함.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransportCommandMapperTest {

    @Autowired private CargoMapper cargoMapper;
    @Autowired private PalletMapper palletMapper;
    @Autowired private RackMapper rackMapper;
    @Autowired private RackLevelMapper rackLevelMapper;
    @Autowired private StorageSlotMapper storageSlotMapper;
    @Autowired private TransportTaskMapper transportTaskMapper;
    @Autowired private TransportCommandMapper transportCommandMapper;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void insertFindAndUnique() {
        Long taskId = seedTask("T1");
        TransportCommand cmd = insertCommand("TCMD-1", taskId, "T1", "REAL-F01", TransportCommandStatus.CREATED);
        assertThat(cmd.getId()).isNotNull();
        assertThat(transportCommandMapper.findByCommandId("TCMD-1")).isPresent();
        assertThat(transportCommandMapper.findByTaskId(taskId)).hasSize(1);

        assertThrows(DataIntegrityViolationException.class,
                () -> insertCommand("TCMD-1", taskId, "T1", "REAL-F01", TransportCommandStatus.CREATED));
    }

    @Test
    void existsActiveByTaskId() {
        Long taskId = seedTask("T2");
        insertCommand("TCMD-2", taskId, "T2", "REAL-F01", TransportCommandStatus.PUBLISHED);
        assertThat(transportCommandMapper.existsActiveByTaskId(taskId)).isTrue();
    }

    @Test
    void markPublished_fromCreated() {
        Long taskId = seedTask("T3");
        insertCommand("TCMD-3", taskId, "T3", "REAL-F01", TransportCommandStatus.CREATED);
        int c = transportCommandMapper.markPublished("TCMD-3", NOW, NOW);
        assertThat(c).isEqualTo(1);
        assertThat(transportCommandMapper.findByCommandId("TCMD-3").orElseThrow().getStatus())
                .isEqualTo(TransportCommandStatus.PUBLISHED);
    }

    @Test
    void markSucceeded_fromPublished() {
        Long taskId = seedTask("T4");
        insertCommand("TCMD-4", taskId, "T4", "REAL-F01", TransportCommandStatus.PUBLISHED);
        assertThat(transportCommandMapper.markSucceeded("TCMD-4", NOW, NOW)).isEqualTo(1);
        assertThat(transportCommandMapper.findByCommandId("TCMD-4").orElseThrow().getStatus())
                .isEqualTo(TransportCommandStatus.SUCCEEDED);
    }

    @Test
    void markFailed_fromPublished() {
        Long taskId = seedTask("T5");
        insertCommand("TCMD-5", taskId, "T5", "REAL-F01", TransportCommandStatus.PUBLISHED);
        assertThat(transportCommandMapper.markFailed("TCMD-5", "boom", NOW, NOW)).isEqualTo(1);
        assertThat(transportCommandMapper.findByCommandId("TCMD-5").orElseThrow().getStatus())
                .isEqualTo(TransportCommandStatus.FAILED);
    }

    @Test
    void terminalCommand_conditionalUpdateFailsAsDuplicate() {
        Long taskId = seedTask("T6");
        insertCommand("TCMD-6", taskId, "T6", "REAL-F01", TransportCommandStatus.SUCCEEDED);
        // 이미 SUCCEEDED → PUBLISHED/ACKNOWLEDGED 조건에 안 걸려 0
        assertThat(transportCommandMapper.markSucceeded("TCMD-6", NOW, NOW)).isEqualTo(0);
        assertThat(transportCommandMapper.markFailed("TCMD-6", "x", NOW, NOW)).isEqualTo(0);
    }

    // --- helpers ---

    private TransportCommand insertCommand(
            String commandId, Long taskId, String taskCode, String vehicleId, TransportCommandStatus status) {
        TransportCommand cmd = new TransportCommand();
        cmd.setCommandId(commandId);
        cmd.setTaskId(taskId);
        cmd.setTaskCode(taskCode);
        cmd.setVehicleId(vehicleId);
        cmd.setCommandType("TRANSPORT");
        cmd.setStatus(status);
        cmd.setCreatedAt(NOW);
        cmd.setUpdatedAt(NOW);
        transportCommandMapper.insert(cmd);
        return cmd;
    }

    private Long seedTask(String suffix) {
        Cargo cargo = Cargo.create("C-" + suffix, 0.8, 1.0, 0.6);
        cargo.setCreatedAt(NOW);
        cargo.setUpdatedAt(NOW);
        cargoMapper.insert(cargo);

        Pallet pallet = new Pallet();
        pallet.setPalletId("P-" + suffix);
        pallet.setCargoId("C-" + suffix);
        pallet.setStatus(PalletStatus.WAITING);
        pallet.setCreatedAt(NOW);
        pallet.setUpdatedAt(NOW);
        palletMapper.insert(pallet);

        Rack rack = new Rack();
        rack.setRackCode("RK-" + suffix);
        rack.setCreatedAt(NOW);
        rack.setUpdatedAt(NOW);
        rackMapper.insert(rack);

        RackLevel level = new RackLevel();
        level.setRackId(rack.getId());
        level.setLevelNumber(1);
        level.setClearWidth(1.0);
        level.setClearLength(1.2);
        level.setClearHeight(0.8);
        level.setForkHeight(0.8);
        level.setCreatedAt(NOW);
        level.setUpdatedAt(NOW);
        rackLevelMapper.insert(level);

        StorageSlot slot = new StorageSlot();
        slot.setSlotCode("S-" + suffix);
        slot.setRackLevelId(level.getId());
        slot.setWidth(1.0);
        slot.setLength(1.2);
        slot.setHeight(0.8);
        slot.setStatus(StorageSlotStatus.RESERVED);
        slot.setCreatedAt(NOW);
        slot.setUpdatedAt(NOW);
        storageSlotMapper.insert(slot);

        TransportTask task = new TransportTask();
        task.setTaskCode(suffix);
        task.setCargoId("C-" + suffix);
        task.setPalletId("P-" + suffix);
        task.setVehicleId("REAL-F01");
        task.setDestinationSlotId(slot.getId());
        task.setStatus(TaskStatus.ASSIGNED);
        task.setCreatedAt(NOW);
        task.setUpdatedAt(NOW);
        transportTaskMapper.insert(task);
        return task.getId();
    }
}
