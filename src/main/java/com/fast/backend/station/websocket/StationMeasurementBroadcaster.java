package com.fast.backend.station.websocket;

import com.fast.backend.station.dto.StationMeasurementResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 측정 스테이션 측정 결과를 STOMP로 브로드캐스트하는 전용 통로(prompt16.md 9단계). 전체 destination과
 * 스테이션별 destination 두 곳에 함께 보낸다.
 *
 * <p>전송 실패는 여기서 잡아 로그만 남기고 위로 던지지 않는다 — 이미 커밋된 DB 저장이 WebSocket 전송
 * 실패 때문에 실패한 것처럼 보이면 안 된다(기존 {@code AiCargoAnalysisBroadcaster}/
 * {@code VehicleWebSocketBroadcaster}와 동일한 방어 패턴).
 */
@Component
public class StationMeasurementBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public StationMeasurementBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcast(StationMeasurementResponse response, LocalDateTime occurredAt) {
        StationMeasurementEvent event = StationMeasurementEvent.completed(response, occurredAt);
        try {
            messagingTemplate.convertAndSend(StationMeasurementTopics.ALL, event);
            if (response.stationId() != null && !response.stationId().isBlank()) {
                messagingTemplate.convertAndSend(StationMeasurementTopics.byStationId(response.stationId()), event);
            }
            log.debug("Station measurement broadcast sent: measurementId={}, stationId={}",
                    response.measurementId(), response.stationId());
        } catch (Exception e) {
            log.error("Failed to broadcast station measurement event: measurementId={}, error={}",
                    response.measurementId(), e.getMessage());
        }
    }
}
