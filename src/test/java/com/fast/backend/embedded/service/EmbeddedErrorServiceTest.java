package com.fast.backend.embedded.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.embedded.domain.EmbeddedErrorHistory;
import com.fast.backend.embedded.dto.EmbeddedErrorMessage;
import com.fast.backend.embedded.dto.EmbeddedErrorResponse;
import com.fast.backend.embedded.mapper.EmbeddedErrorHistoryMapper;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.EmbeddedErrorEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code forklift/{id}/error} 처리·조회를 검증한다(prompt29.md 9장·16장). CRITICAL을 받아도 자동으로
 * 비상정지를 재발행하지 않는지(20장) 명시적으로 확인한다.
 */
class EmbeddedErrorServiceTest {

    private static final OffsetDateTime TIMESTAMP = LocalDateTime.of(2026, 7, 22, 10, 30, 0).atOffset(java.time.ZoneOffset.ofHours(9));

    private VehicleMapper vehicleMapper;
    private EmbeddedErrorHistoryMapper errorHistoryMapper;
    private VehicleWebSocketBroadcaster broadcaster;
    private EmbeddedErrorService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        errorHistoryMapper = mock(EmbeddedErrorHistoryMapper.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        service = new EmbeddedErrorService(vehicleMapper, errorHistoryMapper, broadcaster);
        when(vehicleMapper.existsByVehicleId("REAL01")).thenReturn(true);
    }

    private EmbeddedErrorMessage message(String source, String severity) {
        return new EmbeddedErrorMessage("REAL01", "E001", source, severity, "메시지", TIMESTAMP);
    }

    @Test
    void handleError_registeredVehicle_insertsAndBroadcasts() {
        service.handleError(message("DRIVE", "WARNING"));

        ArgumentCaptor<EmbeddedErrorHistory> captor = ArgumentCaptor.forClass(EmbeddedErrorHistory.class);
        verify(errorHistoryMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getForkliftId()).isEqualTo("REAL01");
        assertThat(captor.getValue().getErrorCode()).isEqualTo("E001");
        verify(broadcaster, times(1)).broadcastEmbeddedError(eq("REAL01"), any(EmbeddedErrorEventData.class), eq(TIMESTAMP));
    }

    @Test
    void handleError_criticalSeverity_doesNotTriggerAnyEmergencyStopPublish() {
        // 20장 명시 조건 — CRITICAL을 받았다는 이유만으로 비상정지를 자동 재발행하지 않는다.
        // EmbeddedErrorService 생성자에는 EmbeddedForkliftCommandPublisher 의존성 자체가 없다 — 이것이
        // 곧 "자동 발행 로직이 없다"는 구조적 증거다. 여기서는 insert·broadcast만 일어남을 확인한다.
        service.handleError(message("SYSTEM", "CRITICAL"));

        verify(errorHistoryMapper, times(1)).insert(any());
        verify(broadcaster, times(1)).broadcastEmbeddedError(any(), any(), any());
    }

    @Test
    void handleError_unregisteredVehicle_skipsInsertAndBroadcast() {
        when(vehicleMapper.existsByVehicleId("REAL99")).thenReturn(false);

        service.handleError(new EmbeddedErrorMessage("REAL99", "E001", "DRIVE", "WARNING", "메시지", TIMESTAMP));

        verify(errorHistoryMapper, never()).insert(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void handleError_unknownErrorSource_skips() {
        service.handleError(message("NETWORK", "WARNING"));

        verify(errorHistoryMapper, never()).insert(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void handleError_unknownSeverity_skips() {
        service.handleError(message("DRIVE", "FATAL"));

        verify(errorHistoryMapper, never()).insert(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void handleError_blankErrorCode_skipsWithoutCallingVehicleMapper() {
        service.handleError(new EmbeddedErrorMessage("REAL01", " ", "DRIVE", "WARNING", "메시지", TIMESTAMP));

        verifyNoInteractions(vehicleMapper);
        verify(errorHistoryMapper, never()).insert(any());
    }

    @Test
    void handleError_nullTimestamp_skips() {
        service.handleError(new EmbeddedErrorMessage("REAL01", "E001", "DRIVE", "WARNING", "메시지", null));

        verify(errorHistoryMapper, never()).insert(any());
    }

    @Test
    void handleError_mapperThrowsRuntimeException_doesNotPropagate() {
        when(errorHistoryMapper.insert(any())).thenThrow(new RuntimeException("DB down"));

        assertThatCode(() -> service.handleError(message("DRIVE", "WARNING"))).doesNotThrowAnyException();
    }

    @Test
    void findRecentByForkliftId_limitTooLow_throwsLimitInvalid() {
        when(vehicleMapper.findByVehicleId("REAL01")).thenReturn(Optional.of(mock(Vehicle.class)));

        assertThatThrownBy(() -> service.findRecentByForkliftId("REAL01", 0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMMAND_LIMIT_INVALID);
    }

    @Test
    void findRecentByForkliftId_unregisteredVehicle_throwsVehicleNotFound() {
        when(vehicleMapper.findByVehicleId("REAL99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findRecentByForkliftId("REAL99", 50))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VEHICLE_NOT_FOUND);
    }

    @Test
    void findRecentByForkliftId_found_returnsMappedResponses() {
        when(vehicleMapper.findByVehicleId("REAL01")).thenReturn(Optional.of(mock(Vehicle.class)));
        EmbeddedErrorHistory history = new EmbeddedErrorHistory();
        history.setForkliftId("REAL01");
        history.setErrorCode("E001");
        history.setErrorSource(com.fast.backend.embedded.domain.EmbeddedErrorSource.DRIVE);
        history.setSeverity(com.fast.backend.embedded.domain.EmbeddedErrorSeverity.WARNING);
        when(errorHistoryMapper.findRecentByForkliftId(eq("REAL01"), anyInt())).thenReturn(List.of(history));

        List<EmbeddedErrorResponse> responses = service.findRecentByForkliftId("REAL01", 50);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).errorCode()).isEqualTo("E001");
        assertThat(responses.get(0).errorSource()).isEqualTo("DRIVE");
        assertThat(responses.get(0).severity()).isEqualTo("WARNING");
    }
}
