package com.fast.backend.embedded.domain;

import java.util.Optional;

/** 오류 발생 영역(prompt29.md 9장). errorSource는 확정된 값이라 enum으로 관리한다. */
public enum EmbeddedErrorSource {
    DRIVE,
    STEERING,
    FORK,
    LIMIT_SWITCH,
    UART,
    SYSTEM;

    public static Optional<EmbeddedErrorSource> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(EmbeddedErrorSource.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
