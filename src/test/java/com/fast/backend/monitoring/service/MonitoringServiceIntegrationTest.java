package com.fast.backend.monitoring.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-504 대시보드 조합 통합 테스트(prompt50.md 16장 35~38번 + 상태/위치 조회). 위치 없는 차량도 응답에
 * 남고, 최신 command 상태가 포함되는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MonitoringServiceIntegrationTest {

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
    private static final OffsetDateTime MSG_AT =
            OffsetDateTime.of(2026, 7, 27, 15, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void dashboard_combinesStatusLocationAndLatestCommand() {
        seedVehicle("MS1-F01", VehicleStatus.MOVING);
        seedVehicle("MS1-F02", VehicleStatus.IDLE); // 위치 없음
        locationProvider.update(new VehicleLocationSnapshot(
                "MS1-F01", "REAL", 2.4, 5.1, 90.0, 0.5, "map", MSG_AT, MSG_AT));
        String taskCode = seedDispatchedTask("MS1");

        DashboardResponse dashboard = monitoringService.getDashboard();

        DashboardResponse.VehicleView f01 = findVehicle(dashboard, "MS1-F01");
        DashboardResponse.VehicleView f02 = findVehicle(dashboard, "MS1-F02");
        assertThat(f01.location()).isNotNull();
        assertThat(f01.location().x()).isEqualTo(2.4);
        assertThat(f02).isNotNull();
        assertThat(f02.location()).isNull(); // 위치 없는 차량도 목록에 남음

        DashboardResponse.TaskView task = dashboard.tasks().stream()
                .filter(t -> t.taskId().equals(taskCode)).findFirst().orElseThrow();
        assertThat(task.commandStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void currentStatuses_and_single() {
        seedVehicle("MS2-F01", VehicleStatus.ACTIVE);
        assertThat(monitoringService.getCurrentStatuses())
                .anySatisfy(s -> assertThat(s.vehicleId()).isEqualTo("MS2-F01"));
        assertThat(monitoringService.getCurrentStatus("MS2-F01").status()).isEqualTo(VehicleStatus.ACTIVE);
    }

    @Test
    void currentStatus_unknownVehicle_throws() {
        assertThatThrownBy(() -> monitoringService.getCurrentStatus("MS-NONE"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
    }

    @Test
    void latestLocation_presentAndAbsent() {
        seedVehicle("MS3-F01", VehicleStatus.MOVING);
        locationProvider.update(new VehicleLocationSnapshot(
                "MS3-F01", "REAL", 1.0, 2.0, 0.0, 0.1, "map", MSG_AT, MSG_AT));
        assertThat(monitoringService.getLatestLocation("MS3-F01")).isNotNull();
        assertThat(monitoringService.getLatestLocation("MS3-NONE")).isNull();
    }

    // --- helpers ---

    private DashboardResponse.VehicleView findVehicle(DashboardResponse d, String id) {
        return d.vehicles().stream().filter(v -> v.vehicleId().equals(id)).findFirst().orElseThrow();
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

    private String seedDispatchedTask(String suffix) {
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
        Long taskId = transportTaskMapper.findByTaskCode(taskCode).orElseThrow().getId();
        TransportCommand cmd = new TransportCommand();
        cmd.setCommandId("TCMD-" + suffix);
        cmd.setTaskId(taskId);
        cmd.setTaskCode(taskCode);
        cmd.setVehicleId("MS1-F01");
        cmd.setCommandType("TRANSPORT");
        cmd.setStatus(TransportCommandStatus.PUBLISHED);
        cmd.setCreatedAt(NOW);
        cmd.setUpdatedAt(NOW);
        transportCommandMapper.insert(cmd);
        return taskCode;
    }
}
