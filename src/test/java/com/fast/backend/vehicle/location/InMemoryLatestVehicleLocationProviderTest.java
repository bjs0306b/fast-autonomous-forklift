package com.fast.backend.vehicle.location;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 최신 위치 Provider 단위 테스트(prompt50.md 6·7·14장): vehicleId별 1건 유지, 덮어쓰기, stale 가드, 분리.
 */
class InMemoryLatestVehicleLocationProviderTest {

    private final InMemoryLatestVehicleLocationProvider provider = new InMemoryLatestVehicleLocationProvider();

    private static OffsetDateTime at(int second) {
        return OffsetDateTime.of(2026, 7, 27, 15, 0, second, 0, ZoneOffset.ofHours(9));
    }

    private static VehicleLocationSnapshot snap(String id, double x, OffsetDateTime messageAt) {
        return new VehicleLocationSnapshot(id, "REAL", x, 0.0, 90.0, 0.5, "map", messageAt, at(0));
    }

    @Test
    void update_overwritesWithNewer() {
        provider.update(snap("V1", 1.0, at(5)));
        provider.update(snap("V1", 2.0, at(10)));
        assertThat(provider.findLatest("V1")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(2.0);
    }

    @Test
    void update_olderMessageDoesNotOverwrite() {
        provider.update(snap("V2", 1.0, at(10)));
        provider.update(snap("V2", 9.0, at(5))); // 더 오래된 messageAt → 무시
        assertThat(provider.findLatest("V2")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(1.0);
    }

    @Test
    void update_nullMessageAt_overwrites() {
        provider.update(snap("V3", 1.0, null));
        provider.update(snap("V3", 2.0, null));
        assertThat(provider.findLatest("V3")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(2.0);
    }

    @Test
    void separatesVehicles() {
        provider.update(snap("REAL-F01", 1.0, at(5)));
        provider.update(snap("SIM-F01", 7.0, at(5)));
        assertThat(provider.findLatest("REAL-F01")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(1.0);
        assertThat(provider.findLatest("SIM-F01")).get().extracting(VehicleLocationSnapshot::x).isEqualTo(7.0);
        assertThat(provider.findAllLatest()).hasSize(2);
    }

    @Test
    void findLatest_absent_returnsEmpty() {
        assertThat(provider.findLatest("NONE")).isEmpty();
    }
}
