package com.fast.backend.ai.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Optional;

/**
 * 무게 중심이 치우친 방향 코드(prompt26.md 2.5장). {@code left/right}는 서로 반대,
 * {@code front/back}은 서로 반대이며, 대각선은 이 중 최대 하나씩을 조합해 두 값으로 표현한다
 * (예: {@code [LEFT, FRONT]}). 이 enum 자체는 방향 값 하나만 표현하고, 배열 조합(중복·반대쌍) 검증은
 * {@code AiCargoAnalysisService}가 담당한다 — enum이 스스로의 조합 규칙까지 알 필요는 없기 때문이다.
 */
public enum LoadBalanceDirection {

    LEFT("left"),
    RIGHT("right"),
    FRONT("front"),
    BACK("back");

    private final String rawValue;

    LoadBalanceDirection(String rawValue) {
        this.rawValue = rawValue;
    }

    @JsonValue
    public String rawValue() {
        return rawValue;
    }

    public static Optional<LoadBalanceDirection> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase();
        for (LoadBalanceDirection direction : values()) {
            if (direction.rawValue.equals(normalized)) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }

    /** left↔right, front↔back — 이 조합이 동시에 오면 물리적으로 모순이라 거부 대상이다. */
    public LoadBalanceDirection opposite() {
        return switch (this) {
            case LEFT -> RIGHT;
            case RIGHT -> LEFT;
            case FRONT -> BACK;
            case BACK -> FRONT;
        };
    }
}
