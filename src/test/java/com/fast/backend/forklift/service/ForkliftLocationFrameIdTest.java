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
 * 최종 합의 좌표계 규격(prompt84 3항: 단위 m, frameId는 <b>{@code map}만</b> 허용, 생략도 위반)을
 * 검증한다.
 *
 * <p>이전 규격(prompt32.md 1장 5번)은 {@code odom}을 허용하고 생략 시 {@code map}으로 보정했다.
 * 최종 합의에서 둘 다 <b>규격 위반</b>이 됐다 — odom은 주행거리계 기준 상대 좌표라 전역 지도
 * 좌표로 해석하면 차량이 엉뚱한 곳에 그려지고, 생략을 map으로 채우면 발행 측 위반이 드러나지 않는다.
 * 위반 메시지는 저장도 중계도 하지 않고 폐기한다.
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
        service = new ForkliftLocationService(vehicleMapper,
                mock(com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper.class), broadcaster,
                new com.fast.backend.vehicle.location.InMemoryLatestVehicleLocationProvider());
        when(vehicleMapper.existsByVehicleId("REAL-F01")).thenReturn(true);
    }

    @Test
    void mapFrameId_isAcceptedAndPassedThrough() {
        service.handleLocation(message("map"));

        assertThat(captureBroadcastData().position().frameId()).isEqualTo("map");
    }

    /** odom은 더 이상 허용하지 않는다 — 저장은 물론 중계도 하지 않는다(prompt84 3항). */
    @Test
    void odomFrameId_isRejectedAndNeverBroadcast() {
        service.handleLocation(message("odom"));

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    /** 생략을 map으로 자동 보정하지 않는다 — 합의 payload에서 frameId는 필수다. */
    @Test
    void missingFrameId_isRejected() {
        service.handleLocation(message(null));

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void blankFrameId_isRejected() {
        service.handleLocation(message("   "));

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
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
