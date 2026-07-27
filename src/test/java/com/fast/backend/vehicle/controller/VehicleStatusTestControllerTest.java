package com.fast.backend.vehicle.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateRequest;
import com.fast.backend.vehicle.service.VehicleStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VehicleStatusTestController가 REST 요청(OffsetDateTime 등)을 내부 Command(LocalDateTime)로 올바르게
 * 변환해 VehicleStatusService에 위임하는지 검증한다. {@code vehicle.status-test-api.enabled}가 test
 * 프로필에서 false이므로(mqtt.test-api.enabled와 동일한 정책) @SpringBootTest로는 이 컨트롤러 Bean이
 * 뜨지 않는다 — 그래서 다른 vehicle 컨트롤러 테스트와 마찬가지로 직접 인스턴스를 생성해서 테스트한다.
 */
class VehicleStatusTestControllerTest {

    private VehicleStatusService vehicleStatusService;
    private VehicleStatusTestController controller;

    @BeforeEach
    void setUp() {
        vehicleStatusService = mock(VehicleStatusService.class);
        controller = new VehicleStatusTestController(vehicleStatusService);
    }

    @Test
    void updateStatus_passesOffsetDateTimeThroughUnchanged() {
        OffsetDateTime messageAt = OffsetDateTime.of(2026, 7, 21, 18, 0, 0, 0, ZoneOffset.ofHours(9));
        VehicleStatusUpdateRequest request = new VehicleStatusUpdateRequest(
                "ACTIVE", 82, 1.2, 3.4, 90.0, 0.4, messageAt);
        VehicleStatusResponse expected = new VehicleStatusResponse(
                VehicleStatus.ACTIVE, 82, 1.2, 3.4, 90.0, 0.4, null, null, null, null, null, null, null);
        when(vehicleStatusService.updateCurrentStatus(eq("SIM-F01"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(expected);

        ApiResponse<VehicleStatusResponse> response = controller.updateStatus("SIM-F01", request);

        assertThat(response.getData()).isEqualTo(expected);

        ArgumentCaptor<VehicleStatusUpdateCommand> captor = ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(vehicleStatusService).updateCurrentStatus(eq("SIM-F01"), captor.capture());
        VehicleStatusUpdateCommand command = captor.getValue();
        assertThat(command.status()).isEqualTo("ACTIVE");
        assertThat(command.battery()).isEqualTo(82);
        // prompt32.md 1장 6번 확정: 요청·커맨드 모두 OffsetDateTime이라 Controller가 변환하지 않는다.
        assertThat(command.messageAt()).isEqualTo(messageAt);
        // Isaac 확장 필드를 다루지 않는 호출자이므로 isaacExtras는 null이어야 한다(기존 값 보존).
        assertThat(command.isaacExtras()).isNull();
    }

    @Test
    void updateStatus_nullMessageAt_passesNullThrough() {
        VehicleStatusUpdateRequest request = new VehicleStatusUpdateRequest("IDLE", null, null, null, null, null, null);
        when(vehicleStatusService.updateCurrentStatus(eq("SIM-F01"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new VehicleStatusResponse(VehicleStatus.IDLE, null, null, null, null, null, null, null, null, null, null, null, null));

        controller.updateStatus("SIM-F01", request);

        ArgumentCaptor<VehicleStatusUpdateCommand> captor = ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(vehicleStatusService).updateCurrentStatus(eq("SIM-F01"), captor.capture());
        assertThat(captor.getValue().messageAt()).isNull();
    }
}
