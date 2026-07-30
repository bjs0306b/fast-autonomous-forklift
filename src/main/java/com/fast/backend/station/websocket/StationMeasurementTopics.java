package com.fast.backend.station.websocket;

/**
 * 측정 스테이션 WebSocket STOMP destination 문자열을 한 곳에서 관리한다(prompt16.md 9단계,
 * {@code VehicleWebSocketTopics}/{@code AiCargoAnalysisTopics}와 동일 패턴). 기존
 * {@code /topic/ai/cargo-analysis}를 재사용하지 않고 스테이션 전용 도메인 경로를 둔다(원칙 3·5번).
 */
public final class StationMeasurementTopics {

    public static final String ALL = "/topic/stations/measurements";

    private StationMeasurementTopics() {
    }

    public static String bySessionId(String sessionId) {
        return "/topic/stations/" + sessionId + "/measurements";
    }
}
