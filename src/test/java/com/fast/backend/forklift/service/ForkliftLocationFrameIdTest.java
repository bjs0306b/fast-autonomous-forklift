package com.fast.backend.forklift.service;

import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleLocationEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 확정 좌표계 규격(prompt32.md 1장 5번: 단위 m, 허용 frameId는 {@code map}/{@code odom}, 기본값
 * {@code map})을 검증한다.
 *
 * <p>허용되지 않은 frameId는 메시지를 폐기한다 — 알 수 없는 좌표계의 위치를 관제 화면에 그리면 차량이
 * 엉뚱한 곳에 표시되므로, 다음 정상 메시지를 기다리는 편이 안전하다.
 */
class ForkliftLocationFrameIdTest {

    private static final OffsetDateTime MESSAGE_AT =
            OffsetDateTime.of(2026, 7, 23, 11, 20, 27, 0, ZoneOffset.ofHours(9));

    private VehicleMapper vehicleMapper;
    private VehicleWebSocketBroadcaster broadcaster;
    private ForkliftLocationService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        service = new ForkliftLocationService(vehicleMapper, broadcaster,
                new com.fast.backend.vehicle.location.InMemoryLatestVehicleLocationProvider());
        when(vehicleMapper.existsByVehicleId("REAL-F01")).thenReturn(true);
    }

    @Test
    void mapFrameId_isAcceptedAndPassedThrough() {
        service.handleLocation(message("map"));

        assertThat(captureBroadcastData().position().frameId()).isEqualTo("map");
    }

    @Test
    void odomFrameId_isAccepted() {
        service.handleLocation(message("odom"));

        assertThat(captureBroadcastData().position().frameId()).isEqualTo("odom");
    }

    @Test
    void missingFrameId_defaultsToMap() {
        service.handleLocation(message(null));

        assertThat(captureBroadcastData().position().frameId()).isEqualTo("map");
    }

    @Test
    void blankFrameId_defaultsToMap() {
        service.handleLocation(message("   "));

        assertThat(captureBroadcastData().position().frameId()).isEqualTo("map");
    }

    @Test
    void unsupportedFrameId_isRejectedAndNeverBroadcast() {
        service.handleLocation(message("base_link"));

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void unsupportedFrameId_doesNotStopTheNextValidMessage() {
        service.handleLocation(message("base_link"));
        service.handleLocation(message("map"));

        verify(broadcaster).broadcastLocation(eq("REAL-F01"), any(), any());
    }

    @Test
    void headingIsNormalisedIntoZeroToThreeSixtyDegrees() {
        service.handleLocation(new ForkliftLocationMessage(
                "REAL-F01", "MOVING",
                new ForkliftLocationMessage.Position(1.0, 2.0, "map"),
                -90.0, null, 0.5, MESSAGE_AT));

        assertThat(captureBroadcastData().heading()).isEqualTo(270.0);
    }

    private VehicleLocationEventData captureBroadcastData() {
        ArgumentCaptor<VehicleLocationEventData> captor = ArgumentCaptor.forClass(VehicleLocationEventData.class);
        verify(broadcaster).broadcastLocation(eq("REAL-F01"), captor.capture(), any());
        return captor.getValue();
    }

    private ForkliftLocationMessage message(String frameId) {
        return new ForkliftLocationMessage(
                "REAL-F01", "MOVING",
                new ForkliftLocationMessage.Position(1.0, 2.0, frameId),
                90.0, null, 0.5, MESSAGE_AT);
    }
}
