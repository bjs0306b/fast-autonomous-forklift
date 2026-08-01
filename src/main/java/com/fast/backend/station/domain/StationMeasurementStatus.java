package com.fast.backend.station.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Optional;

/**
 * 측정 결과 상태. JSON 값은 소문자이며 {@link #rawValue()}로 직렬화하고
 * {@link #fromRaw(String)}로 정규화한다.
 *
 * <p>{@link #DIMENSIONS_ONLY}는 높이만 측정된 상태라 전복·돌출 판정값이 null이다. 현재 적재 추천은
 * 세 안전값을 모두 요구하므로 {@link #OK}만 통과한다.
 */
public enum StationMeasurementStatus {

    OK("ok"),
    DIMENSIONS_ONLY("dimensions_only"),
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
