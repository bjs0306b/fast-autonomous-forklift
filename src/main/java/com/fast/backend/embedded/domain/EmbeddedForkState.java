package com.fast.backend.embedded.domain;

import java.util.Optional;

/**
 * 실물 포크 상태(prompt29.md 8장). Isaac Sim의 {@code IsaacForkliftStatus}(IDLE/MOVING/LIFTING/...)와
 * 의미가 달라 하나의 enum으로 합치지 않는다(11장).
 */
public enum EmbeddedForkState {
    MOVING_UP,
    MOVING_DOWN,
    STOPPED,
    BOTTOM,
    ERROR,
    UNKNOWN;

    public static Optional<EmbeddedForkState> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(EmbeddedForkState.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
