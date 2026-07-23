package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleCreateRequest;
import com.fast.backend.vehicle.dto.VehicleDetailResponse;
import com.fast.backend.vehicle.dto.VehicleResponse;
import com.fast.backend.vehicle.dto.VehicleStatusCountResponse;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusCountRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * VehicleMapper/VehicleCurrentStatusMapper를 모킹해 실제 DB 없이 VehicleService의 조합·판단
 * 로직만 검증한다(DB 연동 자체는 VehicleMapperTest/VehicleCurrentStatusMapperTest가 실제 H2로 검증).
 */
class VehicleServiceTest {

    private VehicleMapper vehicleMapper;
    private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;
    private VehicleService vehicleService;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        vehicleCurrentStatusMapper = mock(VehicleCurrentStatusMapper.class);
        vehicleService = new VehicleService(vehicleMapper, vehicleCurrentStatusMapper);
    }

    @Test
    void register_newVehicleId_insertsAndReturnsUnknownStatus() {
        when(vehicleMapper.existsByVehicleId("SIM-F01")).thenReturn(false);

        VehicleDetailResponse response = vehicleService.register(
                new VehicleCreateRequest("SIM-F01", "시뮬레이션 지게차 1호", VehicleSource.SIMULATION));

        verify(vehicleMapper).insert(any(Vehicle.class));
        ArgumentCaptor<VehicleCurrentStatus> statusCaptor = ArgumentCaptor.forClass(VehicleCurrentStatus.class);
        verify(vehicleCurrentStatusMapper).upsert(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getVehicleId()).isEqualTo("SIM-F01");
        assertThat(statusCaptor.getValue().getStatus()).isEqualTo(VehicleStatus.UNKNOWN);
        assertThat(statusCaptor.getValue().getMessageAt()).isNull();
        assertThat(statusCaptor.getValue().getReceivedAt()).isNotNull();
        assertThat(response.vehicleId()).isEqualTo("SIM-F01");
        assertThat(response.source()).isEqualTo(VehicleSource.SIMULATION);
        assertThat(response.active()).isTrue();
        assertThat(response.status().status()).isEqualTo(VehicleStatus.UNKNOWN);
    }

    @Test
    void register_duplicateVehicleId_throwsConflictAndNeverInserts() {
        when(vehicleMapper.existsByVehicleId("SIM-F01")).thenReturn(true);

        BusinessException exception = catchThrowableOfType(
                () -> vehicleService.register(new VehicleCreateRequest("SIM-F01", "x", VehicleSource.SIMULATION)),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_ID_DUPLICATED);
        verify(vehicleMapper, never()).insert(any());
        verify(vehicleCurrentStatusMapper, never()).upsert(any());
    }

    @Test
    void updateActive_existingVehicle_updatesAndReturnsDetailWithStatus() {
        Vehicle vehicle = vehicle("SIM-F01");
        VehicleCurrentStatus status = new VehicleCurrentStatus();
        status.setVehicleId("SIM-F01");
        status.setStatus(VehicleStatus.ACTIVE);
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(status));

        VehicleDetailResponse response = vehicleService.updateActive("SIM-F01", false);

        verify(vehicleMapper).updateActive(eq("SIM-F01"), eq(false), any(LocalDateTime.class));
        assertThat(response.active()).isFalse();
        assertThat(response.status().status()).isEqualTo(VehicleStatus.ACTIVE);
    }

    @Test
    void updateActive_unknownVehicle_throwsNotFoundAndNeverUpdates() {
        when(vehicleMapper.findByVehicleId("NOPE")).thenReturn(Optional.empty());

        BusinessException exception = catchThrowableOfType(
                () -> vehicleService.updateActive("NOPE", false), BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
        verify(vehicleMapper, never()).updateActive(any(), any(Boolean.class), any());
    }

    @Test
    void getDetail_unknownVehicleId_throwsVehicleNotFound() {
        when(vehicleMapper.findByVehicleId("NOPE")).thenReturn(Optional.empty());

        BusinessException exception = catchThrowableOfType(
                () -> vehicleService.getDetail("NOPE"), BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
    }

    @Test
    void getDetail_noStatusRow_returnsNonNullUnknownStatus() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01")));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.empty());

        VehicleDetailResponse response = vehicleService.getDetail("SIM-F01");

        assertThat(response.status()).isNotNull();
        assertThat(response.status().status()).isEqualTo(VehicleStatus.UNKNOWN);
        assertThat(response.status().battery()).isNull();
    }

    @Test
    void getDetail_withStatusRow_returnsActualStatus() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01")));
        VehicleCurrentStatus status = new VehicleCurrentStatus();
        status.setVehicleId("SIM-F01");
        status.setStatus(VehicleStatus.ACTIVE);
        status.setBattery(82);
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(status));

        VehicleDetailResponse response = vehicleService.getDetail("SIM-F01");

        assertThat(response.status().status()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(response.status().battery()).isEqualTo(82);
    }

    @Test
    void findActiveVehicles_combinesVehicleAndStatusData() {
        when(vehicleMapper.findAllActive()).thenReturn(List.of(vehicle("SIM-F01"), vehicle("SIM-F02")));
        VehicleCurrentStatus status = new VehicleCurrentStatus();
        status.setVehicleId("SIM-F01");
        status.setStatus(VehicleStatus.ACTIVE);
        when(vehicleCurrentStatusMapper.findAllByVehicleIds(anyList())).thenReturn(List.of(status));

        List<VehicleResponse> result = vehicleService.findActiveVehicles();

        assertThat(result).hasSize(2);
        VehicleResponse f01 = result.stream().filter(r -> r.vehicleId().equals("SIM-F01")).findFirst().orElseThrow();
        VehicleResponse f02 = result.stream().filter(r -> r.vehicleId().equals("SIM-F02")).findFirst().orElseThrow();
        assertThat(f01.status().status()).isEqualTo(VehicleStatus.ACTIVE);
        // SIM-F02는 상태 행이 없으므로 UNKNOWN(null 아님)이어야 한다.
        assertThat(f02.status().status()).isEqualTo(VehicleStatus.UNKNOWN);
    }

    @Test
    void findActiveVehicles_noVehicles_returnsEmptyListWithoutQueryingStatus() {
        when(vehicleMapper.findAllActive()).thenReturn(List.of());

        List<VehicleResponse> result = vehicleService.findActiveVehicles();

        assertThat(result).isEmpty();
        verify(vehicleCurrentStatusMapper, never()).findAllByVehicleIds(anyList());
    }

    @Test
    void countByStatus_fillsZeroForStatusesWithNoVehicles() {
        when(vehicleCurrentStatusMapper.countByStatusForActiveVehicles()).thenReturn(List.of(
                countRow("IDLE", 1), countRow("ACTIVE", 2)));

        VehicleStatusCountResponse response = vehicleService.countByStatus();

        assertThat(response.total()).isEqualTo(3);
        assertThat(response.items()).hasSize(VehicleStatus.values().length);
        assertThat(response.items()).extracting(VehicleStatusCountResponse.StatusCount::status)
                .containsExactlyInAnyOrder("UNKNOWN", "IDLE", "ACTIVE", "ERROR", "OFFLINE");
        long idleCount = response.items().stream()
                .filter(i -> i.status().equals("IDLE")).findFirst().orElseThrow().count();
        long offlineCount = response.items().stream()
                .filter(i -> i.status().equals("OFFLINE")).findFirst().orElseThrow().count();
        assertThat(idleCount).isEqualTo(1);
        assertThat(offlineCount).isEqualTo(0);
    }

    private Vehicle vehicle(String vehicleId) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(1L);
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId + " 이름");
        vehicle.setSource(VehicleSource.SIMULATION);
        vehicle.setActive(true);
        vehicle.setCreatedAt(LocalDateTime.now());
        vehicle.setUpdatedAt(LocalDateTime.now());
        return vehicle;
    }

    private VehicleStatusCountRow countRow(String status, long count) {
        VehicleStatusCountRow row = new VehicleStatusCountRow();
        row.setStatus(status);
        row.setCount(count);
        return row;
    }
}
