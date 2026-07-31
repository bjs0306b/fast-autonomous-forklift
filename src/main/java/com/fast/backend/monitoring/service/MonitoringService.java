package com.fast.backend.monitoring.service;

import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.monitoring.dto.DashboardResponse;
import com.fast.backend.monitoring.dto.VehicleLocationLatestResponse;
import com.fast.backend.monitoring.dto.VehicleStatusMonitorResponse;
import com.fast.backend.transport.domain.TransportCommand;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportCommandMapper;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.vehicle.dto.VehicleDetailResponse;
import com.fast.backend.vehicle.dto.VehicleResponse;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.location.LatestVehicleLocationProvider;
import com.fast.backend.vehicle.location.VehicleLocationSnapshot;
import com.fast.backend.vehicle.service.VehicleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * FR-504 대시보드 조회 조합(prompt50.md 8·9·10·11장). <b>이미 구현된 기능을 재사용</b>한다:
 * 차량 현재 상태는 {@link VehicleService}, 최신 위치는 메모리 {@link LatestVehicleLocationProvider},
 * 작업/명령은 기존 Mapper. 위치를 DB에 저장하지 않고 vehicleId로만 결합한다.
 */
@Service
public class MonitoringService {

    private static final int DASHBOARD_TASK_LIMIT = 100;

    private final VehicleService vehicleService;
    private final LatestVehicleLocationProvider locationProvider;
    private final TransportTaskMapper transportTaskMapper;
    private final TransportCommandMapper transportCommandMapper;

    public MonitoringService(
            VehicleService vehicleService, LatestVehicleLocationProvider locationProvider,
            TransportTaskMapper transportTaskMapper, TransportCommandMapper transportCommandMapper) {
        this.vehicleService = vehicleService;
        this.locationProvider = locationProvider;
        this.transportTaskMapper = transportTaskMapper;
        this.transportCommandMapper = transportCommandMapper;
    }

    @Transactional(readOnly = true)
    public List<VehicleStatusMonitorResponse> getCurrentStatuses() {
        return vehicleService.findActiveVehicles().stream()
                .map(MonitoringService::toStatusResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public VehicleStatusMonitorResponse getCurrentStatus(String vehicleId) {
        VehicleDetailResponse detail = vehicleService.getDetail(vehicleId); // 미등록이면 VEHICLE_NOT_FOUND
        VehicleStatusResponse s = detail.status();
        return new VehicleStatusMonitorResponse(
                detail.vehicleId(), s.status(), s.battery(), s.messageAt(), s.receivedAt());
    }

    public List<VehicleLocationLatestResponse> getLatestLocations() {
        return locationProvider.findAllLatest().stream()
                .sorted(Comparator.comparing(VehicleLocationSnapshot::vehicleId))
                .map(VehicleLocationLatestResponse::from)
                .collect(Collectors.toList());
    }

    /** 위치가 아직 수신되지 않은 차량은 null을 반환한다(새 예외를 만들지 않음, prompt50.md 9장). */
    public VehicleLocationLatestResponse getLatestLocation(String vehicleId) {
        return locationProvider.findLatest(vehicleId)
                .map(VehicleLocationLatestResponse::from)
                .orElse(null);
    }

    /**
     * 관제 화면 초기 조회 1회로 끝내기 위한 통합 응답(prompt56.md 6~10장).
     *
     * <p><b>고정 쿼리 수(차량·작업 건수와 무관하게 5회)</b>로 조립한다 — 차량마다 다시 조회하는 구조를
     * 만들지 않는다(§10 N+1 금지):
     * <ol>
     *   <li>활성 차량 목록 1회 + 현재 상태 일괄 1회 — {@link VehicleService#findActiveVehicles()} 내부</li>
     *   <li>최신 위치 전체 스냅샷 1회 — 메모리 Provider(DB 접근 아님)</li>
     *   <li>대시보드 task 목록 1회 — 최근 {@value #DASHBOARD_TASK_LIMIT}건</li>
     *   <li>진행 중(배정된) task 전체 1회 — 차량별 currentTask 계산용</li>
     *   <li>위 두 목록에 등장한 taskId의 command 일괄 1회 — commandStatus 계산용</li>
     * </ol>
     * 이후 결합은 전부 Java 메모리에서 vehicleId/taskId 기준으로 수행한다.
     *
     * <p><b>이전 구현과의 차이</b>: 예전에는 task 100건마다 {@code findLatestByTaskId}를 호출해 최대 100회의
     * 추가 쿼리가 나갔다. 이제 taskId를 모아 한 번에 조회한다.
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        List<VehicleResponse> activeVehicles = vehicleService.findActiveVehicles();
        Map<String, VehicleLocationSnapshot> locationByVehicleId = locationProvider.findAllLatest().stream()
                .collect(Collectors.toMap(VehicleLocationSnapshot::vehicleId, s -> s, (a, b) -> a));

        List<TransportTask> recentTasks = transportTaskMapper.findAll(null, null, null, DASHBOARD_TASK_LIMIT, 0);
        List<TransportTask> activeTasks = transportTaskMapper.findActiveTasksWithVehicle();

        // 두 목록에 등장한 taskId 전체를 한 번에 조회한다(중복 taskId는 Set으로 제거).
        Map<Long, TransportCommand> latestCommandByTaskId =
                loadLatestCommands(Stream.concat(recentTasks.stream(), activeTasks.stream())
                        .map(TransportTask::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));

        // activeTasks는 (vehicle_id, updated_at DESC, id DESC)로 정렬돼 있어 각 차량의 첫 행이 곧 현재 작업이다.
        Map<String, TransportTask> currentTaskByVehicleId = new HashMap<>();
        for (TransportTask task : activeTasks) {
            currentTaskByVehicleId.putIfAbsent(task.getVehicleId(), task);
        }

        List<DashboardResponse.VehicleView> vehicles = activeVehicles.stream()
                .map(vr -> toVehicleView(
                        vr,
                        locationByVehicleId.get(vr.vehicleId()),
                        currentTaskByVehicleId.get(vr.vehicleId()),
                        latestCommandByTaskId))
                .collect(Collectors.toList());

        List<DashboardResponse.TaskView> tasks = recentTasks.stream()
                .map(task -> toTaskView(task, latestCommandByTaskId.get(task.getId())))
                .collect(Collectors.toList());

        return new DashboardResponse(vehicles, tasks);
    }

    /**
     * taskId별 최신 command를 1회 쿼리로 적재한다. Mapper가 {@code task_id, created_at DESC, id DESC}로
     * 정렬해 주므로 각 taskId의 <b>첫 행</b>만 남기면 그것이 최신이다({@code putIfAbsent}).
     */
    private Map<Long, TransportCommand> loadLatestCommands(Collection<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of(); // IN () 문법 오류 방지
        }
        Map<Long, TransportCommand> latestByTaskId = new HashMap<>();
        for (TransportCommand command : transportCommandMapper.findByTaskIds(List.copyOf(taskIds))) {
            latestByTaskId.putIfAbsent(command.getTaskId(), command);
        }
        return latestByTaskId;
    }

    private static VehicleStatusMonitorResponse toStatusResponse(VehicleResponse vr) {
        VehicleStatusResponse s = vr.status();
        return new VehicleStatusMonitorResponse(
                vr.vehicleId(), s.status(), s.battery(), s.messageAt(), s.receivedAt());
    }

    /**
     * 차량 1대의 뷰를 조립한다. 인자로 받은 값만 사용하고 <b>내부에서 추가 조회를 하지 않는다</b>(N+1 방지).
     * 위치가 없으면 {@code location} 전체가 null이고, 진행 중 작업이 없으면 {@code currentTask}가 null이다 —
     * 없는 값을 현재 시각 등으로 만들어내지 않는다(prompt56.md 8·15장).
     */
    private DashboardResponse.VehicleView toVehicleView(
            VehicleResponse vr,
            VehicleLocationSnapshot loc,
            TransportTask currentTask,
            Map<Long, TransportCommand> latestCommandByTaskId) {
        VehicleStatusResponse s = vr.status();
        DashboardResponse.LocationView locationView = loc == null ? null
                : new DashboardResponse.LocationView(
                        loc.x(), loc.y(), loc.heading(), loc.speed(), loc.frameId(),
                        loc.messageAt(), loc.receivedAt(), loc.source());
        DashboardResponse.CurrentTaskView currentTaskView = currentTask == null ? null
                : new DashboardResponse.CurrentTaskView(
                        currentTask.getTaskCode(),
                        currentTask.getStatus() == null ? null : currentTask.getStatus().name(),
                        commandStatusName(latestCommandByTaskId.get(currentTask.getId())),
                        CommunicationTime.toOffset(currentTask.getUpdatedAt()));
        OffsetDateTime lastUpdatedAt = s.messageAt() != null ? s.messageAt() : s.receivedAt();
        return new DashboardResponse.VehicleView(
                vr.vehicleId(), vr.name(), vr.active(),
                s.status(), locationView, currentTaskView, lastUpdatedAt);
    }

    private DashboardResponse.TaskView toTaskView(TransportTask task, TransportCommand latest) {
        return new DashboardResponse.TaskView(
                task.getTaskCode(),
                task.getVehicleId(),
                task.getStatus() == null ? null : task.getStatus().name(),
                commandStatusName(latest),
                CommunicationTime.toOffset(task.getUpdatedAt()));
    }

    private static String commandStatusName(TransportCommand command) {
        return command == null || command.getStatus() == null ? null : command.getStatus().name();
    }
}
