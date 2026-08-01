package com.fast.backend.vehicle.location;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class MultiVehicleLocationProviderTest {
    @Test
    void concurrentVehicleUpdatesRemainIsolated() throws Exception {
        InMemoryLatestVehicleLocationProvider provider = new InMemoryLatestVehicleLocationProvider();
        int vehicleCount = 16;
        CountDownLatch done = new CountDownLatch(vehicleCount);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < vehicleCount; i++) {
                int index = i;
                pool.submit(() -> {
                    provider.update(new VehicleLocationSnapshot(
                            "V-" + index, (double) index, index * 2.0, 0.0, 0.5, "map",
                            OffsetDateTime.of(2026, 7, 27, 15, 0, 5, 0, ZoneOffset.ofHours(9)),
                            OffsetDateTime.now(ZoneOffset.ofHours(9))));
                    done.countDown();
                });
            }
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(provider.findAllLatest()).hasSize(vehicleCount);
    }
}
