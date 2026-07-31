package com.fast.backend.command.controller;

import com.fast.backend.command.service.VehicleCommandPublisher;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
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
 * 안전 명령 REST API 종단 테스트(prompt53.md 4·5장). Publisher는 Mock으로 대체한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SafetyCommandControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    @MockBean private VehicleCommandPublisher publisher;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void stopApi_success() throws Exception {
        doNothing().when(publisher).publish(any());
        register("SCC-F01");

        mockMvc.perform(post("/api/vehicles/{id}/commands/stop", "SCC-F01")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"정지\",\"requestedBy\":\"admin\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.command").value("STOP"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    void emergencyStopApi_success_withoutBody() throws Exception {
        doNothing().when(publisher).publish(any());
        register("SCC-F02");

        mockMvc.perform(post("/api/vehicles/{id}/commands/emergency-stop", "SCC-F02"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.command").value("EMERGENCY_STOP"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    void emergencyStopAllApi_returnsSummary() throws Exception {
        doNothing().when(publisher).publish(any());
        register("SCC-A1");
        register("SCC-A2");

        mockMvc.perform(post("/api/vehicles/commands/emergency-stop-all"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.requestedCount").value(2))
                .andExpect(jsonPath("$.data.publishedCount").value(2))
                .andExpect(jsonPath("$.data.results").isArray());
    }

    @Test
    void stopApi_unknownVehicle_notFound() throws Exception {
        mockMvc.perform(post("/api/vehicles/{id}/commands/stop", "SCC-NONE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void stopApi_requestedByTooLong_validationFails() throws Exception {
        register("SCC-F03");
        String longName = "x".repeat(101);
        mockMvc.perform(post("/api/vehicles/{id}/commands/stop", "SCC-F03")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"r\",\"requestedBy\":\"" + longName + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    private void register(String vehicleId) {
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(vehicleId);
        v.setActive(true);
        v.setCreatedAt(NOW);
        vehicleMapper.insert(v);
        VehicleCurrentStatus cur = new VehicleCurrentStatus();
        cur.setVehicleId(vehicleId);
        cur.setStatus(VehicleStatus.MOVING);
        cur.setReceivedAt(NOW);
        vehicleCurrentStatusMapper.upsert(cur);
    }
}
