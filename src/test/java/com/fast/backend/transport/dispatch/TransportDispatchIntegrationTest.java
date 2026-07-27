package com.fast.backend.transport.dispatch;

import com.fast.backend.command.dto.VehicleCommandResultMessage;
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
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportCommand;
import com.fast.backend.transport.domain.TransportCommandStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.dto.TransportCommandMessage;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * MQTT 디스패치 + command-result 자동 반영 통합 테스트(prompt48.md 20장 9~34번). 실제 Broker 없이
 * {@link TransportCommandPublisher}를 mock으로 대체한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransportDispatchIntegrationTest {

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

    // ---------- dispatch ----------

    @Test
    void dispatch_success_publishesAndStartsTask() {
        String taskCode = assignedTask("D1", "REAL-D1");
        doNothing().when(publisher).publish(any());

        dispatchService.dispatch(taskCode);

        ArgumentCaptor<TransportCommandMessage> captor = ArgumentCaptor.forClass(TransportCommandMessage.class);
        org.mockito.Mockito.verify(publisher).publish(captor.capture());
        TransportCommandMessage msg = captor.getValue();
        assertThat(msg.commandId()).startsWith("TCMD-");
        assertThat(msg.vehicleId()).isEqualTo("REAL-D1");
        assertThat(msg.pickup().palletId()).isEqualTo("P-D1");
        assertThat(msg.destination().slotCode()).isEqualTo("S-D1");

        TransportCommand cmd = commandOf(taskCode);
        assertThat(cmd.getStatus()).isEqualTo(TransportCommandStatus.PUBLISHED);
        assertThat(transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.MOVING_TO_PICKUP);
    }

    @Test
    void dispatch_pendingTask_fails() {
        String taskCode = createTask("D2"); // 배정 안 함 → PENDING
        assertThatThrownBy(() -> dispatchService.dispatch(taskCode))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TASK_NOT_ASSIGNED);
    }

    @Test
    void dispatch_duplicateActiveCommand_fails() {
        String taskCode = assignedTask("D3", "REAL-D3");
        Long taskId = transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getId();
        // ASSIGNED인 채로 이미 진행 중 command가 있는 상황을 직접 구성
        TransportCommand active = new TransportCommand();
        active.setCommandId("TCMD-DUP");
        active.setTaskId(taskId);
        active.setTaskCode(taskCode);
        active.setVehicleId("REAL-D3");
        active.setCommandType("TRANSPORT");
        active.setStatus(TransportCommandStatus.PUBLISHED);
        active.setCreatedAt(NOW);
        active.setUpdatedAt(NOW);
        transportCommandMapper.insert(active);

        assertThatThrownBy(() -> dispatchService.dispatch(taskCode))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TASK_ALREADY_DISPATCHED);
    }

    @Test
    void dispatch_publishFails_marksPublishFailedAndKeepsTaskAssigned() {
        String taskCode = assignedTask("D4", "REAL-D4");
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        assertThatThrownBy(() -> dispatchService.dispatch(taskCode))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MQTT_DISPATCH_FAILED);

        assertThat(commandOf(taskCode).getStatus()).isEqualTo(TransportCommandStatus.PUBLISH_FAILED);
        assertThat(transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.ASSIGNED);
    }

    // ---------- command-result ----------

    @Test
    void result_success_completesTaskOccupiesSlotStoresPallet() {
        String taskCode = assignedTask("R1", "REAL-R1");
        doNothing().when(publisher).publish(any());
        dispatchService.dispatch(taskCode);
        String commandId = commandOf(taskCode).getCommandId();
        Long slotId = transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getDestinationSlotId();

        resultService.handleResult(result(commandId, "REAL-R1", "SUCCESS"));

        assertThat(commandOf(taskCode).getStatus()).isEqualTo(TransportCommandStatus.SUCCEEDED);
        assertThat(transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.COMPLETED);
        StorageSlot slot = storageSlotMapper.findById(slotId).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(StorageSlotStatus.OCCUPIED);
        assertThat(slot.getStoredCargoId()).isEqualTo("C-R1");
        assertThat(palletMapper.findByPalletId("P-R1").orElseThrow().getStatus()).isEqualTo(PalletStatus.STORED);
    }

    @Test
    void result_fail_failsTaskAndReleasesSlot() {
        String taskCode = assignedTask("R2", "REAL-R2");
        doNothing().when(publisher).publish(any());
        dispatchService.dispatch(taskCode);
        String commandId = commandOf(taskCode).getCommandId();
        Long slotId = transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getDestinationSlotId();

        resultService.handleResult(result(commandId, "REAL-R2", "FAIL"));

        assertThat(commandOf(taskCode).getStatus()).isEqualTo(TransportCommandStatus.FAILED);
        assertThat(transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.FAILED);
        assertThat(storageSlotMapper.findById(slotId).orElseThrow().getStatus()).isEqualTo(StorageSlotStatus.EMPTY);
    }

    @Test
    void result_duplicateSuccess_isIdempotent() {
        String taskCode = assignedTask("R3", "REAL-R3");
        doNothing().when(publisher).publish(any());
        dispatchService.dispatch(taskCode);
        String commandId = commandOf(taskCode).getCommandId();

        resultService.handleResult(result(commandId, "REAL-R3", "SUCCESS"));
        // 두 번째 SUCCESS는 종료 상태라 무시 — 예외 없이 정상 종료, 상태 불변
        resultService.handleResult(result(commandId, "REAL-R3", "SUCCESS"));

        assertThat(commandOf(taskCode).getStatus()).isEqualTo(TransportCommandStatus.SUCCEEDED);
        assertThat(transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void result_vehicleMismatch_isDiscarded() {
        String taskCode = assignedTask("R4", "REAL-R4");
        doNothing().when(publisher).publish(any());
        dispatchService.dispatch(taskCode);
        String commandId = commandOf(taskCode).getCommandId();

        resultService.handleResult(result(commandId, "OTHER-VEHICLE", "SUCCESS"));

        // 폐기: command는 PUBLISHED 유지, task는 MOVING_TO_PICKUP 유지
        assertThat(commandOf(taskCode).getStatus()).isEqualTo(TransportCommandStatus.PUBLISHED);
        assertThat(transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.MOVING_TO_PICKUP);
    }

    @Test
    void result_unknownCommandId_isNoOp() {
        // 존재하지 않는 commandId → 예외 없이 무시(다른 도메인 결과일 수 있음)
        resultService.handleResult(result("TCMD-NONEXISTENT", "REAL-X", "SUCCESS"));
    }

    // --- helpers ---

    private VehicleCommandResultMessage result(String commandId, String vehicleId, String result) {
        return new VehicleCommandResultMessage(commandId, vehicleId, null, null, "TRANSPORT", result,
                null, null, null, null, null, null, "msg", OffsetDateTime.now());
    }

    private TransportCommand commandOf(String taskCode) {
        Long taskId = transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getId();
        return transportCommandMapper.findByTaskId(taskId).get(0);
    }

    private String createTask(String suffix) {
        Cargo cargo = Cargo.create("C-" + suffix, 0.8, 1.0, 0.6);
        cargo.setCreatedAt(NOW);
        cargo.setUpdatedAt(NOW);
        cargoMapper.insert(cargo);

        Pallet pallet = new Pallet();
        pallet.setPalletId("P-" + suffix);
        pallet.setCargoId("C-" + suffix);
        pallet.setPickupX(2.5);
        pallet.setPickupY(1.8);
        pallet.setPickupHeading(90.0);
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
        slot.setDestinationX(8.2);
        slot.setDestinationY(4.5);
        slot.setDestinationHeading(180.0);
        slot.setStatus(StorageSlotStatus.EMPTY);
        slot.setCreatedAt(NOW);
        slot.setUpdatedAt(NOW);
        storageSlotMapper.insert(slot);

        return taskService.createTask(new TransportTaskCreateRequest("C-" + suffix, "P-" + suffix)).taskId();
    }

    private String assignedTask(String suffix, String vehicleId) {
        String taskCode = createTask(suffix);
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
        current.setStatus(VehicleStatus.IDLE);
        current.setReceivedAt(NOW);
        current.setUpdatedAt(NOW);
        vehicleCurrentStatusMapper.upsert(current);

        taskService.assign(taskCode, vehicleId);
        return taskCode;
    }
}
