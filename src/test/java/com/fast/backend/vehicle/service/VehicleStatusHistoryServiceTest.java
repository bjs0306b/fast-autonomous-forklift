package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import com.fast.backend.vehicle.dto.VehicleStatusHistoryResponse;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VehicleStatusHistoryService.findRecentHistory의 존재 확인·limit 검증 순서를 Mapper를 모킹해 검증한다
 * (prompt22.md 8장).
 */
class VehicleStatusHistoryServiceTest {

    private VehicleMapper vehicleMapper;
    private VehicleStatusHistoryMapper vehicleStatusHistoryMapper;
    private VehicleStatusHistoryService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        vehicleStatusHistoryMapper = mock(VehicleStatusHistoryMapper.class);
        service = new VehicleStatusHistoryService(vehicleMapper, vehicleStatusHistoryMapper);
    }

    @Test
    void findRecentHistory_unregisteredVehicle_throwsNotFound() {
        when(vehicleMapper.findByVehicleId("NOPE")).thenReturn(Optional.empty());

        BusinessException exception = catchThrowableOfType(
                () -> service.findRecentHistory("NOPE", 50),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
        verify(vehicleStatusHistoryMapper, never()).findRecentByVehicleId(org.mockito.ArgumentMatchers.anyString(), anyInt());
    }

    @Test
    void findRecentHistory_noHistory_returnsEmptyList() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle()));
        when(vehicleStatusHistoryMapper.findRecentByVehicleId("SIM-F01", 50)).thenReturn(List.of());

        List<VehicleStatusHistoryResponse> result = service.findRecentHistory("SIM-F01", 50);

        assertThat(result).isEmpty();
    }

    @Test
    void findRecentHistory_returnsMappedResponses() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle()));
        VehicleStatusHistory history = history(1L, "SIM-F01", VehicleStatus.ACTIVE, 82);
        when(vehicleStatusHistoryMapper.findRecentByVehicleId("SIM-F01", 50)).thenReturn(List.of(history));

        List<VehicleStatusHistoryResponse> result = service.findRecentHistory("SIM-F01", 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).vehicleId()).isEqualTo("SIM-F01");
        assertThat(result.get(0).status()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(result.get(0).battery()).isEqualTo(82);
    }

    @Test
    void findRecentHistory_limitBelowMinimum_throwsBadRequest() {
        BusinessException exception = catchThrowableOfType(
                () -> service.findRecentHistory("SIM-F01", 0),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_STATUS_HISTORY_LIMIT_INVALID);
        verify(vehicleMapper, never()).findByVehicleId(eq("SIM-F01"));
    }

    @Test
    void findRecentHistory_limitAboveMaximum_throwsBadRequest() {
        BusinessException exception = catchThrowableOfType(
                () -> service.findRecentHistory("SIM-F01", 201),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_STATUS_HISTORY_LIMIT_INVALID);
    }

    @Test
    void findRecentHistory_limitAtBoundaries_isAccepted() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle()));
        when(vehicleStatusHistoryMapper.findRecentByVehicleId("SIM-F01", 1)).thenReturn(List.of());
        when(vehicleStatusHistoryMapper.findRecentByVehicleId("SIM-F01", 200)).thenReturn(List.of());

        service.findRecentHistory("SIM-F01", 1);
        service.findRecentHistory("SIM-F01", 200);

        verify(vehicleStatusHistoryMapper).findRecentByVehicleId("SIM-F01", 1);
        verify(vehicleStatusHistoryMapper).findRecentByVehicleId("SIM-F01", 200);
    }

    private Vehicle vehicle() {
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId("SIM-F01");
        vehicle.setName("시뮬레이션 지게차 1호");
        vehicle.setSource(VehicleSource.SIMULATION);
        vehicle.setActive(true);
        return vehicle;
    }

    private VehicleStatusHistory history(Long id, String vehicleId, VehicleStatus status, Integer battery) {
        VehicleStatusHistory history = new VehicleStatusHistory();
        history.setId(id);
        history.setVehicleId(vehicleId);
        history.setStatus(status);
        history.setBattery(battery);
        history.setMessageAt(LocalDateTime.now());
        history.setReceivedAt(LocalDateTime.now());
        history.setCreatedAt(LocalDateTime.now());
        return history;
    }
}
