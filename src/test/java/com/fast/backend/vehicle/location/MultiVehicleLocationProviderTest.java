package com.fast.backend.vehicle.location;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다중 차량 최신 위치 분리 검증(prompt51.md 4장). vehicleId별 독립 저장·덮어쓰기·stale·source 분리와
 * 결정적(CountDownLatch/ExecutorService) 동시성까지 확인한다. DB insert 없음(메모리 Provider).
 */
class MultiVehicleLocationProviderTest {

    private final InMemoryLatestVehicleLocationProvider provider = new InMemoryLatestVehicleLocationProvider();

    private static OffsetDateTime at(int sec) {
        return OffsetDateTime.of(2026, 7, 27, 15, 0, sec, 0, ZoneOffset.ofHours(9));
    }

    private static VehicleLocationSnapshot snap(
            String id, String source, double x, double y, double heading, OffsetDateTime messageAt) {
        return new VehicleLocationSnapshot(id, source, x, y, heading, 0.5, "map", messageAt, at(0));
    }

    @Test
    void eachVehicleHasIndependentLatestLocation() {
        provider.update(snap("REAL-F01", "REAL", 1.0, 2.0, 90.0, at(5)));
        provider.update(snap("REAL-F02", "REAL", 3.0, 4.0, 180.0, at(5)));
        provider.update(snap("SIM-F01", "SIM", 10.0, 20.0, 270.0, at(5)));

        assertThat(provider.findLatest("REAL-F01")).get()
                .satisfies(s -> {
                    assertThat(s.x()).isEqualTo(1.0);
                    assertThat(s.y()).isEqualTo(2.0);
                    assertThat(s.heading()).isEqualTo(90.0);
                });
        assertThat(provider.findLatest("REAL-F02")).get()
                .satisfies(s -> {
                    assertThat(s.x()).isEqualTo(3.0);
                });
        assertThat(provider.findLatest("SIM-F01")).get()
                .satisfies(s -> {
                    assertThat(s.x()).isEqualTo(10.0);
                    assertThat(s.heading()).isEqualTo(270.0);
                });
    }

    @Test
    void updatingOneVehicleDoesNotChangeOthers() {
        provider.update(snap("REAL-F01", "REAL", 1.0, 2.0, 90.0, at(5)));
        provider.update(snap("REAL-F02", "REAL", 3.0, 4.0, 180.0, at(5)));
        provider.update(snap("SIM-F01", "SIM", 10.0, 20.0, 270.0, at(5)));

        provider.update(snap("REAL-F01", "REAL", 99.0, 99.0, 0.0, at(10))); // F01만 갱신

        assertThat(provider.findLatest("REAL-F01")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(99.0);
        assertThat(provider.findLatest("REAL-F02")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(3.0);
        assertThat(provider.findLatest("SIM-F01")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(10.0);
    }

    @Test
    void onlyOneLatestPerVehicle_noFrameAccumulation() {
        provider.update(snap("REAL-F01", "REAL", 1.0, 1.0, 0.0, at(1)));
        provider.update(snap("REAL-F01", "REAL", 2.0, 2.0, 0.0, at(2)));
        provider.update(snap("REAL-F01", "REAL", 3.0, 3.0, 0.0, at(3)));

        List<VehicleLocationSnapshot> all = provider.findAllLatest();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).x()).isEqualTo(3.0);
    }

    @Test
    void staleMessageDoesNotOverwriteAcrossVehicles() {
        provider.update(snap("REAL-F01", "REAL", 5.0, 5.0, 0.0, at(10)));
        provider.update(snap("REAL-F02", "REAL", 3.0, 4.0, 0.0, at(10)));

        provider.update(snap("REAL-F01", "REAL", 1.0, 1.0, 0.0, at(5))); // 오래됨 → 무시

        assertThat(provider.findLatest("REAL-F01")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(5.0);
        assertThat(provider.findLatest("REAL-F02")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(3.0);
    }

    @Test
    void concurrentUpdatesToDistinctVehiclesAreIsolated() throws Exception {
        int vehicles = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(vehicles);
        try {
            for (int i = 0; i < vehicles; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        provider.update(snap("V-" + idx, idx % 2 == 0 ? "REAL" : "SIM",
                                idx, idx * 2, 0.0, at(5)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(provider.findAllLatest()).hasSize(vehicles);
        for (int i = 0; i < vehicles; i++) {
            assertThat(provider.findLatest("V-" + i)).get().extracting(VehicleLocationSnapshot::x).isEqualTo((double) i);
        }
    }
}
