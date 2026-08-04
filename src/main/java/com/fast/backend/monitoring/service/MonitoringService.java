package com.fast.backend.monitoring.service;

import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.monitoring.dto.DashboardResponse;
import com.fast.backend.monitoring.dto.VehicleLocationLatestResponse;
import com.fast.backend.monitoring.dto.VehicleStatusMonitorResponse;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.dto.CargoMeasuredHeight;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.service.StationMeasurementPlacementEligibility;
import com.fast.backend.transport.domain.TransportTask;
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
import java.util.List;
import java.util.Map;

@Service
public class MonitoringService {

    private static final int DASHBOARD_TASK_LIMIT = 100;
    /** 실패 경고는 차량당 1건만 쓴다. 차량 수보다 넉넉하되 무한정 읽지 않도록 상한을 둔다. */
    private static final int RECENT_FAILED_TASK_LIMIT = 50;

    private final VehicleService vehicleService;
    private final LatestVehicleLocationProvider locationProvider;
    private final TransportTaskMapper transportTaskMapper;
    private final StationMeasurementMapper stationMeasurementMapper;
    private final StationMeasurementPlacementEligibility placementEligibility;

    public MonitoringService(
            VehicleService vehicleService,
            LatestVehicleLocationProvider locationProvider,
            TransportTaskMapper transportTaskMapper,
            StationMeasurementMapper stationMeasurementMapper,
            StationMeasurementPlacementEligibility placementEligibility) {
        this.vehicleService = vehicleService;
        this.locationProvider = locationProvider;
        this.transportTaskMapper = transportTaskMapper;
        this.stationMeasurementMapper = stationMeasurementMapper;
        this.placementEligibility = placementEligibility;
    }

    @Transactional(readOnly = true)
    public List<VehicleStatusMonitorResponse> getCurrentStatuses() {
        return vehicleService.findActiveVehicles().stream().map(MonitoringService::toStatusResponse).toList();
    }

    @Transactional(readOnly = true)
    public VehicleStatusMonitorResponse getCurrentStatus(String vehicleId) {
        VehicleDetailResponse detail = vehicleService.getDetail(vehicleId);
        VehicleStatusResponse status = detail.status();
        return new VehicleStatusMonitorResponse(
                detail.vehicleId(), status.status(), status.messageAt(), status.receivedAt());
    }

    public List<VehicleLocationLatestResponse> getLatestLocations() {
        return locationProvider.findAllLatest().stream()
                .sorted(Comparator.comparing(VehicleLocationSnapshot::vehicleId))
                .map(VehicleLocationLatestResponse::from)
                .toList();
    }

    public VehicleLocationLatestResponse getLatestLocation(String vehicleId) {
        return locationProvider.findLatest(vehicleId).map(VehicleLocationLatestResponse::from).orElse(null);
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        List<VehicleResponse> activeVehicles = vehicleService.findActiveVehicles();
        Map<String, VehicleLocationSnapshot> locations = new HashMap<>();
        locationProvider.findAllLatest().forEach(location -> locations.put(location.vehicleId(), location));

        List<TransportTask> recentTasks =
                transportTaskMapper.findAll(null, null, null, DASHBOARD_TASK_LIMIT, 0);
        Map<String, TransportTask> currentTasks = new HashMap<>();
        transportTaskMapper.findActiveTasksWithVehicle()
                .forEach(task -> currentTasks.putIfAbsent(task.getVehicleId(), task));

        // 화물 높이는 차량 목록이 확정된 뒤 한 번에 조회한다(차량별 조회 금지 — N+1).
        Map<String, Long> cargoIdByVehicleId = new HashMap<>();
        for (VehicleResponse vehicle : activeVehicles) {
            Long cargoId = resolveCargoId(vehicle, currentTasks.get(vehicle.vehicleId()));
            if (cargoId != null) {
                cargoIdByVehicleId.put(vehicle.vehicleId(), cargoId);
            }
        }
        Map<Long, Double> cargoHeights = findCargoHeights(cargoIdByVehicleId.values());
        Map<String, DashboardResponse.FailureView> failures = findLatestFailures(currentTasks);

        List<DashboardResponse.VehicleView> vehicles = activeVehicles.stream()
                .map(vehicle -> {
                    Long cargoId = cargoIdByVehicleId.get(vehicle.vehicleId());
                    return toVehicleView(
                            vehicle,
                            locations.get(vehicle.vehicleId()),
                            currentTasks.get(vehicle.vehicleId()),
                            cargoId,
                            cargoId == null ? null : cargoHeights.get(cargoId),
                            failures.get(vehicle.vehicleId()));
                })
                .toList();
        List<DashboardResponse.TaskView> tasks = recentTasks.stream().map(this::toTaskView).toList();
        return new DashboardResponse(vehicles, tasks);
    }

    private static VehicleStatusMonitorResponse toStatusResponse(VehicleResponse vehicle) {
        VehicleStatusResponse status = vehicle.status();
        return new VehicleStatusMonitorResponse(
                vehicle.vehicleId(), status.status(), status.messageAt(), status.receivedAt());
    }

    /**
     * 이 차량과 연결된 화물 식별자.
     *
     * <p>정본은 차량이 보고하는 {@code vehicle_current_status.cargo_id} 다. 다만 현재 MQTT 상태
     * 메시지에는 화물 정보가 없어 이 값이 채워지지 않으므로, 비어 있으면 진행 중인 운반 작업의
     * 화물로 대신한다. 작업의 {@code cargo_id} 는 작업 생성 시 반드시 채워지는 실데이터다.
     */
    private static Long resolveCargoId(VehicleResponse vehicle, TransportTask currentTask) {
        Long reported = vehicle.status().cargoId();
        if (reported != null) {
            return reported;
        }
        return currentTask == null ? null : currentTask.getCargoId();
    }

    /**
     * 적재 여부. 차량 보고값이 있으면 그대로 믿고, 없을 때만 작업 상태로 추정한다.
     * 추정할 근거조차 없으면 {@code null} 을 돌려 화면이 "확인 불가"로 표시하게 한다 —
     * 모르는 상태를 {@code false}(미적재)로 단정하지 않는다.
     */
    private static Boolean resolveHasCargo(VehicleResponse vehicle, TransportTask currentTask) {
        Boolean reported = vehicle.status().hasCargo();
        if (reported != null) {
            return reported;
        }
        if (currentTask == null || currentTask.getStatus() == null) {
            return null;
        }
        return currentTask.getStatus().impliesCargoOnVehicle();
    }

    /** 화물 식별자 → 최근 측정 높이(m). 조회 대상이 없으면 쿼리를 아예 실행하지 않는다. */
    private Map<Long, Double> findCargoHeights(Collection<Long> cargoIds) {
        List<Long> distinct = cargoIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, Double> heights = new HashMap<>();
        // 쿼리가 오래된 것 → 최신 순으로 주므로, 뒤 행이 앞 행을 덮어 최신 1건만 남는다.
        for (CargoMeasuredHeight row : stationMeasurementMapper.findLatestCargoHeights(distinct)) {
            heights.put(row.cargoId(), row.cargoHeight());
        }
        return heights;
    }

    /**
     * 차량별 "지금 보여줘야 할" 실패 1건.
     *
     * <p><b>진행 중인 작업이 있으면 실패를 보여주지 않는다</b> — 실패 후 다음 작업이 배차됐다면 그
     * 경고는 이미 지나간 상황이고, 화면에 남겨 두면 작업자가 현재 상태를 오해한다.
     *
     * <p>측정 상세(전복 등급·돌출률·적재 적합 여부)는 세션 식별자로 한 번에 조회한다. 실패 작업은
     * {@code measurement_id} 가 연결되지 않으므로 그 경로로는 찾을 수 없다.
     */
    private Map<String, DashboardResponse.FailureView> findLatestFailures(
            Map<String, TransportTask> currentTasks) {
        // 쿼리가 최신 순이므로 putIfAbsent 로 차량당 첫(=가장 최근) 실패만 남는다.
        Map<String, TransportTask> failedByVehicle = new HashMap<>();
        for (TransportTask task : transportTaskMapper.findLatestFailedTasksWithVehicle(
                RECENT_FAILED_TASK_LIMIT)) {
            if (!currentTasks.containsKey(task.getVehicleId())) {
                failedByVehicle.putIfAbsent(task.getVehicleId(), task);
            }
        }
        if (failedByVehicle.isEmpty()) {
            return Map.of();
        }

        List<String> sessionIds = failedByVehicle.values().stream()
                .map(TransportTask::getMeasurementSessionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<String, StationMeasurement> measurements = new HashMap<>();
        if (!sessionIds.isEmpty()) {
            // 오래된 것 → 최신 순이라 뒤 행이 앞 행을 덮어 세션당 최신 1건만 남는다.
            stationMeasurementMapper.findBySessionIds(sessionIds)
                    .forEach(m -> measurements.put(m.getSessionId(), m));
        }

        Map<String, DashboardResponse.FailureView> views = new HashMap<>();
        failedByVehicle.forEach((vehicleId, task) -> views.put(vehicleId, toFailureView(task,
                task.getMeasurementSessionId() == null ? null
                        : measurements.get(task.getMeasurementSessionId()))));
        return views;
    }

    private DashboardResponse.FailureView toFailureView(TransportTask task, StationMeasurement measurement) {
        return new DashboardResponse.FailureView(
                task.getTaskCode(),
                task.getFailureCode() == null ? null : task.getFailureCode().name(),
                measurement == null || measurement.getStatus() == null
                        ? null : measurement.getStatus().rawValue(),
                // 측정 결과가 없으면 적합 여부를 판정한 적이 없다 — false 로 단정하지 않는다.
                measurement == null ? null : placementEligibility.isEligible(measurement),
                measurement == null ? null : measurement.getOverhangRatio(),
                measurement == null ? null : measurement.getTippingLevel(),
                CommunicationTime.toOffset(task.getFailedAt()));
    }

    private DashboardResponse.VehicleView toVehicleView(
            VehicleResponse vehicle,
            VehicleLocationSnapshot location,
            TransportTask currentTask,
            Long cargoId,
            Double cargoHeight,
            DashboardResponse.FailureView lastFailure) {
        VehicleStatusResponse status = vehicle.status();
        DashboardResponse.LocationView locationView = location == null ? null
                : new DashboardResponse.LocationView(
                        location.x(), location.y(), location.heading(), location.speed(), location.frameId(),
                        location.messageAt(), location.receivedAt());
        DashboardResponse.CurrentTaskView taskView = currentTask == null ? null
                : new DashboardResponse.CurrentTaskView(
                        currentTask.getTaskCode(), currentTask.getStatus().name(),
                        CommunicationTime.toOffset(currentTask.getUpdatedAt()));
        OffsetDateTime lastUpdatedAt = status.messageAt() != null ? status.messageAt() : status.receivedAt();
        return new DashboardResponse.VehicleView(
                vehicle.vehicleId(), vehicle.name(), vehicle.active(), status.status(),
                locationView, taskView,
                resolveHasCargo(vehicle, currentTask), cargoId, cargoHeight, lastFailure,
                lastUpdatedAt);
    }

    private DashboardResponse.TaskView toTaskView(TransportTask task) {
        return new DashboardResponse.TaskView(
                task.getTaskCode(), task.getVehicleId(), task.getStatus().name(),
                task.getFailureCode() == null ? null : task.getFailureCode().name(),
                CommunicationTime.toOffset(task.getUpdatedAt()));
    }
}
