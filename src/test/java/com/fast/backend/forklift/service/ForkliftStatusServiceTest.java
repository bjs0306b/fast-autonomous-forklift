package com.fast.backend.forklift.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.forklift.dto.ForkliftStatusMessage;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.service.VehicleStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForkliftStatusServiceTest {
    private VehicleStatusService vehicleStatusService;
    private ForkliftStatusService service;

    @BeforeEach
    void setUp() {
        vehicleStatusService = mock(VehicleStatusService.class);
        service = new ForkliftStatusService(vehicleStatusService);
    }

    @Test
    void delegatesNormalizedStatusContract() {
        OffsetDateTime timestamp = OffsetDateTime.of(2026, 7, 21, 18, 0, 0, 0, ZoneOffset.ofHours(9));
        service.handleStatus(new ForkliftStatusMessage("FORKLIFT-01", "ACTIVE", timestamp));

        ArgumentCaptor<VehicleStatusUpdateCommand> captor = ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(vehicleStatusService).updateCurrentStatus(eq("FORKLIFT-01"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(new VehicleStatusUpdateCommand("ACTIVE", timestamp));
    }

    @Test
    void isolatesBusinessAndRuntimeFailures() {
        when(vehicleStatusService.updateCurrentStatus(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.VEHICLE_NOT_FOUND));
        ForkliftStatusMessage message = new ForkliftStatusMessage(
                "UNKNOWN", "ACTIVE", OffsetDateTime.now(ZoneOffset.ofHours(9)));
        assertThatCode(() -> service.handleStatus(message)).doesNotThrowAnyException();
    }
}
