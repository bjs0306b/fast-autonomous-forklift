package com.fast.backend.vehicle.location;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryLatestVehicleLocationProviderTest {
    private final InMemoryLatestVehicleLocationProvider provider = new InMemoryLatestVehicleLocationProvider();

    private static OffsetDateTime at(int second) {
        return OffsetDateTime.of(2026, 7, 27, 15, 0, second, 0, ZoneOffset.ofHours(9));
    }

    private static VehicleLocationSnapshot snap(String id, double x, OffsetDateTime messageAt) {
        return new VehicleLocationSnapshot(id, x, 0.0, 90.0, 0.5, "map", messageAt, at(0));
    }

    @Test
    void keepsNewestLocationPerVehicle() {
        provider.update(snap("V1", 2.0, at(10)));
        provider.update(snap("V1", 9.0, at(5)));
        provider.update(snap("V2", 3.0, at(5)));
        assertThat(provider.findLatest("V1")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(2.0);
        assertThat(provider.findLatest("V2")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(3.0);
        assertThat(provider.findAllLatest()).hasSize(2);
    }
}
