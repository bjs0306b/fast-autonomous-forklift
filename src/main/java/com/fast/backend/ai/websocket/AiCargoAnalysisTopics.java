package com.fast.backend.ai.websocket;

/**
 * AI 화물 분석 결과 WebSocket STOMP 토픽 문자열을 한 곳에서 관리한다(prompt26.md 14장,
 * {@code VehicleWebSocketTopics}와 동일한 패턴). {@link AiCargoAnalysisBroadcaster}만 사용한다.
 */
public final class AiCargoAnalysisTopics {

    public static final String ALL = "/topic/ai/cargo-analysis";

    private AiCargoAnalysisTopics() {
    }

    public static String byCargoId(String cargoId) {
        return ALL + "/" + cargoId;
    }
}
