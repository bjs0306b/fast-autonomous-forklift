package com.fast.backend.isaac.service;

import com.fast.backend.isaac.dto.IsaacForkliftPathMessage;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import com.fast.backend.vehicle.websocket.VehiclePathEventData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isaac Sim 경로 메시지 → {@link VehiclePathEventData} 변환·브로드캐스트, 값 검증을 검증한다
 * (prompt28.md 6장·15장). DB에는 저장하지 않는다.
 */
class IsaacForkliftPathServiceTest {

    private static final LocalDateTime TIMESTAMP = LocalDateTime.of(2026, 7, 22, 10, 30, 0, 123_000_000);

    private VehicleMapper vehicleMapper;
    private VehicleWebSocketBroadcaster broadcaster;
    private IsaacForkliftPathService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        service = new IsaacForkliftPathService(vehicleMapper, broadcaster);
        when(vehicleMapper.existsByVehicleId("SIM01")).thenReturn(true);
    }

    @Test
    void handlePath_registeredVehicle_preservesWaypointOrderAndGoal() {
        IsaacForkliftPathMessage message = new IsaacForkliftPathMessage(
                "SIM01",
                List.of(
                        new IsaacForkliftPathMessage.Waypoint(1.20, 0.87),
                        new IsaacForkliftPathMessage.Waypoint(2.40, 0.87),
                        new IsaacForkliftPathMessage.Waypoint(2.40, 3.10)),
                new IsaacForkliftPathMessage.Goal(2.40, 3.10, 0.0),
                TIMESTAMP);

        service.handlePath(message);

        ArgumentCaptor<VehiclePathEventData> captor = ArgumentCaptor.forClass(VehiclePathEventData.class);
        verify(broadcaster).broadcastPath(eq("SIM01"), captor.capture(), eq(TIMESTAMP));
        VehiclePathEventData data = captor.getValue();
        assertThat(data.waypoints()).extracting(VehiclePathEventData.Waypoint::x)
                .containsExactly(1.20, 2.40, 2.40);
        assertThat(data.goal().x()).isEqualTo(2.40);
        assertThat(data.goal().direction()).isEqualTo(0.0);
        assertThat(data.receivedAt()).isNotNull();
    }

    @Test
    void handlePath_emptyWaypoints_isAllowedAndBroadcast() {
        IsaacForkliftPathMessage message = new IsaacForkliftPathMessage(
                "SIM01", List.of(), new IsaacForkliftPathMessage.Goal(1.0, 1.0, 0.0), TIMESTAMP);

        service.handlePath(message);

        verify(broadcaster).broadcastPath(eq("SIM01"), any(), any());
    }

    @Test
    void handlePath_nullWaypoints_rejectsMessage() {
        IsaacForkliftPathMessage message = new IsaacForkliftPathMessage(
                "SIM01", null, new IsaacForkliftPathMessage.Goal(1.0, 1.0, 0.0), TIMESTAMP);

        service.handlePath(message);

        verify(broadcaster, never()).broadcastPath(any(), any(), any());
    }

    @Test
    void handlePath_nanWaypointCoordinate_rejectsMessage() {
        IsaacForkliftPathMessage message = new IsaacForkliftPathMessage(
                "SIM01", List.of(new IsaacForkliftPathMessage.Waypoint(Double.NaN, 1.0)),
                new IsaacForkliftPathMessage.Goal(1.0, 1.0, 0.0), TIMESTAMP);

        service.handlePath(message);

        verify(broadcaster, never()).broadcastPath(any(), any(), any());
    }

    @Test
    void handlePath_nullGoal_rejectsMessage() {
        IsaacForkliftPathMessage message = new IsaacForkliftPathMessage("SIM01", List.of(), null, TIMESTAMP);

        service.handlePath(message);

        verify(broadcaster, never()).broadcastPath(any(), any(), any());
    }

    @Test
    void handlePath_infiniteGoalDirection_rejectsMessage() {
        IsaacForkliftPathMessage message = new IsaacForkliftPathMessage(
                "SIM01", List.of(), new IsaacForkliftPathMessage.Goal(1.0, 1.0, Double.POSITIVE_INFINITY), TIMESTAMP);

        service.handlePath(message);

        verify(broadcaster, never()).broadcastPath(any(), any(), any());
    }

    @Test
    void handlePath_unregisteredVehicle_skipsBroadcast() {
        when(vehicleMapper.existsByVehicleId("SIM99")).thenReturn(false);
        IsaacForkliftPathMessage message = new IsaacForkliftPathMessage(
                "SIM99", List.of(), new IsaacForkliftPathMessage.Goal(1.0, 1.0, 0.0), TIMESTAMP);

        service.handlePath(message);

        verify(broadcaster, never()).broadcastPath(any(), any(), any());
    }
}
