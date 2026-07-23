package com.fast.backend.ai.dto;

import com.fast.backend.ai.domain.AiAnalysisStatus;
import com.fast.backend.ai.domain.DimensionScale;
import com.fast.backend.ai.domain.LoadBalanceDirection;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 화물 분석 결과 조회 API 응답이자 WebSocket {@code /topic/ai/cargo-analysis} 이벤트 payload
 * 그 자체다(prompt26.md 13장·14장). 별도 envelope(eventType 등)로 감싸지 않고 이 구조를 그대로
 * 브로드캐스트한다 — 이 기능에는 이벤트 종류가 하나뿐이라 {@code VehicleWebSocketEvent} 같은 다중
 * 이벤트 envelope이 필요하지 않다(prompt26.md 14장 "구조를 억지로 분리하지 마" 대신 "필요 없으면
 * 만들지 마"의 반대쪽 판단 — 여기서는 만들 필요가 없어서 안 만들었다).
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
