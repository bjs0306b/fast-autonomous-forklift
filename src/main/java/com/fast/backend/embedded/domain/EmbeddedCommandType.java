package com.fast.backend.embedded.domain;

import java.util.Optional;

/**
 * 실물 지게차 명령 종류(prompt29.md 5장). 알 수 없는 명령은 거부한다(21장 "알려지지 않은 command
 * 거부") — {@link com.fast.backend.vehicle.domain.VehicleStatus}처럼 UNKNOWN으로 흡수하지 않는다.
 */
public enum EmbeddedCommandType {
    STOP,
    FORK_UP,
    FORK_DOWN,
    LOAD,
    UNLOAD,
    EMERGENCY_STOP,
    RESET_ESTOP;

    public static Optional<EmbeddedCommandType> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(EmbeddedCommandType.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
