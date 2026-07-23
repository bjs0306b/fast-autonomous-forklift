package com.fast.backend.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code cargo/detected} MQTT 토픽으로 수신되는 AI 화물·파렛트 분석 결과(prompt26.md 1장·3장 최종 규격).
 *
 * <p>{@code status}·{@code detection.boxes[].className}처럼 enum으로 바로 받을 수 있는 필드도 일부러
 * 원시 문자열/구조 그대로 받는다 — 잘못된 값이 왔을 때 Jackson이 즉시 400을 던지는 대신
 * {@code AiCargoAnalysisService}가 검증 순서(schemaVersion → status → 상태별 정합성 → 필드별 값 검증)를
 * 스스로 통제하도록 하기 위함이다(prompt24.md의 {@code VehicleStatusUpdateRequest}와 동일한 이유).
 *
 * <p>{@code vehicleId}/{@code cargoId}는 이 저장소에 아직 cargo/pallet/task 도메인이 없어(prompt26.md
 * 5장 분석 결과) 존재 검증 없이 느슨한 식별자로만 다룬다.
 */
public record AiCargoAnalysisMessage(
        String schemaVersion,
        String analysisId,
        String vehicleId,
        String cargoId,
        String status,
        Detection detection,
        Distance distance,
        Dimensions dimensions,
        LoadBalance loadBalance,
        Ratios ratios,
        String message,
        LocalDateTime capturedAt,
        LocalDateTime processedAt
) {

    public record Detection(List<DetectedBox> boxes) {
    }

    public record DetectedBox(String className, Double confidence, List<Integer> bboxPx) {
    }

    public record Distance(Double valueCm, Double stdCm) {
    }

    public record Dimensions(Double widthCm, Double heightCm, Double depthCm, Double volumeCm3, String scale) {
    }

    public record LoadBalance(List<String> direction, String message) {
    }

    public record Ratios(Double horizontal, Double vertical) {
    }
}
