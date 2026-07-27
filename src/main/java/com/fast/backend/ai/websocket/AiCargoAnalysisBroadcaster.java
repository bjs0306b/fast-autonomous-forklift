package com.fast.backend.ai.websocket;

import com.fast.backend.ai.dto.AiCargoAnalysisResponse;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.common.websocket.RealtimeEvent;
import com.fast.backend.common.websocket.RealtimeEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * AI 화물 분석 결과를 STOMP로 브로드캐스트하는 전용 통로(prompt26.md 14장).
 *
 * <p><b>공통 envelope로 전환됐다(prompt32.md 1장 13번 확정)</b>. 이전에는 이 Broadcaster만 봉투 없이
 * {@code AiCargoAnalysisResponse}를 그대로 전송해서, 프론트가 차량 이벤트와 AI 이벤트를 서로 다른
 * 규격으로 구독해야 했다(구 communication-protocol.md §5.2의 `미확정` 항목). 이제
 * {@link RealtimeEvent}로 감싸 {@code eventType}/{@code vehicleId}/{@code occurredAt}/{@code data}
 * 구조를 다른 도메인과 공유한다. destination({@code /topic/ai/cargo-analysis})은 그대로 유지한다.
 *
 * <p><b>{@code vehicleId} 정책</b>: 분석 결과에 차량이 연결돼 있으면 그 값을 쓰고, 연결되지 않은 분석은
 * <b>{@code null}을 허용</b>한다(1장 13번). AI 도메인 고유 식별자({@code analysisId}, {@code cargoId})는
 * 최상위로 올리지 않고 {@code data} 안에 그대로 둔다.
 *
 * <p>{@code occurredAt}은 분석이 실제 수행된 시각({@code processedAt})을 {@code +09:00}으로 변환해
 * 쓴다 — 백엔드 수신 시각이 아니다. 값이 없으면 현재 시각으로 대체한다.
 *
 * <p>전송 실패는 여기서 잡아 로그만 남기고 위로 던지지 않는다 — 이미 커밋된 DB 저장 결과가 WebSocket
 * 전송 실패 때문에 실패한 것처럼 보이면 안 된다.
 */
@Component
public class AiCargoAnalysisBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(AiCargoAnalysisBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public AiCargoAnalysisBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 전체 구독 토픽({@link AiCargoAnalysisTopics#ALL})에는 항상 보낸다. {@code cargoId}가 있으면
     * 화물별 토픽에도 함께 보낸다 — cargoId가 없는 분석 결과(파렛트만 감지되고 화물 식별자가 아직 없는
     * 경우 등)는 전체 토픽으로만 전달된다.
     */
    public void broadcast(AiCargoAnalysisResponse response) {
        RealtimeEvent<AiCargoAnalysisResponse> event = RealtimeEvent.of(
                RealtimeEventType.AI_CARGO_ANALYSIS_COMPLETED,
                response.vehicleId(),
                response.processedAt() != null
                        ? CommunicationTime.toOffset(response.processedAt())
                        : CommunicationTime.nowOffset(),
                response);
        try {
            messagingTemplate.convertAndSend(AiCargoAnalysisTopics.ALL, event);
            if (response.cargoId() != null && !response.cargoId().isBlank()) {
                messagingTemplate.convertAndSend(AiCargoAnalysisTopics.byCargoId(response.cargoId()), event);
            }
            log.debug("AI cargo analysis broadcast sent: analysisId={}, cargoId={}, vehicleId={}",
                    response.analysisId(), response.cargoId(), response.vehicleId());
        } catch (Exception e) {
            log.error("Failed to broadcast AI cargo analysis event: analysisId={}, error={}",
                    response.analysisId(), e.getMessage());
        }
    }
}
