package com.fast.backend.isaac.service;

import com.fast.backend.isaac.dto.IsaacVehicleEventMessage;
import com.fast.backend.vehicle.config.VehicleProperties;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.service.VehicleAutoRegistrar;
import com.fast.backend.vehicle.service.VehicleIdAliasResolver;
import com.fast.backend.vehicle.service.VehicleStatusService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IsaacVehicleEventServiceTest {

    private final VehicleAutoRegistrar autoRegistrar = mock(VehicleAutoRegistrar.class);
    private final VehicleStatusService statusService = mock(VehicleStatusService.class);
    private final IsaacVehicleEventService service = new IsaacVehicleEventService(
            new VehicleIdAliasResolver(new VehicleProperties(
                    true, false, 10L, Map.of("sim03", "SIM-F03"))),
            autoRegistrar, statusService);

    @Test
    void holdingEventUpdatesTheNormalizedVehicle() {
        when(autoRegistrar.ensureRegistered("SIM-F03", "isaac-event")).thenReturn(true);

        service.handleEvent("sim03", new IsaacVehicleEventMessage(
                "sim03", 1785946947471L, "HOLDING", null, null, "T-1"));

        ArgumentCaptor<VehicleStatusUpdateCommand> command =
                ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(statusService).updateCurrentStatus(
                org.mockito.ArgumentMatchers.eq("SIM-F03"), command.capture());
        assertThat(command.getValue().status()).isEqualTo("HOLDING");
    }

    @Test
    void unknownEventDoesNotOverwriteTheCurrentStatus() {
        when(autoRegistrar.ensureRegistered("SIM-F03", "isaac-event")).thenReturn(true);

        service.handleEvent("sim03", new IsaacVehicleEventMessage(
                "sim03", 1L, null, "SOMETHING_NEW", null, null));

        verify(statusService, never()).updateCurrentStatus(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
