package com.fast.backend.vehicle.service;

import com.fast.backend.vehicle.config.VehicleProperties;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VehicleServiceAliasTest {

    @Test
    void activeVehicleListHidesLegacyRawAliasRow() {
        VehicleMapper vehicleMapper = mock(VehicleMapper.class);
        VehicleCurrentStatusMapper statusMapper = mock(VehicleCurrentStatusMapper.class);
        Vehicle raw = vehicle("sim03");
        Vehicle canonical = vehicle("SIM-F03");
        when(vehicleMapper.findAllActive()).thenReturn(List.of(raw, canonical));
        when(statusMapper.findAllByVehicleIds(List.of("SIM-F03"))).thenReturn(List.of());
        VehicleService service = new VehicleService(
                vehicleMapper,
                statusMapper,
                new VehicleIdAliasResolver(new VehicleProperties(
                        true, false, 10L, Map.of("sim03", "SIM-F03"))));

        assertThat(service.findActiveVehicles())
                .extracting(response -> response.vehicleId())
                .containsExactly("SIM-F03");
    }

    private Vehicle vehicle(String vehicleId) {
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId);
        vehicle.setActive(true);
        return vehicle;
    }
}
