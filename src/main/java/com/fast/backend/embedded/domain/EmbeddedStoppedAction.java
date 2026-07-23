package com.fast.backend.embedded.domain;

import java.util.Optional;

/**
 * 비상정지로 중단된 동작 종류(prompt29.md 7장 {@code stoppedActions}). 백엔드가 실제로 어떤 장치가
 * 멈췄는지 추측하지 않고, MCU/ROS2가 보낸 값만 그대로 이 enum으로 정규화해 보존한다
 * (20장 "백엔드가 실제 하드웨어 중단 범위를 추측하지 않는다").
 */
public enum EmbeddedStoppedAction {
    DRIVE,
    STEERING,
    FORK;

    public static Optional<EmbeddedStoppedAction> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(EmbeddedStoppedAction.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
