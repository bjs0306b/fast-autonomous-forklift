package com.fast.backend.monitoring.service;

import com.fast.backend.monitoring.dto.DashboardResponse;
import com.fast.backend.station.dto.CargoMeasuredHeight;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.service.StationMeasurementPlacementEligibility;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleResponse;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.location.LatestVehicleLocationProvider;
import com.fast.backend.vehicle.service.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대시보드의 적재 정보(적재 여부·화물 ID·물건 높이) 구성 규칙 검증.
 *
 * <p>핵심은 두 가지다.
 * <ol>
 *   <li><b>차량 보고값 우선.</b> 차량이 직접 보고한 hasCargo/cargoId 가 있으면 그대로 쓰고,
 *       비어 있을 때만 진행 중인 운반 작업으로 보완한다.</li>
 *   <li><b>높이 조회는 1회.</b> 차량이 몇 대든 측정 높이 조회는 한 번만 나가야 한다(N+1 금지).</li>
 * </ol>
 */
class MonitoringServiceCargoTest {

    private VehicleService vehicleService;
    private LatestVehicleLocationProvider locationProvider;
    private TransportTaskMapper transportTaskMapper;
    private StationMeasurementMapper stationMeasurementMapper;
    private MonitoringService service;

    @BeforeEach
    void setUp() {
        vehicleService = mock(VehicleService.class);
        locationProvider = mock(LatestVehicleLocationProvider.class);
        transportTaskMapper = mock(TransportTaskMapper.class);
        stationMeasurementMapper = mock(StationMeasurementMapper.class);
        service = new MonitoringService(
                vehicleService, locationProvider, transportTaskMapper, stationMeasurementMapper,
                new StationMeasurementPlacementEligibility(null));

        when(locationProvider.findAllLatest()).thenReturn(List.of());
        when(transportTaskMapper.findAll(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(transportTaskMapper.findActiveTasksWithVehicle()).thenReturn(List.of());
        when(transportTaskMapper.findLatestFailedTasksWithVehicle(anyInt())).thenReturn(List.of());
        when(stationMeasurementMapper.findLatestCargoHeights(any())).thenReturn(List.of());
    }

    /** 차량이 화물 정보를 보고하지 않으면 운반 작업으로 보완한다. TRANSPORTING 은 싣고 있는 상태다. */
    @Test
    void transportingTask_fillsCargoInfoAndHeight() {
        when(vehicleService.findActiveVehicles()).thenReturn(List.of(vehicle("FORKLIFT-01", null, null)));
        when(transportTaskMapper.findActiveTasksWithVehicle())
                .thenReturn(List.of(task("FORKLIFT-01", 1L, TaskStatus.TRANSPORTING)));
        when(stationMeasurementMapper.findLatestCargoHeights(List.of(1L)))
                .thenReturn(List.of(new CargoMeasuredHeight(1L, 1.25)));

        DashboardResponse.VehicleView view = single(service.getDashboard());

        assertThat(view.hasCargo()).isTrue();
        assertThat(view.cargoId()).isEqualTo(1L);
        assertThat(view.cargoHeight()).isEqualTo(1.25);
    }

    /** 아직 집기 전(MEASURING)이면 화물 ID 는 알아도 실려 있지는 않다. */
    @Test
    void taskBeforePickup_reportsCargoIdButNotLoaded() {
        when(vehicleService.findActiveVehicles()).thenReturn(List.of(vehicle("FORKLIFT-01", null, null)));
        when(transportTaskMapper.findActiveTasksWithVehicle())
                .thenReturn(List.of(task("FORKLIFT-01", 2L, TaskStatus.MEASURING)));

        DashboardResponse.VehicleView view = single(service.getDashboard());

        assertThat(view.hasCargo()).isFalse();
        assertThat(view.cargoId()).isEqualTo(2L);
    }

    /** 차량 보고값이 있으면 작업 상태보다 우선한다. */
    @Test
    void reportedCargoInfo_winsOverTaskDerivedValue() {
        when(vehicleService.findActiveVehicles())
                .thenReturn(List.of(vehicle("FORKLIFT-01", Boolean.TRUE, 3L)));
        when(transportTaskMapper.findActiveTasksWithVehicle())
                .thenReturn(List.of(task("FORKLIFT-01", 4L, TaskStatus.MEASURING)));

        DashboardResponse.VehicleView view = single(service.getDashboard());

        assertThat(view.hasCargo()).isTrue();
        assertThat(view.cargoId()).isEqualTo(3L);
    }

    /** 근거가 전혀 없으면 false 로 단정하지 않고 null(=확인 불가)을 준다. */
    @Test
    void noReportAndNoTask_leavesCargoUnknown() {
        when(vehicleService.findActiveVehicles()).thenReturn(List.of(vehicle("FORKLIFT-01", null, null)));

        DashboardResponse.VehicleView view = single(service.getDashboard());

        assertThat(view.hasCargo()).isNull();
        assertThat(view.cargoId()).isNull();
        assertThat(view.cargoHeight()).isNull();
        // 조회할 화물이 없으면 쿼리 자체를 실행하지 않는다(빈 IN 절 방지).
        verify(stationMeasurementMapper, never()).findLatestCargoHeights(any());
    }

    /** 차량이 여러 대여도 높이 조회는 정확히 1회다. */
    @Test
    void multipleVehicles_queryCargoHeightsOnlyOnce() {
        when(vehicleService.findActiveVehicles()).thenReturn(List.of(
                vehicle("FORKLIFT-01", null, null),
                vehicle("FORKLIFT-02", null, null),
                vehicle("FORKLIFT-03", null, null)));
        when(transportTaskMapper.findActiveTasksWithVehicle()).thenReturn(List.of(
                task("FORKLIFT-01", 10L, TaskStatus.TRANSPORTING),
                task("FORKLIFT-02", 11L, TaskStatus.TRANSPORTING),
                task("FORKLIFT-03", 12L, TaskStatus.PLACING)));
        when(stationMeasurementMapper.findLatestCargoHeights(any())).thenReturn(List.of(
                new CargoMeasuredHeight(10L, 0.5),
                new CargoMeasuredHeight(11L, 0.9)));

        DashboardResponse dashboard = service.getDashboard();

        verify(stationMeasurementMapper, times(1)).findLatestCargoHeights(any());
        Map<String, DashboardResponse.VehicleView> byId = dashboard.vehicles().stream()
                .collect(Collectors.toMap(DashboardResponse.VehicleView::vehicleId, view -> view));
        assertThat(byId.get("FORKLIFT-01").cargoHeight()).isEqualTo(0.5);
        assertThat(byId.get("FORKLIFT-02").cargoHeight()).isEqualTo(0.9);
        // 측정 결과가 없는 화물은 높이만 null 이고 화물 ID 는 그대로 남는다.
        assertThat(byId.get("FORKLIFT-03").cargoHeight()).isNull();
        assertThat(byId.get("FORKLIFT-03").cargoId()).isEqualTo(12L);
    }

    private static DashboardResponse.VehicleView single(DashboardResponse dashboard) {
        assertThat(dashboard.vehicles()).hasSize(1);
        return dashboard.vehicles().get(0);
    }

    private static VehicleResponse vehicle(String vehicleId, Boolean hasCargo, Long cargoId) {
        VehicleStatusResponse status = new VehicleStatusResponse(
                VehicleStatus.IDLE, null, null, null, null, null, hasCargo, cargoId, null, null);
        return new VehicleResponse(vehicleId, vehicleId, true, status);
    }

    private static TransportTask task(String vehicleId, Long cargoId, TaskStatus status) {
        TransportTask task = new TransportTask();
        task.setTaskCode("TASK-" + cargoId);
        task.setVehicleId(vehicleId);
        task.setCargoId(cargoId);
        task.setStatus(status);
        return task;
    }
}
