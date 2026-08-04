package com.fast.backend.monitoring.service;

import com.fast.backend.monitoring.dto.DashboardResponse;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.service.StationMeasurementPlacementEligibility;
import com.fast.backend.transport.domain.TaskFailureCode;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 대시보드가 실패 원인을 관제 화면까지 실어 나르는지 검증한다.
 *
 * <p>백엔드는 <b>문구를 만들지 않는다</b> — 원인 코드와 원본 측정값만 내려주고, 사용자 문구는
 * 프론트가 한 곳에서 매핑한다. 그래서 여기서 검증하는 것도 코드와 원본값이다.
 */
class MonitoringServiceFailureTest {

    private VehicleService vehicleService;
    private TransportTaskMapper transportTaskMapper;
    private StationMeasurementMapper stationMeasurementMapper;
    private MonitoringService service;

    @BeforeEach
    void setUp() {
        vehicleService = mock(VehicleService.class);
        LatestVehicleLocationProvider locationProvider = mock(LatestVehicleLocationProvider.class);
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
        when(stationMeasurementMapper.findBySessionIds(any())).thenReturn(List.of());
        when(vehicleService.findActiveVehicles()).thenReturn(List.of(vehicle("FORKLIFT-01")));
    }

    @Test
    void noDetectionFailure_exposesCodeAndRawStatusWithoutPlacementDetails() {
        givenFailedTask(TaskFailureCode.MEASUREMENT_NO_DETECTION,
                measurement(StationMeasurementStatus.NO_DETECTION, null, null));

        DashboardResponse.FailureView failure = failureOfSingleVehicle();

        assertThat(failure.failureCode()).isEqualTo("MEASUREMENT_NO_DETECTION");
        assertThat(failure.measurementStatus()).isEqualTo("no_detection");
        assertThat(failure.overhangRatio()).isNull();
        assertThat(failure.tippingLevel()).isNull();
    }

    /** 치수는 쟀지만 파렛트를 못 찾은 상태. 화면이 "편하중·전복 판정 불가"를 말할 근거가 여기 있다. */
    @Test
    void dimensionsOnlyFailure_keepsRawStatusSoScreenCanExplainWhatIsMissing() {
        givenFailedTask(TaskFailureCode.MEASUREMENT_PALLET_NOT_DETECTED,
                measurement(StationMeasurementStatus.DIMENSIONS_ONLY, null, null));

        DashboardResponse.FailureView failure = failureOfSingleVehicle();

        assertThat(failure.failureCode()).isEqualTo("MEASUREMENT_PALLET_NOT_DETECTED");
        assertThat(failure.measurementStatus()).isEqualTo("dimensions_only");
        assertThat(failure.placementEligible()).isFalse();
    }

    @Test
    void placementIneligible_exposesOverhangAndTippingDetails() {
        givenFailedTask(TaskFailureCode.PLACEMENT_INELIGIBLE,
                measurement(StationMeasurementStatus.OK, 0.082, "DANGER"));

        DashboardResponse.FailureView failure = failureOfSingleVehicle();

        assertThat(failure.failureCode()).isEqualTo("PLACEMENT_INELIGIBLE");
        assertThat(failure.measurementStatus()).isEqualTo("ok");
        assertThat(failure.placementEligible()).isFalse();
        assertThat(failure.overhangRatio()).isEqualTo(0.082);
        assertThat(failure.tippingLevel()).isEqualTo("DANGER");
    }

    /** 결과가 아예 오지 않은 실패. 측정값이 없다고 적합 여부를 false 로 단정하지 않는다. */
    @Test
    void timeoutFailure_hasNoMeasurementAndLeavesEligibilityUnknown() {
        TransportTask task = failedTask(TaskFailureCode.MEASUREMENT_NO_RESPONSE);
        task.setMeasurementSessionId(null);
        when(transportTaskMapper.findLatestFailedTasksWithVehicle(anyInt())).thenReturn(List.of(task));

        DashboardResponse.FailureView failure = failureOfSingleVehicle();

        assertThat(failure.failureCode()).isEqualTo("MEASUREMENT_NO_RESPONSE");
        assertThat(failure.measurementStatus()).isNull();
        assertThat(failure.placementEligible()).isNull();
    }

    /** 실패 뒤 새 작업이 배차됐다면 지나간 경고다 — 화면에 남겨 두면 현재 상태를 오해한다. */
    @Test
    void newActiveTask_hidesPreviousFailure() {
        givenFailedTask(TaskFailureCode.MEASUREMENT_NO_DETECTION,
                measurement(StationMeasurementStatus.NO_DETECTION, null, null));
        TransportTask active = new TransportTask();
        active.setTaskCode("TASK-NEXT");
        active.setVehicleId("FORKLIFT-01");
        active.setCargoId(2L);
        active.setStatus(TaskStatus.MOVING_TO_PICKUP);
        when(transportTaskMapper.findActiveTasksWithVehicle()).thenReturn(List.of(active));

        assertThat(service.getDashboard().vehicles().get(0).lastFailure()).isNull();
    }

    @Test
    void noFailedTask_leavesLastFailureNull() {
        assertThat(service.getDashboard().vehicles().get(0).lastFailure()).isNull();
    }

    private void givenFailedTask(TaskFailureCode code, StationMeasurement measurement) {
        when(transportTaskMapper.findLatestFailedTasksWithVehicle(anyInt()))
                .thenReturn(List.of(failedTask(code)));
        when(stationMeasurementMapper.findBySessionIds(List.of("SESSION-1")))
                .thenReturn(List.of(measurement));
    }

    private DashboardResponse.FailureView failureOfSingleVehicle() {
        DashboardResponse dashboard = service.getDashboard();
        assertThat(dashboard.vehicles()).hasSize(1);
        DashboardResponse.FailureView failure = dashboard.vehicles().get(0).lastFailure();
        assertThat(failure).isNotNull();
        return failure;
    }

    private static TransportTask failedTask(TaskFailureCode code) {
        TransportTask task = new TransportTask();
        task.setId(1L);
        task.setTaskCode("TASK-1");
        task.setVehicleId("FORKLIFT-01");
        task.setCargoId(1L);
        task.setMeasurementSessionId("SESSION-1");
        task.setStatus(TaskStatus.FAILED);
        task.setFailureCode(code);
        task.setFailedAt(LocalDateTime.of(2026, 8, 4, 10, 0));
        return task;
    }

    private static StationMeasurement measurement(
            StationMeasurementStatus status, Double overhangRatio, String tippingLevel) {
        StationMeasurement measurement = new StationMeasurement();
        measurement.setMeasurementId("M-1");
        measurement.setSessionId("SESSION-1");
        measurement.setStatus(status);
        measurement.setCargoHeight(status == StationMeasurementStatus.OK
                || status == StationMeasurementStatus.DIMENSIONS_ONLY ? 1.2 : null);
        measurement.setOverhangRatio(overhangRatio);
        measurement.setTippingLevel(tippingLevel);
        return measurement;
    }

    private static VehicleResponse vehicle(String vehicleId) {
        VehicleStatusResponse status = new VehicleStatusResponse(
                VehicleStatus.IDLE, null, null, null, null, null, null, null, null, null);
        return new VehicleResponse(vehicleId, vehicleId, true, status);
    }
}
