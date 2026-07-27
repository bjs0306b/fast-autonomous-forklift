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

/**
 * 측정 스테이션 측정 결과를 STOMP로 브로드캐스트하는 전용 통로(prompt16.md 9단계). 전체 destination과
 * 스테이션별 destination 두 곳에 함께 보낸다.
 *
 * <p><b>공통 envelope로 전환됐다(prompt32.md 1장 13번 확정)</b>. 이전에는 스테이션 전용 봉투
 * ({@code StationMeasurementEvent}, 최상위 식별자가 {@code stationId})를 썼다. 이제
 * {@link RealtimeEvent}를 공유하며, 최상위 식별자 필드는 확정 규격대로 {@code vehicleId}로 통일된다.
 * destination({@code /topic/stations/...})은 그대로 유지한다.
 *
 * <p><b>{@code vehicleId} 정책</b>: 측정 스테이션은 차량과 독립적으로 동작하며 측정 결과 자체에 차량이
 * 배정되지 않는다 — 따라서 {@code vehicleId}는 <b>항상 {@code null}</b>이다(1장 13번이 "스테이션
 * 이벤트에서 차량이 배정되지 않았으면 null 허용"으로 명시). 스테이션 도메인 식별자
 * ({@code stationId}, {@code measurementId})는 {@code data} 안에 그대로 유지된다.
 *
 * <p>스테이션 도메인의 저장 방식(UTC + offset minutes 분리 저장)은 이번 변경에서 <b>건드리지 않았다</b>
 * ("관련 없는 스테이션 코드를 깨뜨리지 말 것"). 이 클래스가 받는 {@code occurredAt}은 내부 처리 시각
 * ({@link LocalDateTime})이라 envelope에 담기 전에 {@code +09:00}으로 변환한다.
 *
 * <p>전송 실패는 여기서 잡아 로그만 남기고 위로 던지지 않는다 — 이미 커밋된 DB 저장이 WebSocket 전송
 * 실패 때문에 실패한 것처럼 보이면 안 된다.
 */
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
                null,
                CommunicationTime.toOffset(occurredAt),
                response);
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
