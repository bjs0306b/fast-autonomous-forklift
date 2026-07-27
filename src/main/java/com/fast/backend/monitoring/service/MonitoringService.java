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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

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

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        List<DashboardResponse.VehicleView> vehicles = vehicleService.findActiveVehicles().stream()
                .map(this::toVehicleView)
                .collect(Collectors.toList());

        List<DashboardResponse.TaskView> tasks =
                transportTaskMapper.findAll(null, null, null, DASHBOARD_TASK_LIMIT, 0).stream()
                        .map(this::toTaskView)
                        .collect(Collectors.toList());

        return new DashboardResponse(vehicles, tasks);
    }

    private static VehicleStatusMonitorResponse toStatusResponse(VehicleResponse vr) {
        VehicleStatusResponse s = vr.status();
        return new VehicleStatusMonitorResponse(
                vr.vehicleId(), s.status(), s.battery(), s.messageAt(), s.receivedAt());
    }

    private DashboardResponse.VehicleView toVehicleView(VehicleResponse vr) {
        VehicleStatusResponse s = vr.status();
        VehicleLocationSnapshot loc = locationProvider.findLatest(vr.vehicleId()).orElse(null);
        DashboardResponse.LocationView locationView = loc == null ? null
                : new DashboardResponse.LocationView(loc.x(), loc.y(), loc.heading(), loc.speed(), loc.frameId());
        OffsetDateTime lastUpdatedAt = s.messageAt() != null ? s.messageAt() : s.receivedAt();
        return new DashboardResponse.VehicleView(vr.vehicleId(), s.status(), locationView, lastUpdatedAt);
    }

    private DashboardResponse.TaskView toTaskView(TransportTask task) {
        TransportCommand latest = transportCommandMapper.findLatestByTaskId(task.getId()).orElse(null);
        return new DashboardResponse.TaskView(
                task.getTaskCode(),
                task.getVehicleId(),
                task.getStatus() == null ? null : task.getStatus().name(),
                latest == null ? null : latest.getStatus().name(),
                CommunicationTime.toOffset(task.getUpdatedAt()));
    }
}
