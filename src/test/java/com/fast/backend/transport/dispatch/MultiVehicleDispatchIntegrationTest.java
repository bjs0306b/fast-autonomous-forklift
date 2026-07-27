package com.fast.backend.transport.dispatch;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.command.service.VehicleCommandPublisher;
import com.fast.backend.config.mqtt.MqttTopics;
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
import com.fast.backend.transport.dto.TransportCommandMessage;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 다중 차량 명령 발행 토픽 분리 검증(prompt51.md 6장). Publisher를 Mock으로 대체해 실제 발행 없이
 * 차량별 payload·commandId·토픽 분리와 중복 디스패치 차단을 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MultiVehicleDispatchIntegrationTest {

    @Autowired private TransportTaskService taskService;
    @Autowired private TransportDispatchService dispatchService;
    @Autowired private MqttTopics mqttTopics;
    @Autowired private CargoMapper cargoMapper;
    @Autowired private PalletMapper palletMapper;
    @Autowired private RackMapper rackMapper;
    @Autowired private RackLevelMapper rackLevelMapper;
    @Autowired private StorageSlotMapper storageSlotMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    @MockBean private TransportCommandPublisher publisher;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void differentTasks_dispatchToDistinctVehicleTopicsWithIndependentPayloads() {
        doNothing().when(publisher).publish(any());
        String t1 = assignedTask("MD1", "REAL-F01");
        String t2 = assignedTask("MD2", "REAL-F02");
        String t3 = assignedTask("MD3", "SIM-F01");

        dispatchService.dispatch(t1);
        dispatchService.dispatch(t2);
        dispatchService.dispatch(t3);

        ArgumentCaptor<TransportCommandMessage> captor = ArgumentCaptor.forClass(TransportCommandMessage.class);
        verify(publisher, times(3)).publish(captor.capture());
        List<TransportCommandMessage> msgs = captor.getAllValues();

        TransportCommandMessage m1 = byVehicle(msgs, "REAL-F01");
        TransportCommandMessage m2 = byVehicle(msgs, "REAL-F02");
        TransportCommandMessage m3 = byVehicle(msgs, "SIM-F01");

        // payload vehicleId가 각자 맞고, 서로 다른 pallet/task를 담는다(교차 오염 없음)
        assertThat(m1.taskId()).isEqualTo(t1);
        assertThat(m1.pickup().palletId()).isEqualTo("P-MD1");
        assertThat(m2.pickup().palletId()).isEqualTo("P-MD2");
        assertThat(m3.pickup().palletId()).isEqualTo("P-MD3");

        // commandId가 작업별로 독립적(모두 다름)
        assertThat(List.of(m1.commandId(), m2.commandId(), m3.commandId())).doesNotHaveDuplicates();

        // 차량별 발행 토픽이 정확히 분리된다
        assertThat(mqttTopics.vehicleCommand("REAL-F01")).isEqualTo("forklift/REAL-F01/command");
        assertThat(mqttTopics.vehicleCommand("REAL-F02")).isEqualTo("forklift/REAL-F02/command");
        assertThat(mqttTopics.vehicleCommand("SIM-F01")).isEqualTo("forklift/SIM-F01/command");
        assertThat(mqttTopics.vehicleCommand("REAL-F01"))
                .isNotEqualTo(mqttTopics.vehicleCommand("REAL-F02"));
    }

    @Test
    void duplicateDispatchIsBlocked() {
        doNothing().when(publisher).publish(any());
        String t1 = assignedTask("MD4", "REAL-F01");
        dispatchService.dispatch(t1); // 성공 → MOVING_TO_PICKUP

        assertThatThrownBy(() -> dispatchService.dispatch(t1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TASK_NOT_ASSIGNED); // 더 이상 ASSIGNED가 아님
    }

    @Test
    void commandPolicy_isQos1AndNotRetained() {
        assertThat(VehicleCommandPublisher.COMMAND_QOS).isEqualTo(1);
        assertThat(VehicleCommandPublisher.COMMAND_RETAINED).isFalse();
    }

    private TransportCommandMessage byVehicle(List<TransportCommandMessage> msgs, String vehicleId) {
        return msgs.stream().filter(m -> m.vehicleId().equals(vehicleId)).findFirst().orElseThrow();
    }

    private String assignedTask(String suffix, String vehicleId) {
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
        return taskCode;
    }
}
