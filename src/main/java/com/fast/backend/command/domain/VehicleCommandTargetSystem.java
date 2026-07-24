package com.fast.backend.command.domain;

import java.util.Optional;

/**
 * 명령을 수행해야 하는 대상 시스템(prompt32.md 1장 8번 확정 규격).
 *
 * <ul>
 *   <li>{@link #ROS2} — 주행/내비게이션을 담당하는 ROS2 스택</li>
 *   <li>{@link #EMBEDDED} — 포크·모터를 직접 구동하는 임베디드(MCU) 펌웨어</li>
 *   <li>{@link #ALL} — 두 시스템이 <b>동시에</b> 반응해야 하는 명령(비상 정지·해제)</li>
 * </ul>
 *
 * <p>수신 측(ROS2 브리지 / 임베디드 펌웨어)이 <b>payload를 깊게 파싱하기 전에</b> 자기가 처리할 명령인지
 * 즉시 판단할 수 있도록, 이 필드를 명령 envelope 최상단에 둔다.
 */
public enum VehicleCommandTargetSystem {
    ROS2,
    EMBEDDED,
    ALL;

    public static Optional<VehicleCommandTargetSystem> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(VehicleCommandTargetSystem.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
