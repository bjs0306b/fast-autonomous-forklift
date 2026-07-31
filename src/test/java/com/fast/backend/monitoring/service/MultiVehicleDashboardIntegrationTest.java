package com.fast.backend.monitoring.service;

import com.fast.backend.monitoring.dto.DashboardResponse;
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
import com.fast.backend.transport.domain.TransportCommand;
import com.fast.backend.transport.domain.TransportCommandStatus;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
import com.fast.backend.transport.mapper.TransportCommandMapper;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.transport.service.TransportTaskService;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다중 차량 대시보드 결합 분리 검증(prompt51.md 11장). vehicleId별 상태+위치 결합, 위치 없는 차량 유지,
 * REAL/SIM source 분리, Task↔최신 command 정확 연결을 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MultiVehicleDashboardIntegrationTest {

    @Autowired private MonitoringService monitoringService;
    @Autowired private InMemoryLatestVehicleLocationProvider locationProvider;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;
    @Autowired private CargoMapper cargoMapper;
    @Autowired private PalletMapper palletMapper;
    @Autowired private RackMapper rackMapper;
    @Autowired private RackLevelMapper rackLevelMapper;
    @Autowired private StorageSlotMapper storageSlotMapper;
    @Autowired private TransportTaskService taskService;
    @Autowired private TransportTaskMapper transportTaskMapper;
    @Autowired private TransportCommandMapper transportCommandMapper;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);
    private static final OffsetDateTime MSG_AT = OffsetDateTime.of(2026, 7, 27, 15, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void dashboard_separatesVehiclesAndLinksLatestCommandCorrectly() {
        // MDB-R1: 상태+위치+Task(command PUBLISHED)
        registerVehicle("MDB-R1", VehicleStatus.MOVING);
        locationProvider.update(new VehicleLocationSnapshot(
                "MDB-R1", "REAL", 1.0, 2.0, 90.0, 0.5, "map", MSG_AT, MSG_AT));
        String taskR1 = seedTask("MDB1", "MDB-R1", TransportCommandStatus.PUBLISHED);

        // MDB-R2: 상태만, 위치 없음
        registerVehicle("MDB-R2", VehicleStatus.IDLE);

        // MDB-S1: 상태+위치(SIM), Task(command SUCCEEDED)
        registerVehicle("MDB-S1", VehicleStatus.ACTIVE);
        locationProvider.update(new VehicleLocationSnapshot(
                "MDB-S1", "SIM", 10.0, 20.0, 270.0, 0.1, "map", MSG_AT, MSG_AT));
        String taskS1 = seedTask("MDB2", "MDB-S1", TransportCommandStatus.SUCCEEDED);

        // MDB-S2: 등록만(활성), 상태/위치/Task 없음
        registerVehicleNoStatus("MDB-S2");

        DashboardResponse dashboard = monitoringService.getDashboard();

        // 1) 각 차량이 정확히 한 번씩(중복 없음)
        assertThat(dashboard.vehicles()).extracting(DashboardResponse.VehicleView::vehicleId)
                .contains("MDB-R1", "MDB-R2", "MDB-S1", "MDB-S2")
                .doesNotHaveDuplicates();

        // 2) R1 상태+위치 결합
        DashboardResponse.VehicleView r1 = vehicle(dashboard, "MDB-R1");
        assertThat(r1.status()).isEqualTo(VehicleStatus.MOVING);
        assertThat(r1.location()).isNotNull();
        assertThat(r1.location().x()).isEqualTo(1.0);

        // 3) R2 위치 null이어도 목록 유지
        DashboardResponse.VehicleView r2 = vehicle(dashboard, "MDB-R2");
        assertThat(r2.status()).isEqualTo(VehicleStatus.IDLE);
        assertThat(r2.location()).isNull();

        // 4) S1 SIM 위치가 REAL과 안 섞임
        DashboardResponse.VehicleView s1 = vehicle(dashboard, "MDB-S1");
        assertThat(s1.location().x()).isEqualTo(10.0);
        assertThat(s1.location().heading()).isEqualTo(270.0);

        // 5) S2 등록만 → 활성 조회에 포함, 위치 null
        DashboardResponse.VehicleView s2 = vehicle(dashboard, "MDB-S2");
        assertThat(s2.location()).isNull();

        // 6/7) Task↔최신 command 정확 연결(교차 없음)
        DashboardResponse.TaskView tvR1 = task(dashboard, taskR1);
        DashboardResponse.TaskView tvS1 = task(dashboard, taskS1);
        assertThat(tvR1.vehicleId()).isEqualTo("MDB-R1");
        assertThat(tvR1.commandStatus()).isEqualTo("PUBLISHED");
        assertThat(tvS1.vehicleId()).isEqualTo("MDB-S1");
        assertThat(tvS1.commandStatus()).isEqualTo("SUCCEEDED");
    }

    // --- helpers ---

    private DashboardResponse.VehicleView vehicle(DashboardResponse d, String id) {
        return d.vehicles().stream().filter(v -> v.vehicleId().equals(id)).findFirst().orElseThrow();
    }

    private DashboardResponse.TaskView task(DashboardResponse d, String taskCode) {
        return d.tasks().stream().filter(t -> t.taskId().equals(taskCode)).findFirst().orElseThrow();
    }

    private void registerVehicle(String vehicleId, VehicleStatus status) {
        registerVehicleNoStatus(vehicleId);
        VehicleCurrentStatus cur = new VehicleCurrentStatus();
        cur.setVehicleId(vehicleId);
        cur.setStatus(status);
        cur.setMessageAt(NOW);
        cur.setReceivedAt(NOW);
        cur.setUpdatedAt(NOW);
        vehicleCurrentStatusMapper.upsert(cur);
    }

    private void registerVehicleNoStatus(String vehicleId) {
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(vehicleId);
        v.setSource(vehicleId.startsWith("MDB-S") ? VehicleSource.SIMULATION : VehicleSource.REAL);
        v.setActive(true);
        v.setCreatedAt(NOW);
        v.setUpdatedAt(NOW);
        vehicleMapper.insert(v);
    }

    private String seedTask(String suffix, String vehicleId, TransportCommandStatus commandStatus) {
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
        // 조건부 배정 Mapper로 task.vehicleId를 설정한다(차량 상태와 무관하게 배정 상태 구성).
        transportTaskMapper.updateAssignment(taskCode, vehicleId, NOW, NOW);
        Long taskId = transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getId();
        TransportCommand cmd = new TransportCommand();
        cmd.setCommandId("TCMD-" + suffix);
        cmd.setTaskId(taskId);
        cmd.setTaskCode(taskCode);
        cmd.setVehicleId(vehicleId);
        cmd.setCommandType("TRANSPORT");
        cmd.setStatus(commandStatus);
        cmd.setCreatedAt(NOW);
        cmd.setUpdatedAt(NOW);
        transportCommandMapper.insert(cmd);
        return taskCode;
    }
}
