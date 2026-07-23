package com.fast.backend.station.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Optional;

/**
 * 편하중 방향(prompt16.md 정책 5·3단계). JSON 값은 소문자(left/right/front/back). 기존
 * {@code LoadBalanceDirection}과 값은 같지만 스테이션 도메인 전용으로 분리했다(원칙 3·5번) — 두 도메인의
 * enum을 공유하면 한쪽 규격이 바뀔 때 다른 쪽이 의도치 않게 영향을 받는다.
 */
public enum StationDirection {

    LEFT("left"),
    RIGHT("right"),
    FRONT("front"),
    BACK("back");

    private final String rawValue;

    StationDirection(String rawValue) {
        this.rawValue = rawValue;
    }

    @JsonValue
    public String rawValue() {
        return rawValue;
    }

    public static Optional<StationDirection> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase();
        for (StationDirection direction : values()) {
            if (direction.rawValue.equals(normalized)) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }
}
