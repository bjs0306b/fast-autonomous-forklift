package com.fast.backend.transport.controller;

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
import com.fast.backend.transport.dispatch.TransportCommandPublisher;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
import com.fast.backend.transport.service.TransportTaskService;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 디스패치 API 종단 테스트(prompt48.md 20장 38~41번). publisher는 mock으로 대체한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TransportDispatchControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TransportTaskService taskService;
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
    void dispatch_assignedTask_success() throws Exception {
        String taskCode = assignedTask("API-D1", "API-V1");
        doNothing().when(publisher).publish(any());

        mockMvc.perform(post("/api/transport-tasks/{taskId}/dispatch", taskCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value(taskCode))
                .andExpect(jsonPath("$.data.commandStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.taskStatus").value("MOVING_TO_PICKUP"));
    }

    @Test
    void dispatch_pendingTask_conflict() throws Exception {
        String taskCode = createTask("API-D2"); // 미배정 PENDING

        mockMvc.perform(post("/api/transport-tasks/{taskId}/dispatch", taskCode))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // --- helpers ---

    private String createTask(String suffix) {
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
