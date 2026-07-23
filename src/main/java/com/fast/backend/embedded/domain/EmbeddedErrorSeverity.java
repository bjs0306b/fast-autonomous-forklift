package com.fast.backend.embedded.domain;

import java.util.Optional;

/** 오류 심각도(prompt29.md 9장). severity는 확정된 값이라 enum으로 관리한다. */
public enum EmbeddedErrorSeverity {
    WARNING,
    ERROR,
    CRITICAL;

    public static Optional<EmbeddedErrorSeverity> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(EmbeddedErrorSeverity.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
