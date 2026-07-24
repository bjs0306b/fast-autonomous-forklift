package com.fast.backend.ai.dto;

import com.fast.backend.ai.domain.AiAnalysisStatus;
import com.fast.backend.ai.domain.DimensionScale;
import com.fast.backend.ai.domain.LoadBalanceDirection;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 화물 분석 결과 조회 API 응답이자 WebSocket {@code /topic/ai/cargo-analysis} 이벤트의
 * {@code data} payload다(prompt26.md 13장·14장).
 *
 * <p><b>WebSocket 전송 시에는 공통 envelope로 감싸진다(prompt32.md 1장 13번 확정)</b>. 이전에는 이
 * 구조를 봉투 없이 그대로 브로드캐스트해서 프론트가 차량 이벤트와 AI 이벤트를 서로 다른 규격으로
 * 구독해야 했다. 이제 {@link com.fast.backend.common.websocket.RealtimeEvent}가 이 객체를
 * {@code data}에 담아 보낸다 — {@code AiCargoAnalysisBroadcaster} 참고. REST 조회 응답은 봉투 없이
 * 이 구조 그대로다.
 *
 * <p>{@code vehicleId}는 envelope의 최상위 {@code vehicleId}로도 함께 올라간다(차량과 연결되지 않은
 * 분석이면 null). {@code analysisId}/{@code cargoId} 같은 AI 도메인 고유 식별자는 최상위로 올리지 않고
 * 이 {@code data} 안에 그대로 유지한다.
 *
 * <p>{@code distance}/{@code dimensions}/{@code loadBalance}/{@code ratios}는 관련 원본 필드가 전부
 * null이면(예: {@code no_detection}) 객체 자체를 null로 내려준다 — prompt26.md 4장 예시(감지 실패 시
 * 이 네 필드 전체가 null)와 동일하게 맞추기 위함이다.
 */
public record AiCargoAnalysisResponse(
        String analysisId,
        String vehicleId,
        String cargoId,
        AiAnalysisStatus status,
        Detection detection,
        Distance distance,
        Dimensions dimensions,
        LoadBalance loadBalance,
        Ratios ratios,
        String message,
        LocalDateTime capturedAt,
        LocalDateTime processedAt,
        LocalDateTime receivedAt
) {

    public record Detection(List<DetectedBox> boxes) {
    }

    public record DetectedBox(String className, Double confidence, List<Integer> bboxPx) {
    }

    public record Distance(Double valueCm, Double stdCm) {
    }

    public record Dimensions(Double widthCm, Double heightCm, Double depthCm, Double volumeCm3, DimensionScale scale) {
    }

    public record LoadBalance(List<LoadBalanceDirection> direction, String message) {
    }

    public record Ratios(Double horizontal, Double vertical) {
    }
}
