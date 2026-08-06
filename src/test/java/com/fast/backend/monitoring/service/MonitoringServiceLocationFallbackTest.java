package com.fast.backend.monitoring.service;

import com.fast.backend.monitoring.dto.DashboardResponse;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.service.StationMeasurementPlacementEligibility;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleResponse;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.location.LatestVehicleLocationProvider;
import com.fast.backend.vehicle.location.VehicleLocationSnapshot;
import com.fast.backend.vehicle.service.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 대시보드 위치 구성 규칙 검증 — 인메모리 최신 위치 우선, 없으면 DB 좌표 폴백.
 *
 * <p>폴백이 없으면 <b>백엔드를 재시작한 직후</b> 대시보드가 {@code location=null} 을 내려주고, 화면은
 * 좌표를 이미 아는 차량인데도 "위치 미수신" 으로 표시한다. 최신 위치는 인메모리라 재시작 시 사라지지만
 * DB {@code vehicle_current_status} 에는 같은 좌표가 남아 있기 때문에 생기는 공백이다.
 */
class MonitoringServiceLocationFallbackTest {

    private static final OffsetDateTime STATUS_TIME = OffsetDateTime.parse("2026-08-05T10:00:00+09:00");
    private static final OffsetDateTime LIVE_TIME = OffsetDateTime.parse("2026-08-05T10:00:05+09:00");

    private VehicleService vehicleService;
    private LatestVehicleLocationProvider locationProvider;
    private MonitoringService service;

    @BeforeEach
    void setUp() {
        vehicleService = mock(VehicleService.class);
        locationProvider = mock(LatestVehicleLocationProvider.class);
        TransportTaskMapper transportTaskMapper = mock(TransportTaskMapper.class);
        StationMeasurementMapper stationMeasurementMapper = mock(StationMeasurementMapper.class);
        service = new MonitoringService(
                vehicleService, locationProvider, transportTaskMapper, stationMeasurementMapper,
                new StationMeasurementPlacementEligibility(null));

        when(locationProvider.findAllLatest()).thenReturn(List.of());
        when(transportTaskMapper.findAll(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(transportTaskMapper.findActiveTasksWithVehicle()).thenReturn(List.of());
        when(transportTaskMapper.findLatestFailedTasksWithVehicle(anyInt())).thenReturn(List.of());
        when(stationMeasurementMapper.findLatestCargoHeights(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("인메모리 최신 위치가 있으면 그 값을 그대로 쓴다")
    void usesInMemorySnapshotWhenPresent() {
        when(vehicleService.findActiveVehicles()).thenReturn(List.of(vehicleWithDbLocation()));
        when(locationProvider.findAllLatest()).thenReturn(List.of(new VehicleLocationSnapshot(
                "FORKLIFT-01", 9.9, 8.8, 45.0, 1.5, "map", LIVE_TIME, LIVE_TIME)));

        DashboardResponse.LocationView location = single(service.getDashboard()).location();

        assertThat(location).isNotNull();
        assertThat(location.x()).isEqualTo(9.9);
        assertThat(location.y()).isEqualTo(8.8);
        assertThat(location.heading()).isEqualTo(45.0);
        assertThat(location.speed()).isEqualTo(1.5);
        assertThat(location.receivedAt()).isEqualTo(LIVE_TIME);
    }

    @Test
    @DisplayName("재시작 등으로 인메모리 위치가 비면 vehicle_current_status 좌표로 폴백한다")
    void fallsBackToCurrentStatusLocationAfterRestart() {
        when(vehicleService.findActiveVehicles()).thenReturn(List.of(vehicleWithDbLocation()));
        // 인메모리 공급자는 비어 있다(= 백엔드 재시작 직후 상태).

        DashboardResponse.LocationView location = single(service.getDashboard()).location();

        assertThat(location).isNotNull();
        assertThat(location.x()).isEqualTo(3.5);
        assertThat(location.y()).isEqualTo(7.25);
        assertThat(location.heading()).isEqualTo(270.0);
        assertThat(location.speed()).isEqualTo(0.4);
        assertThat(location.frameId()).isEqualTo("map");
        assertThat(location.messageAt()).isEqualTo(STATUS_TIME);
        assertThat(location.receivedAt()).isEqualTo(STATUS_TIME);
    }

    @Test
    @DisplayName("좌표를 한 번도 받은 적 없는 차량은 위치를 만들어 내지 않는다")
    void keepsLocationNullWhenNoCoordinateWasEverReceived() {
        VehicleStatusResponse status = new VehicleStatusResponse(
                VehicleStatus.IDLE, null, null, null, null, null, null, null, STATUS_TIME, STATUS_TIME);
        when(vehicleService.findActiveVehicles())
                .thenReturn(List.of(new VehicleResponse("FORKLIFT-01", "FORKLIFT-01", true, status)));

        assertThat(single(service.getDashboard()).location()).isNull();
    }

    @Test
    @DisplayName("x 만 있고 y 가 없는 불완전한 좌표는 폴백하지 않는다")
    void doesNotFallBackOnPartialCoordinates() {
        VehicleStatusResponse status = new VehicleStatusResponse(
                VehicleStatus.IDLE, 3.5, null, "map", 270.0, 0.4, null, null, STATUS_TIME, STATUS_TIME);
        when(vehicleService.findActiveVehicles())
                .thenReturn(List.of(new VehicleResponse("FORKLIFT-01", "FORKLIFT-01", true, status)));

        assertThat(single(service.getDashboard()).location()).isNull();
    }

    private static VehicleResponse vehicleWithDbLocation() {
        VehicleStatusResponse status = new VehicleStatusResponse(
                VehicleStatus.IDLE, 3.5, 7.25, "map", 270.0, 0.4, null, null, STATUS_TIME, STATUS_TIME);
        return new VehicleResponse("FORKLIFT-01", "FORKLIFT-01", true, status);
    }

    private static DashboardResponse.VehicleView single(DashboardResponse dashboard) {
        assertThat(dashboard.vehicles()).hasSize(1);
        return dashboard.vehicles().get(0);
    }
}
