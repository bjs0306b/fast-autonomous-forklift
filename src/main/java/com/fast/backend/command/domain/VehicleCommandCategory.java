package com.fast.backend.command.domain;

import java.util.Optional;

/**
 * 명령의 성격 분류(prompt32.md 1장 8번 확정 규격).
 *
 * <ul>
 *   <li>{@link #MOVE} — 주행/이동</li>
 *   <li>{@link #FORK} — 포크 승강</li>
 *   <li>{@link #LOAD} — 적재/하역</li>
 *   <li>{@link #SAFETY} — 정지·비상정지·해제 등 안전 관련</li>
 * </ul>
 *
 * <p>{@link VehicleCommandTargetSystem}과 함께 수신 측의 1차 분기 키로 쓰인다. 특히 비상 정지는
 * {@code targetSystem=ALL} + {@code commandCategory=SAFETY} 조합이 보장되어, 수신 측이 명령 이름을
 * 비교하기 전에 안전 명령임을 알 수 있다.
 */
public enum VehicleCommandCategory {
    MOVE,
    FORK,
    LOAD,
    SAFETY;

    public static Optional<VehicleCommandCategory> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(VehicleCommandCategory.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
