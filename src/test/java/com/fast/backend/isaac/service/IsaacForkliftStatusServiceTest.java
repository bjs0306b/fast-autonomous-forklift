package com.fast.backend.isaac.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.isaac.dto.IsaacForkliftStatusMessage;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.service.VehicleStatusService;
import com.fast.backend.vehicle.websocket.IsaacVehicleStatusEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Isaac Sim 상태 메시지(LWT 포함) → {@link VehicleStatusService#updateCurrentStatus} 위임(기존 차량
 * 관제 구조 재사용)과 {@link IsaacVehicleStatusEventData} 브로드캐스트, 값 검증을 검증한다
 * (prompt28.md 4장·5장·15장).
 */
class IsaacForkliftStatusServiceTest {

    private static final LocalDateTime TIMESTAMP = LocalDateTime.of(2026, 7, 22, 10, 30, 0, 123_000_000);

    private VehicleStatusService vehicleStatusService;
    private VehicleWebSocketBroadcaster broadcaster;
    private IsaacForkliftStatusService service;

    @BeforeEach
    void setUp() {
        vehicleStatusService = mock(VehicleStatusService.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        service = new IsaacForkliftStatusService(vehicleStatusService, broadcaster);
        when(vehicleStatusService.updateCurrentStatus(anyString(), any()))
                .thenReturn(new VehicleStatusResponse(VehicleStatus.ACTIVE, 87, null, null, null, null, null, null));
    }

    @Test
    void handleStatus_movingStatus_updatesCommonStatusAsActiveAndBroadcastsRawIsaacStatus() {
        IsaacForkliftStatusMessage message = fullMessage("SIM01", "MOVING");

        service.handleStatus(message);

        ArgumentCaptor<VehicleStatusUpdateCommand> commandCaptor =
                ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(vehicleStatusService).updateCurrentStatus(eq("SIM01"), commandCaptor.capture());
        // MOVING → ACTIVE로 매핑돼 기존 VehicleStatus.fromRaw()가 그대로 받을 수 있는 값이 전달된다.
        assertThat(commandCaptor.getValue().status()).isEqualTo("ACTIVE");
        assertThat(commandCaptor.getValue().battery()).isEqualTo(87);

        ArgumentCaptor<IsaacVehicleStatusEventData> eventCaptor =
                ArgumentCaptor.forClass(IsaacVehicleStatusEventData.class);
        verify(broadcaster).broadcastIsaacStatus(eq("SIM01"), eventCaptor.capture(), any());
        // WebSocket 이벤트에는 매핑 전 원본 Isaac 상태("MOVING")가 그대로 보존된다(정보 손실 방지).
        assertThat(eventCaptor.getValue().status()).isEqualTo("MOVING");
        assertThat(eventCaptor.getValue().forkHeight()).isEqualTo(0.120);
        assertThat(eventCaptor.getValue().hasCargo()).isTrue();
        assertThat(eventCaptor.getValue().cargoId()).isEqualTo("BOX-0042");
        assertThat(eventCaptor.getValue().footprint().length()).isEqualTo(0.28);
    }

    @Test
    void handleStatus_liftingAndLoading_mapToActive() {
        service.handleStatus(fullMessage("SIM01", "LIFTING"));
        service.handleStatus(fullMessage("SIM01", "LOADING"));

        ArgumentCaptor<VehicleStatusUpdateCommand> captor = ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(vehicleStatusService, org.mockito.Mockito.times(2)).updateCurrentStatus(eq("SIM01"), captor.capture());
        assertThat(captor.getAllValues()).extracting(VehicleStatusUpdateCommand::status)
                .containsExactly("ACTIVE", "ACTIVE");
    }

    @Test
    void handleStatus_estop_mapsToError() {
        service.handleStatus(fullMessage("SIM01", "ESTOP"));

        ArgumentCaptor<VehicleStatusUpdateCommand> captor = ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(vehicleStatusService).updateCurrentStatus(eq("SIM01"), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("ERROR");
    }

    @Test
    void handleStatus_idle_mapsToIdle() {
        service.handleStatus(fullMessage("SIM01", "IDLE"));

        ArgumentCaptor<VehicleStatusUpdateCommand> captor = ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(vehicleStatusService).updateCurrentStatus(eq("SIM01"), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("IDLE");
    }

    @Test
    void handleStatus_lwtOffline_minimalMessage_updatesStatusWithBackendReceivedTimestamp() {
        IsaacForkliftStatusMessage lwt = new IsaacForkliftStatusMessage(
                "SIM01", "OFFLINE", null, null, null, null, null, null);

        service.handleStatus(lwt);

        ArgumentCaptor<VehicleStatusUpdateCommand> captor = ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(vehicleStatusService).updateCurrentStatus(eq("SIM01"), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("OFFLINE");
        assertThat(captor.getValue().battery()).isNull();
        // timestamp가 null이므로 백엔드 수신 시각이 대신 채워져야 한다(5장 4번).
        assertThat(captor.getValue().messageAt()).isNotNull();

        ArgumentCaptor<IsaacVehicleStatusEventData> eventCaptor =
                ArgumentCaptor.forClass(IsaacVehicleStatusEventData.class);
        verify(broadcaster).broadcastIsaacStatus(eq("SIM01"), eventCaptor.capture(), any());
        assertThat(eventCaptor.getValue().receivedAt()).isNotNull();
    }

    @Test
    void handleStatus_batteryOutOfRange_rejectsMessage() {
        IsaacForkliftStatusMessage message = new IsaacForkliftStatusMessage(
                "SIM01", "IDLE", 150, 0.1, false, null,
                new IsaacForkliftStatusMessage.Footprint(0.28, 0.16), TIMESTAMP);

        service.handleStatus(message);

        verifyNoInteractions(vehicleStatusService);
        verify(broadcaster, never()).broadcastIsaacStatus(any(), any(), any());
    }

    @Test
    void handleStatus_negativeForkHeight_rejectsMessage() {
        IsaacForkliftStatusMessage message = new IsaacForkliftStatusMessage(
                "SIM01", "IDLE", 50, -0.1, false, null,
                new IsaacForkliftStatusMessage.Footprint(0.28, 0.16), TIMESTAMP);

        service.handleStatus(message);

        verifyNoInteractions(vehicleStatusService);
    }

    @Test
    void handleStatus_missingFootprint_rejectsMessage() {
        IsaacForkliftStatusMessage message = new IsaacForkliftStatusMessage(
                "SIM01", "IDLE", 50, 0.1, false, null, null, TIMESTAMP);

        service.handleStatus(message);

        verifyNoInteractions(vehicleStatusService);
    }

    @Test
    void handleStatus_nonPositiveFootprintWidth_rejectsMessage() {
        IsaacForkliftStatusMessage message = new IsaacForkliftStatusMessage(
                "SIM01", "IDLE", 50, 0.1, false, null,
                new IsaacForkliftStatusMessage.Footprint(0.28, 0.0), TIMESTAMP);

        service.handleStatus(message);

        verifyNoInteractions(vehicleStatusService);
    }

    @Test
    void handleStatus_missingHasCargo_rejectsMessage() {
        IsaacForkliftStatusMessage message = new IsaacForkliftStatusMessage(
                "SIM01", "IDLE", 50, 0.1, null, null,
                new IsaacForkliftStatusMessage.Footprint(0.28, 0.16), TIMESTAMP);

        service.handleStatus(message);

        verifyNoInteractions(vehicleStatusService);
    }

    @Test
    void handleStatus_hasCargoTrueWithoutCargoId_isAllowed() {
        // 합의 문서에 조건부 필수 여부가 없어 임의로 강제하지 않는다(4장 검증 규칙).
        IsaacForkliftStatusMessage message = new IsaacForkliftStatusMessage(
                "SIM01", "IDLE", 50, 0.1, true, null,
                new IsaacForkliftStatusMessage.Footprint(0.28, 0.16), TIMESTAMP);

        service.handleStatus(message);

        verify(vehicleStatusService).updateCurrentStatus(eq("SIM01"), any());
    }

    @Test
    void handleStatus_unregisteredVehicle_skipsBroadcast() {
        when(vehicleStatusService.updateCurrentStatus(eq("SIM99"), any()))
                .thenThrow(new BusinessException(ErrorCode.VEHICLE_NOT_FOUND, "not found"));

        service.handleStatus(fullMessage("SIM99", "IDLE"));

        verify(broadcaster, never()).broadcastIsaacStatus(any(), any(), any());
    }

    private IsaacForkliftStatusMessage fullMessage(String forkliftId, String status) {
        return new IsaacForkliftStatusMessage(
                forkliftId, status, 87, 0.120, true, "BOX-0042",
                new IsaacForkliftStatusMessage.Footprint(0.28, 0.16), TIMESTAMP);
    }
}
