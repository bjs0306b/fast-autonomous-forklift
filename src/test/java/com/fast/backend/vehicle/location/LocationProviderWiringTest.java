package com.fast.backend.vehicle.location;

import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.isaac.dto.IsaacForkliftLocationMessage;
import com.fast.backend.isaac.service.IsaacForkliftLocationService;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 위치 서비스 → Provider 배선 검증(prompt50.md 6·14장). ROS2/Isaac 위치 수신 시 최신 위치가 갱신되고
 * source(REAL/SIM)·frameId가 올바른지 확인한다. DB·Broker 없이 mock으로 검증한다.
 */
class LocationProviderWiringTest {

    private static final OffsetDateTime MSG_AT =
            OffsetDateTime.of(2026, 7, 27, 15, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void ros2Location_updatesProviderWithRealSource() {
        VehicleMapper vehicleMapper = mock(VehicleMapper.class);
        when(vehicleMapper.existsByVehicleId("REAL-F01")).thenReturn(true);
        InMemoryLatestVehicleLocationProvider provider = new InMemoryLatestVehicleLocationProvider();
        ForkliftLocationService service = new ForkliftLocationService(
                vehicleMapper, mock(VehicleWebSocketBroadcaster.class), provider);

        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "REAL-F01", "MOVING",
                new ForkliftLocationMessage.Position(2.4, 5.1, "map"),
                90.0, null, 0.5, MSG_AT);
        service.handleLocation(message);

        VehicleLocationSnapshot snap = provider.findLatest("REAL-F01").orElseThrow();
        assertThat(snap.x()).isEqualTo(2.4);
        assertThat(snap.frameId()).isEqualTo("map");
        assertThat(snap.messageAt()).isEqualTo(MSG_AT);
    }

    @Test
    void isaacLocation_updatesProviderWithSimSource() {
        VehicleMapper vehicleMapper = mock(VehicleMapper.class);
        when(vehicleMapper.existsByVehicleId("SIM-F01")).thenReturn(true);
        InMemoryLatestVehicleLocationProvider provider = new InMemoryLatestVehicleLocationProvider();
        IsaacForkliftLocationService service = new IsaacForkliftLocationService(
                vehicleMapper, mock(VehicleWebSocketBroadcaster.class), provider);

        IsaacForkliftLocationMessage message = new IsaacForkliftLocationMessage(
                "SIM-F01", 1.2, 3.4, 180.0, 0.2, MSG_AT);
        service.handleLocation(message);

        VehicleLocationSnapshot snap = provider.findLatest("SIM-F01").orElseThrow();
        assertThat(snap.frameId()).isEqualTo("map");
        assertThat(snap.heading()).isEqualTo(180.0);
    }

    @Test
    void unregisteredVehicle_doesNotUpdateProvider() {
        VehicleMapper vehicleMapper = mock(VehicleMapper.class);
        when(vehicleMapper.existsByVehicleId("GHOST")).thenReturn(false);
        InMemoryLatestVehicleLocationProvider provider = new InMemoryLatestVehicleLocationProvider();
        ForkliftLocationService service = new ForkliftLocationService(
                vehicleMapper, mock(VehicleWebSocketBroadcaster.class), provider);

        service.handleLocation(new ForkliftLocationMessage(
                "GHOST", null, new ForkliftLocationMessage.Position(1.0, 2.0, "map"), 0.0, null, 0.0, MSG_AT));

        assertThat(provider.findLatest("GHOST")).isEmpty();
    }
}
