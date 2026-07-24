package com.fast.backend.forklift.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.forklift.dto.ForkliftStatusMessage;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.service.VehicleStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MQTT 상태 메시지 → {@link VehicleStatusUpdateCommand} 변환과, {@code VehicleStatusService}가
 * 던지는 예외를 MQTT 경로에서 안전하게 흡수하는지 검증한다(prompt20.md 11장·14장). 실제 DB/WebSocket은
 * {@code VehicleStatusService}(및 그 하위 Mapper/Broadcaster)가 이미 별도로 검증하므로
 * (VehicleStatusServiceTest, VehicleCurrentStatusMapperTest, VehicleWebSocketBroadcasterTest),
 * 이 테스트는 VehicleStatusService를 Mock으로 두고 "MQTT 어댑터 계층의 책임"만 확인한다.
 */
class ForkliftStatusServiceTest {

    private VehicleStatusService vehicleStatusService;
    private ForkliftStatusService forkliftStatusService;

    @BeforeEach
    void setUp() {
        vehicleStatusService = mock(VehicleStatusService.class);
        forkliftStatusService = new ForkliftStatusService(vehicleStatusService);
    }

    @Test
    void handleStatus_validMessage_delegatesToVehicleStatusServiceWithConvertedCommand() {
        OffsetDateTime timestamp = LocalDateTime.of(2026, 7, 21, 18, 0, 0).atOffset(java.time.ZoneOffset.ofHours(9));
        ForkliftStatusMessage message = new ForkliftStatusMessage("SIM-F01", "ACTIVE", 82, timestamp);

        forkliftStatusService.handleStatus(message);

        ArgumentCaptor<VehicleStatusUpdateCommand> captor = ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(vehicleStatusService).updateCurrentStatus(eq("SIM-F01"), captor.capture());
        VehicleStatusUpdateCommand command = captor.getValue();
        assertThat(command.status()).isEqualTo("ACTIVE");
        assertThat(command.battery()).isEqualTo(82);
        assertThat(command.messageAt()).isEqualTo(timestamp);
        // ForkliftStatusMessage에는 위치/속도/방향 필드가 없다 — location은 별도 토픽·별도 경로로 처리된다
        // (ForkliftLocationService, VehicleLocationEventData Javadoc 참고).
        assertThat(command.positionX()).isNull();
        assertThat(command.positionY()).isNull();
        assertThat(command.heading()).isNull();
        assertThat(command.speed()).isNull();
    }

    @Test
    void handleStatus_vehicleNotFound_isSwallowedAndDoesNotPropagate() {
        when(vehicleStatusService.updateCurrentStatus(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.VEHICLE_NOT_FOUND, "등록되지 않은 차량입니다: NO-SUCH"));
        ForkliftStatusMessage message = new ForkliftStatusMessage("NO-SUCH", "ACTIVE", 50, java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)));

        assertThatCode(() -> forkliftStatusService.handleStatus(message)).doesNotThrowAnyException();
    }

    @Test
    void handleStatus_batteryOutOfRange_isSwallowedAndDoesNotPropagate() {
        when(vehicleStatusService.updateCurrentStatus(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.VEHICLE_BATTERY_OUT_OF_RANGE,
                        "battery는 0~100 범위여야 합니다: 150"));
        ForkliftStatusMessage message = new ForkliftStatusMessage("SIM-F01", "ACTIVE", 150, java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)));

        assertThatCode(() -> forkliftStatusService.handleStatus(message)).doesNotThrowAnyException();
    }

    @Test
    void handleStatus_unexpectedRuntimeException_isSwallowedAndDoesNotPropagate() {
        when(vehicleStatusService.updateCurrentStatus(any(), any()))
                .thenThrow(new RuntimeException("DB connection lost"));
        ForkliftStatusMessage message = new ForkliftStatusMessage("SIM-F01", "ACTIVE", 50, java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)));

        assertThatCode(() -> forkliftStatusService.handleStatus(message)).doesNotThrowAnyException();
    }
}
