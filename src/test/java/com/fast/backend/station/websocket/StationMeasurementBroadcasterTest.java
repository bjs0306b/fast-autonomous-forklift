package com.fast.backend.station.websocket;

import com.fast.backend.common.websocket.RealtimeEvent;
import com.fast.backend.common.websocket.RealtimeEventType;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.dto.StationMeasurementResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 스테이션 이벤트가 공통 envelope로 전환됐는지(prompt32.md 1장 13번), 그리고 차량이 배정되지 않는
 * 도메인이므로 {@code vehicleId}가 null로 나가는지 검증한다.
 */
class StationMeasurementBroadcasterTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 7, 22, 13, 5, 1);

    @Test
    void broadcast_wrapsResponseInTheSharedRealtimeEnvelope() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        StationMeasurementBroadcaster broadcaster = new StationMeasurementBroadcaster(template);

        broadcaster.broadcast(response("m-1", "station-1"), OCCURRED_AT);

        ArgumentCaptor<RealtimeEvent> captor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(template).convertAndSend(eq("/topic/stations/measurements"), captor.capture());
        RealtimeEvent<?> event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(RealtimeEventType.STATION_MEASUREMENT_COMPLETED);
        assertThat(event.data()).isInstanceOf(StationMeasurementResponse.class);
    }

    @Test
    void broadcast_vehicleIdIsNullBecauseStationsAreNotBoundToAVehicle() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        StationMeasurementBroadcaster broadcaster = new StationMeasurementBroadcaster(template);

        broadcaster.broadcast(response("m-2", "station-1"), OCCURRED_AT);

        ArgumentCaptor<RealtimeEvent> captor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(template).convertAndSend(eq("/topic/stations/measurements"), captor.capture());
        assertThat(captor.getValue().vehicleId()).isNull();
        // 스테이션 식별자는 최상위가 아니라 data 안에 남아 있어야 한다.
        assertThat(((StationMeasurementResponse) captor.getValue().data()).stationId()).isEqualTo("station-1");
    }

    @Test
    void broadcast_occurredAtCarriesTheSeoulOffset() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        StationMeasurementBroadcaster broadcaster = new StationMeasurementBroadcaster(template);

        broadcaster.broadcast(response("m-3", "station-1"), OCCURRED_AT);

        ArgumentCaptor<RealtimeEvent> captor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(template).convertAndSend(eq("/topic/stations/measurements"), captor.capture());
        assertThat(captor.getValue().occurredAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    void broadcast_sendsToBothAllAndStationSpecificDestinations() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        StationMeasurementBroadcaster broadcaster = new StationMeasurementBroadcaster(template);

        broadcaster.broadcast(response("m-4", "station-7"), OCCURRED_AT);

        verify(template).convertAndSend(eq("/topic/stations/measurements"), any(RealtimeEvent.class));
        verify(template).convertAndSend(eq("/topic/stations/station-7/measurements"), any(RealtimeEvent.class));
    }

    @Test
    void broadcast_messagingTemplateThrows_exceptionDoesNotPropagate() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new RuntimeException("boom")).when(template).convertAndSend(anyString(), any(Object.class));
        StationMeasurementBroadcaster broadcaster = new StationMeasurementBroadcaster(template);

        assertThatCode(() -> broadcaster.broadcast(response("m-5", "station-1"), OCCURRED_AT))
                .doesNotThrowAnyException();
    }

    private StationMeasurementResponse response(String measurementId, String stationId) {
        return new StationMeasurementResponse(
                measurementId, stationId, "1.0",
                OffsetDateTime.of(2026, 7, 22, 13, 5, 1, 0, ZoneOffset.ofHours(9)),
                StationMeasurementStatus.OK,
                null, null, null, null,
                LocalDateTime.of(2026, 7, 22, 13, 5, 2));
    }
}
