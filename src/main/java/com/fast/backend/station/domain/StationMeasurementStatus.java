package com.fast.backend.station.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Optional;

/**
 * 측정 스테이션 측정 결과 상태(prompt16.md 3단계). JSON 값은 소문자(ok/no_detection/unreliable)이며
 * {@link #rawValue()}(@JsonValue)로 직렬화하고 {@link #fromRaw(String)}로 역직렬화·검증한다 —
 * 기존 {@code AiAnalysisStatus}와 동일한 패턴이되 스테이션 도메인 전용으로 분리했다(원칙 3·5번).
 */
public enum StationMeasurementStatus {

    OK("ok"),
    NO_DETECTION("no_detection"),
    UNRELIABLE("unreliable");

    private final String rawValue;

    StationMeasurementStatus(String rawValue) {
        this.rawValue = rawValue;
    }

    @JsonValue
    public String rawValue() {
        return rawValue;
    }

    public static Optional<StationMeasurementStatus> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase();
        for (StationMeasurementStatus status : values()) {
            if (status.rawValue.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
