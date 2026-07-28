package com.fast.backend.monitoring.controller;

import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.domain.Pallet;
import com.fast.backend.storage.domain.PalletStatus;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.storage.mapper.PalletMapper;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportCommand;
import com.fast.backend.transport.domain.TransportCommandStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportCommandMapper;
import com.fast.backend.transport.mapper.TransportTaskMapper;
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

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * dashboard JSON 계약 종단 검증(prompt56.md 16장 1~17번). 확장 필드가 JSON에 실제로 나가는지, 기존 필드명이
 * 그대로인지, battery가 섞여 들어오지 않는지를 응답 본문 기준으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DashboardContractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private InMemoryLatestVehicleLocationProvider locationProvider;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;
    @Autowired private CargoMapper cargoMapper;
    @Autowired private PalletMapper palletMapper;
    @Autowired private TransportTaskMapper transportTaskMapper;
    @Autowired private TransportCommandMapper transportCommandMapper;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);
    private static final OffsetDateTime MSG_AT =
            OffsetDateTime.of(2026, 7, 28, 11, 20, 27, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime RECV_AT =
            OffsetDateTime.of(2026, 7, 28, 11, 20, 28, 0, ZoneOffset.ofHours(9));

    /**
     * 각 테스트는 {@code @Transactional} 롤백으로 격리되고 test 프로필은 초기 데이터를 넣지 않으므로
     * (application-test.yml에 data-locations 없음), 테스트가 심은 차량 1대만 응답에 담긴다.
     * 그래서 중첩 객체까지 안전하게 훑을 수 있는 확정 경로 {@code vehicles[0]}를 쓴다 —
     * 필터식({@code [?(...)]})은 결과가 배열이 되어 중첩 객체 경로를 이어 붙일 수 없다.
     */
    private static final String V0 = "$.data.vehicles[0]";

    @Test
    void dashboard_returnsExtendedVehicleContract() throws Exception {
        seedVehicle("DC1-R1", "Forklift-01", VehicleSource.REAL, VehicleStatus.MOVING);
        locationProvider.update(new VehicleLocationSnapshot(
                "DC1-R1", "REAL", 12.34, 5.67, 90.0, 0.8, "map", MSG_AT, RECV_AT));
        seedCargoAndPallet("DC1");
        TransportTask task = seedTask("DC1-T1", "DC1", "DC1-R1", TaskStatus.TRANSPORTING);
        seedCommand("DC1-C1", task, "DC1-R1", TransportCommandStatus.PUBLISHED);

        mockMvc.perform(get("/api/monitoring/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 기존 계약 유지
                .andExpect(jsonPath("$.data.vehicles").isArray())
                .andExpect(jsonPath("$.data.tasks").isArray())
                .andExpect(jsonPath(V0 + ".vehicleId").value("DC1-R1"))
                .andExpect(jsonPath(V0 + ".status").value("MOVING"))
                .andExpect(jsonPath(V0 + ".lastUpdatedAt").exists())
                .andExpect(jsonPath(V0 + ".location.x").value(12.34))
                .andExpect(jsonPath(V0 + ".location.y").value(5.67))
                .andExpect(jsonPath(V0 + ".location.heading").value(90.0))
                .andExpect(jsonPath(V0 + ".location.speed").value(0.8))
                .andExpect(jsonPath(V0 + ".location.frameId").value("map"))
                // 신규 차량 메타데이터
                .andExpect(jsonPath(V0 + ".name").value("Forklift-01"))
                .andExpect(jsonPath(V0 + ".source").value("REAL"))
                .andExpect(jsonPath(V0 + ".active").value(true))
                // 신규 위치 메타데이터 — 스냅샷 원본 시각이 +09:00으로 나간다
                .andExpect(jsonPath(V0 + ".location.messageAt").value("2026-07-28T11:20:27+09:00"))
                .andExpect(jsonPath(V0 + ".location.receivedAt").value("2026-07-28T11:20:28+09:00"))
                .andExpect(jsonPath(V0 + ".location.source").value("REAL"))
                // 신규 currentTask
                .andExpect(jsonPath(V0 + ".currentTask.taskId").value("DC1-T1"))
                .andExpect(jsonPath(V0 + ".currentTask.status").value("TRANSPORTING"))
                .andExpect(jsonPath(V0 + ".currentTask.commandStatus").value("PUBLISHED"))
                .andExpect(jsonPath(V0 + ".currentTask.updatedAt").exists())
                // 배터리는 어떤 계층에도 없어야 한다
                .andExpect(jsonPath(V0 + ".battery").doesNotExist())
                .andExpect(jsonPath(V0 + ".location.battery").doesNotExist())
                .andExpect(jsonPath(V0 + ".currentTask.battery").doesNotExist());
    }

    @Test
    void dashboard_nullsOutLocationAndCurrentTaskWhenAbsent() throws Exception {
        seedVehicle("DC2-R1", "Forklift-02", VehicleSource.SIMULATION, VehicleStatus.IDLE);

        mockMvc.perform(get("/api/monitoring/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(V0 + ".vehicleId").value("DC2-R1"))
                // default-property-inclusion: always 라 키는 존재하고 값이 null이다
                .andExpect(jsonPath(V0 + ".location").value(nullValue()))
                .andExpect(jsonPath(V0 + ".currentTask").value(nullValue()))
                .andExpect(jsonPath(V0 + ".name").value("Forklift-02"))
                .andExpect(jsonPath(V0 + ".source").value("SIMULATION"))
                .andExpect(jsonPath(V0 + ".active").value(true));
    }

    @Test
    void dashboard_keepsExistingTaskArrayFieldNames() throws Exception {
        seedVehicle("DC3-R1", "Forklift-03", VehicleSource.REAL, VehicleStatus.MOVING);
        seedCargoAndPallet("DC3");
        TransportTask task = seedTask("DC3-T1", "DC3", "DC3-R1", TaskStatus.PLACING);
        seedCommand("DC3-C1", task, "DC3-R1", TransportCommandStatus.ACKNOWLEDGED);

        String t = "$.data.tasks[0]";
        mockMvc.perform(get("/api/monitoring/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(t + ".taskId").value("DC3-T1"))
                .andExpect(jsonPath(t + ".vehicleId").value("DC3-R1"))
                .andExpect(jsonPath(t + ".status").value("PLACING"))
                .andExpect(jsonPath(t + ".commandStatus").value("ACKNOWLEDGED"))
                .andExpect(jsonPath(t + ".updatedAt").exists());
    }

    // --- helpers ---

    private void seedVehicle(String vehicleId, String name, VehicleSource source, VehicleStatus status) {
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(name);
        v.setSource(source);
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

    private void seedCargoAndPallet(String suffix) {
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
    }

    private TransportTask seedTask(String taskCode, String suffix, String vehicleId, TaskStatus status) {
        TransportTask task = new TransportTask();
        task.setTaskCode(taskCode);
        task.setCargoId("C-" + suffix);
        task.setPalletId("P-" + suffix);
        task.setVehicleId(vehicleId);
        task.setStatus(status);
        task.setCreatedAt(NOW);
        task.setUpdatedAt(NOW);
        transportTaskMapper.insert(task);
        return task;
    }

    private void seedCommand(
            String commandId, TransportTask task, String vehicleId, TransportCommandStatus status) {
        TransportCommand cmd = new TransportCommand();
        cmd.setCommandId(commandId);
        cmd.setTaskId(task.getId());
        cmd.setTaskCode(task.getTaskCode());
        cmd.setVehicleId(vehicleId);
        cmd.setCommandType("TRANSPORT");
        cmd.setStatus(status);
        cmd.setCreatedAt(NOW);
        cmd.setUpdatedAt(NOW);
        transportCommandMapper.insert(cmd);
    }
}
