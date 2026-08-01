package com.fast.backend.monitoring.service;

import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.monitoring.dto.DashboardResponse;
import com.fast.backend.monitoring.dto.VehicleLocationLatestResponse;
import com.fast.backend.monitoring.dto.VehicleStatusMonitorResponse;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MonitoringService {

    private static final int DASHBOARD_TASK_LIMIT = 100;

    private final VehicleService vehicleService;
    private final LatestVehicleLocationProvider locationProvider;
    private final TransportTaskMapper transportTaskMapper;

    public MonitoringService(
            VehicleService vehicleService,
            LatestVehicleLocationProvider locationProvider,
            TransportTaskMapper transportTaskMapper) {
        this.vehicleService = vehicleService;
        this.locationProvider = locationProvider;
        this.transportTaskMapper = transportTaskMapper;
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
                detail.vehicleId(), status.status(), status.battery(), status.messageAt(), status.receivedAt());
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

        List<DashboardResponse.VehicleView> vehicles = activeVehicles.stream()
                .map(vehicle -> toVehicleView(
                        vehicle, locations.get(vehicle.vehicleId()), currentTasks.get(vehicle.vehicleId())))
                .toList();
        List<DashboardResponse.TaskView> tasks = recentTasks.stream().map(this::toTaskView).toList();
        return new DashboardResponse(vehicles, tasks);
    }

    private static VehicleStatusMonitorResponse toStatusResponse(VehicleResponse vehicle) {
        VehicleStatusResponse status = vehicle.status();
        return new VehicleStatusMonitorResponse(
                vehicle.vehicleId(), status.status(), status.battery(), status.messageAt(), status.receivedAt());
    }

    private DashboardResponse.VehicleView toVehicleView(
            VehicleResponse vehicle,
            VehicleLocationSnapshot location,
            TransportTask currentTask) {
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
                locationView, taskView, lastUpdatedAt);
    }

    private DashboardResponse.TaskView toTaskView(TransportTask task) {
        return new DashboardResponse.TaskView(
                task.getTaskCode(), task.getVehicleId(), task.getStatus().name(),
                CommunicationTime.toOffset(task.getUpdatedAt()));
    }
}
