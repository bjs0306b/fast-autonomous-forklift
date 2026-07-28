package com.fast.backend.monitoring.service;

import com.fast.backend.monitoring.dto.DashboardResponse;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제 프론트 초기 조회용 dashboard 확장 검증(prompt56.md 7·8·9·15장).
 *
 * <p>확인 범위: 차량 name/source, 위치 messageAt/receivedAt/source, 차량별 currentTask 판정(종료 상태 제외·
 * 최신 updatedAt 선택·차량 간 혼합 없음), null 처리, 기존 계약 유지(vehicleId 정렬·활성 차량만·tasks 배열).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DashboardVehicleDetailIntegrationTest {

    @Autowired private MonitoringService monitoringService;
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

    // --- 7장: 차량 name / source ---

    @Test
    void vehicleView_carriesNameAndSourceFromVehicleTable() {
        seedVehicle("DVD1-R1", "Forklift-01", VehicleSource.REAL, true, VehicleStatus.MOVING);
        seedVehicle("DVD1-S1", "Sim-Forklift-01", VehicleSource.SIMULATION, true, VehicleStatus.IDLE);

        DashboardResponse dashboard = monitoringService.getDashboard();

        DashboardResponse.VehicleView real = vehicle(dashboard, "DVD1-R1");
        assertThat(real.name()).isEqualTo("Forklift-01");
        assertThat(real.source()).isEqualTo(VehicleSource.REAL);

        // 차량 등록 source는 SIMULATION 원본 값 그대로 — 위치 태그 "SIM"으로 바꾸지 않는다.
        DashboardResponse.VehicleView sim = vehicle(dashboard, "DVD1-S1");
        assertThat(sim.name()).isEqualTo("Sim-Forklift-01");
        assertThat(sim.source()).isEqualTo(VehicleSource.SIMULATION);
    }

    /**
     * dashboard는 활성 차량만 반환하므로 {@code active}는 항상 true다. 그래도 응답에 담아 "이 목록은 활성
     * 차량만"이라는 계약을 프론트가 응답만 보고 확인할 수 있게 한다(prompt57.md 7장).
     */
    @Test
    void vehicleView_carriesActiveFlagAndItIsAlwaysTrueForListedVehicles() {
        seedVehicle("DVD1B-R1", "R1", VehicleSource.REAL, true, VehicleStatus.MOVING);
        seedVehicle("DVD1B-R2", "R2", VehicleSource.REAL, false, VehicleStatus.IDLE); // 비활성 → 목록에서 제외

        DashboardResponse dashboard = monitoringService.getDashboard();

        assertThat(vehicle(dashboard, "DVD1B-R1").active()).isTrue();
        assertThat(dashboard.vehicles()).extracting(DashboardResponse.VehicleView::vehicleId)
                .doesNotContain("DVD1B-R2");
        assertThat(dashboard.vehicles()).allSatisfy(v -> assertThat(v.active()).isTrue());
    }

    @Test
    void vehicleView_matchesNameAndSourceToTheCorrectVehicle() {
        seedVehicle("DVD2-A", "이름-A", VehicleSource.REAL, true, VehicleStatus.MOVING);
        seedVehicle("DVD2-B", "이름-B", VehicleSource.SIMULATION, true, VehicleStatus.IDLE);

        DashboardResponse dashboard = monitoringService.getDashboard();

        assertThat(vehicle(dashboard, "DVD2-A").name()).isEqualTo("이름-A");
        assertThat(vehicle(dashboard, "DVD2-B").name()).isEqualTo("이름-B");
        assertThat(vehicle(dashboard, "DVD2-A").source()).isEqualTo(VehicleSource.REAL);
        assertThat(vehicle(dashboard, "DVD2-B").source()).isEqualTo(VehicleSource.SIMULATION);
    }

    // --- 8장: 위치 메타데이터 ---

    @Test
    void locationView_carriesSnapshotMessageAtReceivedAtAndSource() {
        seedVehicle("DVD3-R1", "R1", VehicleSource.REAL, true, VehicleStatus.MOVING);
        locationProvider.update(new VehicleLocationSnapshot(
                "DVD3-R1", "REAL", 12.34, 5.67, 90.0, 0.8, "map", MSG_AT, RECV_AT));

        DashboardResponse.LocationView location = vehicle(monitoringService.getDashboard(), "DVD3-R1").location();

        // 기존 필드 유지
        assertThat(location.x()).isEqualTo(12.34);
        assertThat(location.y()).isEqualTo(5.67);
        assertThat(location.heading()).isEqualTo(90.0);
        assertThat(location.speed()).isEqualTo(0.8);
        assertThat(location.frameId()).isEqualTo("map");
        // 추가 필드 — 스냅샷 원본 값 그대로(임의 시각 생성 없음)
        assertThat(location.messageAt()).isEqualTo(MSG_AT);
        assertThat(location.receivedAt()).isEqualTo(RECV_AT);
        assertThat(location.source()).isEqualTo("REAL");
    }

    @Test
    void locationView_simSnapshotKeepsSimSourceTag() {
        seedVehicle("DVD4-S1", "S1", VehicleSource.SIMULATION, true, VehicleStatus.ACTIVE);
        locationProvider.update(new VehicleLocationSnapshot(
                "DVD4-S1", "SIM", 10.0, 20.0, 270.0, 0.1, "map", MSG_AT, RECV_AT));

        DashboardResponse.VehicleView view = vehicle(monitoringService.getDashboard(), "DVD4-S1");

        // 위치 태그는 "SIM", 차량 등록 source는 SIMULATION — 서로 다른 값 집합을 유지한다.
        assertThat(view.location().source()).isEqualTo("SIM");
        assertThat(view.source()).isEqualTo(VehicleSource.SIMULATION);
    }

    @Test
    void locationView_isNullWhenNoSnapshotReceived() {
        seedVehicle("DVD5-N1", "N1", VehicleSource.REAL, true, VehicleStatus.IDLE);

        DashboardResponse.VehicleView view = vehicle(monitoringService.getDashboard(), "DVD5-N1");

        assertThat(view.location()).isNull(); // 위치가 없으면 location 전체가 null
        assertThat(view.vehicleId()).isEqualTo("DVD5-N1"); // 그래도 목록에서 사라지지 않는다
    }

    @Test
    void locationView_doesNotFabricateTimestampsWhenSnapshotHasNone() {
        seedVehicle("DVD6-R1", "R1", VehicleSource.REAL, true, VehicleStatus.MOVING);
        // messageAt/receivedAt이 없는 스냅샷 — 백엔드가 현재 시각으로 채워 넣으면 안 된다.
        locationProvider.update(new VehicleLocationSnapshot(
                "DVD6-R1", "REAL", 1.0, 2.0, 0.0, 0.0, "map", null, null));

        DashboardResponse.LocationView location = vehicle(monitoringService.getDashboard(), "DVD6-R1").location();

        assertThat(location).isNotNull();
        assertThat(location.messageAt()).isNull();
        assertThat(location.receivedAt()).isNull();
    }

    // --- 9장: currentTask ---

    @Test
    void currentTask_selectsInProgressTaskWithLatestCommandStatus() {
        seedVehicle("DVD7-R1", "R1", VehicleSource.REAL, true, VehicleStatus.MOVING);
        seedCargoAndPallet("DVD7");
        TransportTask task = seedTask("DVD7-T1", "DVD7", "DVD7-R1", TaskStatus.TRANSPORTING, NOW);
        seedCommand("DVD7-C1", task, "DVD7-R1", TransportCommandStatus.PUBLISHED, NOW);

        DashboardResponse.CurrentTaskView current =
                vehicle(monitoringService.getDashboard(), "DVD7-R1").currentTask();

        assertThat(current).isNotNull();
        assertThat(current.taskId()).isEqualTo("DVD7-T1");
        assertThat(current.status()).isEqualTo("TRANSPORTING");
        assertThat(current.commandStatus()).isEqualTo("PUBLISHED");
        assertThat(current.updatedAt()).isNotNull();
    }

    @Test
    void currentTask_excludesCompletedFailedAndCancelledTasks() {
        seedVehicle("DVD8-C", "C", VehicleSource.REAL, true, VehicleStatus.IDLE);
        seedVehicle("DVD8-F", "F", VehicleSource.REAL, true, VehicleStatus.IDLE);
        seedVehicle("DVD8-X", "X", VehicleSource.REAL, true, VehicleStatus.IDLE);
        seedCargoAndPallet("DVD8");
        seedTask("DVD8-T-COMPLETED", "DVD8", "DVD8-C", TaskStatus.COMPLETED, NOW);
        seedTask("DVD8-T-FAILED", "DVD8", "DVD8-F", TaskStatus.FAILED, NOW);
        seedTask("DVD8-T-CANCELLED", "DVD8", "DVD8-X", TaskStatus.CANCELLED, NOW);

        DashboardResponse dashboard = monitoringService.getDashboard();

        assertThat(vehicle(dashboard, "DVD8-C").currentTask()).isNull();
        assertThat(vehicle(dashboard, "DVD8-F").currentTask()).isNull();
        assertThat(vehicle(dashboard, "DVD8-X").currentTask()).isNull();
    }

    @Test
    void currentTask_picksMostRecentlyUpdatedWhenSeveralAreInProgress() {
        seedVehicle("DVD9-R1", "R1", VehicleSource.REAL, true, VehicleStatus.MOVING);
        seedCargoAndPallet("DVD9");
        seedTask("DVD9-OLD", "DVD9", "DVD9-R1", TaskStatus.ASSIGNED, NOW.minusMinutes(10));
        seedTask("DVD9-NEW", "DVD9", "DVD9-R1", TaskStatus.PICKING_UP, NOW);

        DashboardResponse.CurrentTaskView current =
                vehicle(monitoringService.getDashboard(), "DVD9-R1").currentTask();

        assertThat(current.taskId()).isEqualTo("DVD9-NEW");
        assertThat(current.status()).isEqualTo("PICKING_UP");
    }

    @Test
    void currentTask_isNullWhenVehicleHasNoTask() {
        seedVehicle("DVD10-R1", "R1", VehicleSource.REAL, true, VehicleStatus.IDLE);

        assertThat(vehicle(monitoringService.getDashboard(), "DVD10-R1").currentTask()).isNull();
    }

    @Test
    void currentTask_isNotMixedBetweenVehicles() {
        seedVehicle("DVD11-A", "A", VehicleSource.REAL, true, VehicleStatus.MOVING);
        seedVehicle("DVD11-B", "B", VehicleSource.REAL, true, VehicleStatus.MOVING);
        seedCargoAndPallet("DVD11");
        TransportTask taskA = seedTask("DVD11-TA", "DVD11", "DVD11-A", TaskStatus.TRANSPORTING, NOW);
        TransportTask taskB = seedTask("DVD11-TB", "DVD11", "DVD11-B", TaskStatus.PLACING, NOW);
        seedCommand("DVD11-CA", taskA, "DVD11-A", TransportCommandStatus.PUBLISHED, NOW);
        seedCommand("DVD11-CB", taskB, "DVD11-B", TransportCommandStatus.SUCCEEDED, NOW);

        DashboardResponse dashboard = monitoringService.getDashboard();

        assertThat(vehicle(dashboard, "DVD11-A").currentTask().taskId()).isEqualTo("DVD11-TA");
        assertThat(vehicle(dashboard, "DVD11-A").currentTask().commandStatus()).isEqualTo("PUBLISHED");
        assertThat(vehicle(dashboard, "DVD11-B").currentTask().taskId()).isEqualTo("DVD11-TB");
        assertThat(vehicle(dashboard, "DVD11-B").currentTask().commandStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void currentTask_commandStatusIsNullWhenNoCommandPublishedYet() {
        seedVehicle("DVD12-R1", "R1", VehicleSource.REAL, true, VehicleStatus.MOVING);
        seedCargoAndPallet("DVD12");
        seedTask("DVD12-T1", "DVD12", "DVD12-R1", TaskStatus.ASSIGNED, NOW);

        DashboardResponse.CurrentTaskView current =
                vehicle(monitoringService.getDashboard(), "DVD12-R1").currentTask();

        assertThat(current.taskId()).isEqualTo("DVD12-T1");
        assertThat(current.commandStatus()).isNull();
    }

    /**
     * currentTask는 대시보드 {@code tasks} 목록(최근 100건)과 독립적으로 계산돼야 한다 — 진행 중 작업이
     * 100건 창 밖으로 밀려나도 놓치면 안 되기 때문이다(별도 활성 작업 조회를 쓰는 이유).
     */
    @Test
    void currentTask_isFoundEvenWhenTaskIsOutsideTheRecentTaskWindow() {
        seedVehicle("DVD13-R1", "R1", VehicleSource.REAL, true, VehicleStatus.MOVING);
        seedCargoAndPallet("DVD13");
        // 오래된 진행 중 작업 1건 + 그보다 나중에 생성된 종료 작업 120건
        seedTask("DVD13-ACTIVE", "DVD13", "DVD13-R1", TaskStatus.TRANSPORTING, NOW.minusDays(2), NOW.minusDays(2));
        for (int i = 0; i < 120; i++) {
            seedTask("DVD13-DONE-" + i, "DVD13", null, TaskStatus.COMPLETED, NOW, NOW);
        }

        DashboardResponse dashboard = monitoringService.getDashboard();

        assertThat(dashboard.tasks()).hasSize(100); // 기존 목록 상한 유지
        assertThat(dashboard.tasks()).extracting(DashboardResponse.TaskView::taskId)
                .doesNotContain("DVD13-ACTIVE"); // 창 밖으로 밀려남
        assertThat(vehicle(dashboard, "DVD13-R1").currentTask().taskId()).isEqualTo("DVD13-ACTIVE");
    }

    // --- 15장: 기존 계약 유지 ---

    @Test
    void dashboard_keepsVehicleIdOrderAndActiveOnlyFilter() {
        seedVehicle("DVD14-C", "C", VehicleSource.REAL, true, VehicleStatus.IDLE);
        seedVehicle("DVD14-A", "A", VehicleSource.REAL, true, VehicleStatus.IDLE);
        seedVehicle("DVD14-B", "B", VehicleSource.REAL, true, VehicleStatus.IDLE);
        seedVehicle("DVD14-Z-INACTIVE", "Z", VehicleSource.REAL, false, VehicleStatus.IDLE);

        DashboardResponse dashboard = monitoringService.getDashboard();

        List<String> ids = dashboard.vehicles().stream()
                .map(DashboardResponse.VehicleView::vehicleId)
                .filter(id -> id.startsWith("DVD14-"))
                .toList();
        assertThat(ids).containsExactly("DVD14-A", "DVD14-B", "DVD14-C"); // 오름차순, 비활성 제외
        assertThat(dashboard.vehicles()).extracting(DashboardResponse.VehicleView::vehicleId)
                .doesNotContain("DVD14-Z-INACTIVE");
        // 전체 목록도 vehicleId 오름차순을 유지한다
        assertThat(dashboard.vehicles()).extracting(DashboardResponse.VehicleView::vehicleId)
                .isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void dashboard_statusIsUnknownWhenNeverReported() {
        seedVehicleWithoutStatus("DVD15-R1", "R1", VehicleSource.REAL, true);

        DashboardResponse.VehicleView view = vehicle(monitoringService.getDashboard(), "DVD15-R1");

        assertThat(view.status()).isEqualTo(VehicleStatus.UNKNOWN);
        assertThat(view.location()).isNull();
        assertThat(view.currentTask()).isNull();
        assertThat(view.name()).isEqualTo("R1"); // 상태가 없어도 기본 정보는 나온다
    }

    @Test
    void dashboard_keepsExistingTasksArrayContract() {
        seedVehicle("DVD16-R1", "R1", VehicleSource.REAL, true, VehicleStatus.MOVING);
        seedCargoAndPallet("DVD16");
        TransportTask task = seedTask("DVD16-T1", "DVD16", "DVD16-R1", TaskStatus.TRANSPORTING, NOW);
        seedCommand("DVD16-C1", task, "DVD16-R1", TransportCommandStatus.PUBLISHED, NOW);

        DashboardResponse.TaskView taskView = monitoringService.getDashboard().tasks().stream()
                .filter(t -> t.taskId().equals("DVD16-T1")).findFirst().orElseThrow();

        assertThat(taskView.vehicleId()).isEqualTo("DVD16-R1");
        assertThat(taskView.status()).isEqualTo("TRANSPORTING");
        assertThat(taskView.commandStatus()).isEqualTo("PUBLISHED");
        assertThat(taskView.updatedAt()).isNotNull();
    }

    // --- helpers ---

    private DashboardResponse.VehicleView vehicle(DashboardResponse d, String id) {
        return d.vehicles().stream().filter(v -> v.vehicleId().equals(id)).findFirst().orElseThrow();
    }

    private void seedVehicle(
            String vehicleId, String name, VehicleSource source, boolean active, VehicleStatus status) {
        seedVehicleWithoutStatus(vehicleId, name, source, active);
        VehicleCurrentStatus cur = new VehicleCurrentStatus();
        cur.setVehicleId(vehicleId);
        cur.setStatus(status);
        cur.setMessageAt(NOW);
        cur.setReceivedAt(NOW);
        cur.setUpdatedAt(NOW);
        vehicleCurrentStatusMapper.upsert(cur);
    }

    private void seedVehicleWithoutStatus(String vehicleId, String name, VehicleSource source, boolean active) {
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(name);
        v.setSource(source);
        v.setActive(active);
        v.setCreatedAt(NOW);
        v.setUpdatedAt(NOW);
        vehicleMapper.insert(v);
    }

    /** transport_task는 cargo_id/pallet_id에 FK가 걸려 있어 참조 대상만 최소로 만든다. */
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

    private TransportTask seedTask(
            String taskCode, String suffix, String vehicleId, TaskStatus status, LocalDateTime updatedAt) {
        return seedTask(taskCode, suffix, vehicleId, status, updatedAt, NOW);
    }

    private TransportTask seedTask(
            String taskCode, String suffix, String vehicleId, TaskStatus status,
            LocalDateTime updatedAt, LocalDateTime createdAt) {
        TransportTask task = new TransportTask();
        task.setTaskCode(taskCode);
        task.setCargoId("C-" + suffix);
        task.setPalletId("P-" + suffix);
        task.setVehicleId(vehicleId);
        task.setStatus(status);
        task.setCreatedAt(createdAt);
        task.setUpdatedAt(updatedAt);
        transportTaskMapper.insert(task);
        return task;
    }

    private void seedCommand(
            String commandId, TransportTask task, String vehicleId,
            TransportCommandStatus status, LocalDateTime createdAt) {
        TransportCommand cmd = new TransportCommand();
        cmd.setCommandId(commandId);
        cmd.setTaskId(task.getId());
        cmd.setTaskCode(task.getTaskCode());
        cmd.setVehicleId(vehicleId);
        cmd.setCommandType("TRANSPORT");
        cmd.setStatus(status);
        cmd.setCreatedAt(createdAt);
        cmd.setUpdatedAt(createdAt);
        transportCommandMapper.insert(cmd);
    }
}
