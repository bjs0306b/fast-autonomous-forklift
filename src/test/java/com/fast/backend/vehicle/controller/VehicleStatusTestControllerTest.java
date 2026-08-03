package com.fast.backend.vehicle.controller;

import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateRequest;
import com.fast.backend.vehicle.service.VehicleStatusService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleStatusTestControllerTest {
    @Test
    void forwardsOnlyTheStatusContract() {
        VehicleStatusService service = mock(VehicleStatusService.class);
        VehicleStatusTestController controller = new VehicleStatusTestController(service);
        OffsetDateTime messageAt = OffsetDateTime.of(2026, 7, 21, 18, 0, 0, 0, ZoneOffset.ofHours(9));
        VehicleStatusUpdateRequest request = new VehicleStatusUpdateRequest(
                "ACTIVE", 1.2, 3.4, 90.0, 0.4, messageAt);
        VehicleStatusResponse response = new VehicleStatusResponse(
                VehicleStatus.ACTIVE, null, null, null, null, null,
                null, null, messageAt, messageAt);
        when(service.updateCurrentStatus(eq("FORKLIFT-01"), any())).thenReturn(response);

        assertThat(controller.updateStatus("FORKLIFT-01", request).getData()).isEqualTo(response);
        ArgumentCaptor<VehicleStatusUpdateCommand> captor = ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(service).updateCurrentStatus(eq("FORKLIFT-01"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(new VehicleStatusUpdateCommand("ACTIVE", messageAt));
    }
}
