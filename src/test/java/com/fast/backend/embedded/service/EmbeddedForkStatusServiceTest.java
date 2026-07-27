package com.fast.backend.embedded.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.embedded.domain.VehicleForkCurrentStatus;
import com.fast.backend.embedded.dto.EmbeddedForkStatusMessage;
import com.fast.backend.embedded.dto.EmbeddedForkStatusResponse;
import com.fast.backend.embedded.mapper.VehicleForkCurrentStatusMapper;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.websocket.EmbeddedForkStatusEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code forklift/{id}/fork-status} 처리와 {@code GET /fork-status} 조회를 검증한다(prompt29.md 8장·
 * 16장·21장). limitBottom=true인데 forkState != BOTTOM인 조합은 거부하지 않고 경고만 남기는지도 확인한다.
 */
class EmbeddedForkStatusServiceTest {

    private static final OffsetDateTime TIMESTAMP = LocalDateTime.of(2026, 7, 22, 10, 30, 0).atOffset(java.time.ZoneOffset.ofHours(9));

    private VehicleMapper vehicleMapper;
    private VehicleForkCurrentStatusMapper forkStatusMapper;
    private VehicleWebSocketBroadcaster broadcaster;
    private EmbeddedForkStatusService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        forkStatusMapper = mock(VehicleForkCurrentStatusMapper.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        service = new EmbeddedForkStatusService(vehicleMapper, forkStatusMapper, broadcaster);
        when(vehicleMapper.existsByVehicleId("REAL01")).thenReturn(true);
    }

    private EmbeddedForkStatusMessage message(String forkState, Boolean limitBottom) {
        return new EmbeddedForkStatusMessage("REAL01", forkState, limitBottom, null, TIMESTAMP);
    }

    @Test
    void handleForkStatus_registeredVehicle_upsertsAndBroadcasts() {
        service.handleForkStatus(message("STOPPED", false));

        ArgumentCaptor<VehicleForkCurrentStatus> captor = ArgumentCaptor.forClass(VehicleForkCurrentStatus.class);
        verify(forkStatusMapper, times(1)).upsert(captor.capture());
        assertThat(captor.getValue().getForkliftId()).isEqualTo("REAL01");
        verify(broadcaster, times(1)).broadcastForkStatus(eq("REAL01"), any(EmbeddedForkStatusEventData.class), eq(TIMESTAMP));
    }

    @Test
    void handleForkStatus_unregisteredVehicle_skipsUpsertAndBroadcast() {
        when(vehicleMapper.existsByVehicleId("REAL99")).thenReturn(false);

        service.handleForkStatus(new EmbeddedForkStatusMessage("REAL99", "STOPPED", false, null, TIMESTAMP));

        verify(forkStatusMapper, never()).upsert(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void handleForkStatus_unknownForkState_skips() {
        service.handleForkStatus(message("LIFTING", false));

        verify(forkStatusMapper, never()).upsert(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void handleForkStatus_limitBottomTrueButForkStateNotBottom_stillAccepted() {
        // 21장 검증 규칙 — 센서 순서가 미확정이라 이 조합을 거부하지 않고 그대로 저장·브로드캐스트한다.
        service.handleForkStatus(message("STOPPED", true));

        verify(forkStatusMapper, times(1)).upsert(any());
        verify(broadcaster, times(1)).broadcastForkStatus(eq("REAL01"), any(), eq(TIMESTAMP));
    }

    @Test
    void handleForkStatus_limitBottomTrueAndForkStateBottom_consistentAndAccepted() {
        service.handleForkStatus(message("BOTTOM", true));

        verify(forkStatusMapper, times(1)).upsert(any());
    }

    @Test
    void handleForkStatus_nullLimitBottom_skips() {
        service.handleForkStatus(message("STOPPED", null));

        verify(forkStatusMapper, never()).upsert(any());
    }

    @Test
    void handleForkStatus_nullTimestamp_skips() {
        service.handleForkStatus(new EmbeddedForkStatusMessage("REAL01", "STOPPED", false, null, null));

        verify(forkStatusMapper, never()).upsert(any());
    }

    @Test
    void handleForkStatus_blankForkliftId_skipsWithoutCallingVehicleMapper() {
        service.handleForkStatus(new EmbeddedForkStatusMessage(" ", "STOPPED", false, null, TIMESTAMP));

        verifyNoInteractions(vehicleMapper);
        verify(forkStatusMapper, never()).upsert(any());
    }

    @Test
    void handleForkStatus_mapperThrowsRuntimeException_doesNotPropagate() {
        org.mockito.Mockito.doThrow(new RuntimeException("DB down")).when(forkStatusMapper).upsert(any());

        assertThatCode(() -> service.handleForkStatus(message("STOPPED", false))).doesNotThrowAnyException();
    }

    @Test
    void getCurrentForkStatus_unregisteredVehicle_throwsVehicleNotFound() {
        when(vehicleMapper.findByVehicleId("REAL99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentForkStatus("REAL99"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VEHICLE_NOT_FOUND);
    }

    @Test
    void getCurrentForkStatus_noStatusRow_throwsForkStatusNotFound() {
        when(vehicleMapper.findByVehicleId("REAL01")).thenReturn(Optional.of(mock(Vehicle.class)));
        when(forkStatusMapper.findByForkliftId("REAL01")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentForkStatus("REAL01"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDED_FORK_STATUS_NOT_FOUND);
    }

    @Test
    void getCurrentForkStatus_found_returnsResponse() {
        when(vehicleMapper.findByVehicleId("REAL01")).thenReturn(Optional.of(mock(Vehicle.class)));
        VehicleForkCurrentStatus status = new VehicleForkCurrentStatus();
        status.setForkliftId("REAL01");
        status.setForkState(com.fast.backend.embedded.domain.EmbeddedForkState.STOPPED);
        status.setLimitBottom(false);
        when(forkStatusMapper.findByForkliftId("REAL01")).thenReturn(Optional.of(status));

        EmbeddedForkStatusResponse response = service.getCurrentForkStatus("REAL01");

        assertThat(response.forkliftId()).isEqualTo("REAL01");
        assertThat(response.forkState()).isEqualTo("STOPPED");
        assertThat(response.limitBottom()).isFalse();
    }
}
