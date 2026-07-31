package com.fast.backend.monitoring.controller;

import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.location.InMemoryLatestVehicleLocationProvider;
import com.fast.backend.vehicle.location.VehicleLocationSnapshot;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-504 모니터링 REST API 종단 테스트(prompt50.md 8·9·10장). ApiResponse 형식·404·위치 null 처리 확인.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MonitoringControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private InMemoryLatestVehicleLocationProvider locationProvider;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);
    private static final OffsetDateTime MSG_AT =
            OffsetDateTime.of(2026, 7, 27, 15, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void currentStatus_endpoints() throws Exception {
        seedVehicle("MC1-F01", VehicleStatus.MOVING);

        mockMvc.perform(get("/api/vehicles/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/vehicles/{id}/status", "MC1-F01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicleId").value("MC1-F01"))
                .andExpect(jsonPath("$.data.status").value("MOVING"));

        mockMvc.perform(get("/api/vehicles/{id}/status", "MC1-UNKNOWN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void latestLocation_presentAndNull() throws Exception {
        seedVehicle("MC2-F01", VehicleStatus.MOVING);
        locationProvider.update(new VehicleLocationSnapshot(
                "MC2-F01", "REAL", 2.4, 5.1, 90.0, 0.5, "map", MSG_AT, MSG_AT));

        mockMvc.perform(get("/api/vehicles/locations/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/vehicles/{id}/location/latest", "MC2-F01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("REAL"))
                .andExpect(jsonPath("$.data.x").value(2.4));

        // 위치 미수신 차량 → data=null (default-property-inclusion: always 라 키는 존재하고 값이 null)
        mockMvc.perform(get("/api/vehicles/{id}/location/latest", "MC2-NOLOC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void dashboard_endpoint() throws Exception {
        seedVehicle("MC3-F01", VehicleStatus.IDLE);

        mockMvc.perform(get("/api/monitoring/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vehicles").isArray())
                .andExpect(jsonPath("$.data.tasks").isArray());
    }

    private void seedVehicle(String vehicleId, VehicleStatus status) {
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(vehicleId);
        v.setSource(VehicleSource.REAL);
        v.setActive(true);
        v.setCreatedAt(NOW);
        v.setUpdatedAt(NOW);
        vehicleMapper.insert(v);

        VehicleCurrentStatus cur = new VehicleCurrentStatus();
        cur.setVehicleId(vehicleId);
        cur.setStatus(status);
        cur.setMessageAt(NOW);
        cur.setReceivedAt(NOW);
        cur.setUpdatedAt(NOW);
        vehicleCurrentStatusMapper.upsert(cur);
    }
}
