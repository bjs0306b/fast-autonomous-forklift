package com.fast.backend.ai.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Optional;

/**
 * AI 화물·파렛트 분석 결과의 상태(prompt26.md 2.1장). MQTT payload는 소문자("ok", "no_detection",
 * "unreliable")를 쓰지만 백엔드 내부·DB에는 대문자 enum 이름을 사용한다.
 *
 * <p>{@link com.fast.backend.vehicle.domain.VehicleStatus#fromRaw(String)}와 달리 이 enum은
 * "알 수 없는 값은 UNKNOWN으로 흡수"하지 않는다 — prompt26.md 16.2장이 "잘못된 status"를 명시적인
 * 검증 실패 케이스로 요구하기 때문이다(차량 상태와 달리 AI 분석 상태는 셋 중 하나가 아니면 메시지
 * 자체가 잘못된 것으로 간주해 거부한다).
 */
public enum AiAnalysisStatus {

    OK("ok"),
    NO_DETECTION("no_detection"),
    UNRELIABLE("unreliable");

    private final String rawValue;

    AiAnalysisStatus(String rawValue) {
        this.rawValue = rawValue;
    }

    @JsonValue
    public String rawValue() {
        return rawValue;
    }

    public static Optional<AiAnalysisStatus> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase();
        for (AiAnalysisStatus status : values()) {
            if (status.rawValue.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
