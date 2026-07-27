package com.fast.backend.transport.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.storage.domain.Rack;
import com.fast.backend.storage.domain.RackLevel;
import com.fast.backend.storage.domain.StorageSlot;
import com.fast.backend.storage.domain.StorageSlotStatus;
import com.fast.backend.storage.mapper.RackLevelMapper;
import com.fast.backend.storage.mapper.RackMapper;
import com.fast.backend.storage.mapper.StorageSlotMapper;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 적재·운반 REST API 종단 테스트(prompt47.md 15장 35~42번). Controller→Service→Mapper→H2 관통.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TransportTaskControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RackMapper rackMapper;
    @Autowired private RackLevelMapper rackLevelMapper;
    @Autowired private StorageSlotMapper storageSlotMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void cargo_register_success_andValidationFailure() throws Exception {
        mockMvc.perform(post("/api/cargos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cargoId\":\"API-C1\",\"width\":0.8,\"length\":1.0,\"height\":0.6}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cargoId").value("API-C1"))
                .andExpect(jsonPath("$.data.volume").value(0.8 * 1.0 * 0.6));

        mockMvc.perform(post("/api/cargos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cargoId\":\"API-C1-BAD\",\"width\":-0.1,\"length\":1.0,\"height\":0.6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void pallet_register_success_andDuplicateFailure() throws Exception {
        registerCargo("API-C2");
        String body = "{\"palletId\":\"API-P2\",\"cargoId\":\"API-C2\",\"pickup\":{\"x\":2.5,\"y\":1.8,\"heading\":90.0}}";

        mockMvc.perform(post("/api/pallets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.palletId").value("API-P2"))
                .andExpect(jsonPath("$.data.status").value("WAITING"));

        mockMvc.perform(post("/api/pallets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void transportTask_create_detail_list_assign_status() throws Exception {
        registerCargo("API-C3");
        registerPallet("API-P3", "API-C3");
        insertSlot("API-A-01", 1.0, 1.2, 0.8);
        registerVehicle("API-F01", VehicleStatus.IDLE);

        // create
        String createBody = "{\"cargoId\":\"API-C3\",\"palletId\":\"API-P3\"}";
        JsonNode created = objectMapper.readTree(
                mockMvc.perform(post("/api/transport-tasks").contentType(MediaType.APPLICATION_JSON).content(createBody))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.status").value("PENDING"))
                        .andExpect(jsonPath("$.data.placement.slotCode").value("API-A-01"))
                        .andReturn().getResponse().getContentAsString());
        String taskId = created.get("data").get("taskId").asText();

        // detail
        mockMvc.perform(get("/api/transport-tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.cargo.volume").isNumber());

        // list filter by palletId
        mockMvc.perform(get("/api/transport-tasks").param("palletId", "API-P3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].palletId").value("API-P3"))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        // assign
        mockMvc.perform(patch("/api/transport-tasks/{taskId}/assign", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vehicleId\":\"API-F01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.vehicleId").value("API-F01"));

        // status change
        mockMvc.perform(patch("/api/transport-tasks/{taskId}/status", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"MOVING_TO_PICKUP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MOVING_TO_PICKUP"));
    }

    // --- helpers ---

    private void registerCargo(String cargoId) throws Exception {
        mockMvc.perform(post("/api/cargos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cargoId\":\"" + cargoId + "\",\"width\":0.8,\"length\":1.0,\"height\":0.6}"))
                .andExpect(status().isCreated());
    }

    private void registerPallet(String palletId, String cargoId) throws Exception {
        mockMvc.perform(post("/api/pallets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"palletId\":\"" + palletId + "\",\"cargoId\":\"" + cargoId
                                + "\",\"pickup\":{\"x\":2.5,\"y\":1.8,\"heading\":90.0}}"))
                .andExpect(status().isCreated());
    }

    private void insertSlot(String slotCode, double w, double l, double h) {
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
        rl.setLevelNumber(1);
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
