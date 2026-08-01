package com.fast.backend.station.websocket;

import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.common.websocket.RealtimeEvent;
import com.fast.backend.common.websocket.RealtimeEventType;
import com.fast.backend.station.dto.StationMeasurementResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 측정 결과를 공통 실시간 이벤트 envelope로 STOMP 브로드캐스트한다. */
@Component
public class StationMeasurementBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public StationMeasurementBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcast(StationMeasurementResponse response, LocalDateTime occurredAt) {
        RealtimeEvent<StationMeasurementResponse> event = RealtimeEvent.of(
                RealtimeEventType.STATION_MEASUREMENT_COMPLETED,
                // 측정 설비는 차량과 독립적이므로 공통 envelope의 vehicleId는 null이다.
                null,
                CommunicationTime.toOffset(occurredAt),
                response);
        try {
            messagingTemplate.convertAndSend(StationMeasurementTopics.ALL, event);
            log.debug("Station measurement broadcast sent: measurementId={}, sessionId={}",
                    response.measurementId(), response.sessionId());
        } catch (Exception e) {
            log.error("Failed to broadcast station measurement event: measurementId={}, error={}",
                    response.measurementId(), e.getMessage());
        }
    }
}
