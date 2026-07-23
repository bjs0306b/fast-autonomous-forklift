package com.fast.backend.ai.websocket;

import com.fast.backend.ai.dto.AiCargoAnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * AI 화물 분석 결과를 STOMP로 브로드캐스트하는 전용 통로(prompt26.md 14장). 이벤트 종류가
 * "분석 결과 갱신" 하나뿐이라 {@code VehicleWebSocketBroadcaster}처럼 여러 이벤트 타입을 한 클래스가
 * 다루지 않는다 — 차량 도메인과 무관한 별도 도메인(AI 화물 분석)이라 기존 Broadcaster에 얹으면 그
 * 클래스가 "차량 이벤트 전용"이라는 책임 경계를 잃는다.
 *
 * <p>전송 실패는 여기서 잡아 로그만 남기고 위로 던지지 않는다 — 이미 커밋된 DB 저장 결과가 WebSocket
 * 전송 실패 때문에 실패한 것처럼 보이면 안 된다({@code VehicleWebSocketBroadcaster}와 동일한 방어 패턴).
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
        try {
            messagingTemplate.convertAndSend(AiCargoAnalysisTopics.ALL, response);
            if (response.cargoId() != null && !response.cargoId().isBlank()) {
                messagingTemplate.convertAndSend(AiCargoAnalysisTopics.byCargoId(response.cargoId()), response);
            }
            log.debug("AI cargo analysis broadcast sent: analysisId={}, cargoId={}",
                    response.analysisId(), response.cargoId());
        } catch (Exception e) {
            log.error("Failed to broadcast AI cargo analysis event: analysisId={}, error={}",
                    response.analysisId(), e.getMessage());
        }
    }
}
