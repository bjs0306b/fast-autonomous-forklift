package com.fast.backend.transport.dispatch;

import com.fast.backend.command.dto.VehicleCommandResultMessage;
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
import com.fast.backend.transport.domain.TransportCommandStatus;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
import com.fast.backend.transport.mapper.TransportCommandMapper;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.transport.service.TransportTaskService;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

/**
 * 다중 차량 command-result 분리 검증(prompt51.md 8장). 처리 순서가 뒤바뀌어도 commandId·vehicleId 기준으로
 * 올바른 Task에만 반영되고, 다른 Task/차량은 영향받지 않음을 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MultiVehicleCommandResultIntegrationTest {

    @Autowired private TransportTaskService taskService;
    @Autowired private TransportDispatchService dispatchService;
    @Autowired private TransportCommandResultService resultService;
    @Autowired private CargoMapper cargoMapper;
    @Autowired private PalletMapper palletMapper;
    @Autowired private RackMapper rackMapper;
    @Autowired private RackLevelMapper rackLevelMapper;
    @Autowired private StorageSlotMapper storageSlotMapper;
    @Autowired private TransportTaskMapper transportTaskMapper;
    @Autowired private TransportCommandMapper transportCommandMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    @MockBean private TransportCommandPublisher publisher;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void outOfOrderResults_matchByCommandIdToCorrectTask() {
        doNothing().when(publisher).publish(any());
        String tA = dispatched("MR-A", "REAL-F01");
        String tB = dispatched("MR-B", "REAL-F02");
        String tC = dispatched("MR-C", "SIM-F01");
        String cmdA = commandId(tA);
        String cmdB = commandId(tB);
        String cmdC = commandId(tC);

        // 생성 순서(A,B,C)와 다른 순서(C, A, B)로 결과 수신
        resultService.handleResult(result(cmdC, "SIM-F01", "SUCCESS"));
        resultService.handleResult(result(cmdA, "REAL-F01", "SUCCESS"));
        resultService.handleResult(result(cmdB, "REAL-F02", "FAIL"));

        assertThat(status(tA)).isEqualTo("COMPLETED");
        assertThat(status(tB)).isEqualTo("FAILED");
        assertThat(status(tC)).isEqualTo("COMPLETED");
        // command도 각자 종료 상태
        assertThat(transportCommandMapper.findByCommandId(cmdA).orElseThrow().getStatus())
                .isEqualTo(TransportCommandStatus.SUCCEEDED);
        assertThat(transportCommandMapper.findByCommandId(cmdB).orElseThrow().getStatus())
                .isEqualTo(TransportCommandStatus.FAILED);
    }

    @Test
    void oneResultDoesNotAffectOtherTasks() {
        doNothing().when(publisher).publish(any());
        String tA = dispatched("MR-D", "REAL-F01");
        String tB = dispatched("MR-E", "REAL-F02");

        resultService.handleResult(result(commandId(tA), "REAL-F01", "SUCCESS"));

        assertThat(status(tA)).isEqualTo("COMPLETED");
        assertThat(status(tB)).isEqualTo("MOVING_TO_PICKUP"); // 그대로
    }

    @Test
    void vehicleIdMismatch_isRejected() {
        doNothing().when(publisher).publish(any());
        String tA = dispatched("MR-F", "REAL-F01");
        String cmdA = commandId(tA);

        resultService.handleResult(result(cmdA, "REAL-F02", "SUCCESS")); // 잘못된 vehicleId

        assertThat(transportCommandMapper.findByCommandId(cmdA).orElseThrow().getStatus())
                .isEqualTo(TransportCommandStatus.PUBLISHED); // 변경 없음
        assertThat(status(tA)).isEqualTo("MOVING_TO_PICKUP");
    }

    @Test
    void unknownCommandId_isNoOp() {
        doNothing().when(publisher).publish(any());
        String tA = dispatched("MR-G", "REAL-F01");

        resultService.handleResult(result("TCMD-DOES-NOT-EXIST", "REAL-F01", "SUCCESS"));

        assertThat(status(tA)).isEqualTo("MOVING_TO_PICKUP"); // 무영향
    }

    @Test
    void duplicateSuccess_isIdempotent() {
        doNothing().when(publisher).publish(any());
        String tA = dispatched("MR-H", "REAL-F01");
        String cmdA = commandId(tA);

        resultService.handleResult(result(cmdA, "REAL-F01", "SUCCESS"));
        resultService.handleResult(result(cmdA, "REAL-F01", "SUCCESS")); // 중복 → 무시

        assertThat(status(tA)).isEqualTo("COMPLETED");
        assertThat(transportCommandMapper.findByCommandId(cmdA).orElseThrow().getStatus())
                .isEqualTo(TransportCommandStatus.SUCCEEDED);
    }

    // --- helpers ---

    private VehicleCommandResultMessage result(String commandId, String vehicleId, String result) {
        return new VehicleCommandResultMessage(commandId, vehicleId, null, null, "TRANSPORT", result,
                null, null, null, null, null, null, "msg", OffsetDateTime.now());
    }

    private String status(String taskCode) {
        return transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getStatus().name();
    }

    private String commandId(String taskCode) {
        Long taskId = transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getId();
        return transportCommandMapper.findByTaskId(taskId).get(0).getCommandId();
    }

    private String dispatched(String suffix, String vehicleId) {
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
        slot.setStatus(StorageSlotStatus.EMPTY);
        slot.setCreatedAt(NOW);
        slot.setUpdatedAt(NOW);
        storageSlotMapper.insert(slot);

        String taskCode = taskService.createTask(new TransportTaskCreateRequest("C-" + suffix, "P-" + suffix)).taskId();
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(vehicleId);
        v.setSource(vehicleId.startsWith("SIM") ? VehicleSource.SIMULATION : VehicleSource.REAL);
        v.setActive(true);
        v.setCreatedAt(NOW);
        v.setUpdatedAt(NOW);
        vehicleMapper.insert(v);
        VehicleCurrentStatus cur = new VehicleCurrentStatus();
        cur.setVehicleId(vehicleId);
        cur.setStatus(VehicleStatus.IDLE);
        cur.setReceivedAt(NOW);
        cur.setUpdatedAt(NOW);
        vehicleCurrentStatusMapper.upsert(cur);

        taskService.assign(taskCode, vehicleId);
        dispatchService.dispatch(taskCode);
        return taskCode;
    }
}
