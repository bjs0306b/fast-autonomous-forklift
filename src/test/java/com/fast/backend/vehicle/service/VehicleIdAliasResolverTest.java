package com.fast.backend.vehicle.service;

import com.fast.backend.vehicle.config.VehicleProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleIdAliasResolverTest {

    private final VehicleIdAliasResolver resolver = new VehicleIdAliasResolver(
            new VehicleProperties(true, false, 10, Map.of(
                    "sim01", "SIM-F01",
                    "sim02", "SIM-F02")));

    @Test
    void resolvesIdsInBothDirections() {
        assertThat(resolver.resolve("sim02")).contains("SIM-F02");
        assertThat(resolver.resolveExternal("SIM-F02")).contains("sim02");
    }

    @Test
    void unaliasedAutoRegisteredVehiclePassesThrough() {
        assertThat(resolver.resolve("sim03")).contains("sim03");
        assertThat(resolver.resolveExternal("sim03")).contains("sim03");
    }
}
