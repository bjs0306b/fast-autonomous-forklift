package com.fast.backend.command.domain;

import java.util.Optional;

/**
 * 지원하는 차량 명령 8종과 <b>각 명령의 확정 조합 규칙</b>(prompt32.md 1장 8번).
 *
 * <p>확정 조합표:
 * <pre>
 * MOVE           → targetSystem=ROS2      category=MOVE     payload.destination 필수
 * FORK_UP        → targetSystem=EMBEDDED  category=FORK
 * FORK_DOWN      → targetSystem=EMBEDDED  category=FORK
 * LOAD           → targetSystem=EMBEDDED  category=LOAD
 * UNLOAD         → targetSystem=EMBEDDED  category=LOAD
 * EMERGENCY_STOP → targetSystem=ALL       category=SAFETY
 * RESET_ESTOP    → targetSystem=ALL       category=SAFETY
 * STOP           → targetSystem=EMBEDDED  category=SAFETY   (아래 주석 참고)
 * </pre>
 *
 * <p><b>{@link #STOP}에 대한 결정</b>: 확정 규격의 "지원값" 목록에는 STOP이 들어 있지만
 * "명령 조합 검증 규칙" 절에는 STOP 항목이 없다. 임의로 빼거나 추측으로 ROS2에 배정하지 않고,
 * 이 프로젝트가 이미 STOP을 실물 임베디드 명령으로 다뤄 온 기존 동작(구 {@code EmbeddedCommandType})을
 * 유지해 {@code EMBEDDED}+{@code SAFETY}로 정의했다. 비상정지가 아닌 통상 정지라 {@code ALL}로 넓히지
 * 않았다. <b>이 조합은 팀 확인이 필요한 항목</b>이며 문서에도 그렇게 표시했다.
 *
 * <p>{@link #RESET_ESTOP}은 확정 규격이 "ALL 또는 팀 기존 처리 대상에 맞게"라고 열어 뒀다.
 * EMERGENCY_STOP과 짝을 이루는 해제 명령이므로 동일하게 {@code ALL}+{@code SAFETY}로 고정했다 —
 * 비상정지를 두 시스템이 함께 걸었다면 해제도 두 시스템이 함께 풀어야 하기 때문이다.
 *
 * <p>알 수 없는 명령은 {@link #fromRaw(String)}가 빈 Optional을 돌려주며 절대 다른 값으로 흡수하지
 * 않는다 — 안전 명령을 다루는 도메인에서 "모르는 값을 적당히 해석"하는 것은 금지다.
 */
public enum VehicleCommandType {

    MOVE(VehicleCommandTargetSystem.ROS2, VehicleCommandCategory.MOVE, true),
    STOP(VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.SAFETY, false),
    FORK_UP(VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.FORK, false),
    FORK_DOWN(VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.FORK, false),
    LOAD(VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.LOAD, false),
    UNLOAD(VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.LOAD, false),
    EMERGENCY_STOP(VehicleCommandTargetSystem.ALL, VehicleCommandCategory.SAFETY, false),
    RESET_ESTOP(VehicleCommandTargetSystem.ALL, VehicleCommandCategory.SAFETY, false);

    private final VehicleCommandTargetSystem requiredTargetSystem;
    private final VehicleCommandCategory requiredCategory;
    private final boolean destinationRequired;

    VehicleCommandType(
            VehicleCommandTargetSystem requiredTargetSystem,
            VehicleCommandCategory requiredCategory,
            boolean destinationRequired) {
        this.requiredTargetSystem = requiredTargetSystem;
        this.requiredCategory = requiredCategory;
        this.destinationRequired = destinationRequired;
    }

    public static Optional<VehicleCommandType> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(VehicleCommandType.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 이 명령이 반드시 가져야 하는 대상 시스템. */
    public VehicleCommandTargetSystem requiredTargetSystem() {
        return requiredTargetSystem;
    }

    /** 이 명령이 반드시 가져야 하는 분류. */
    public VehicleCommandCategory requiredCategory() {
        return requiredCategory;
    }

    /** {@code payload.destination}이 필수인 명령인지(현재 {@link #MOVE}만 해당). */
    public boolean isDestinationRequired() {
        return destinationRequired;
    }

    /** 비상 정지 계열(수신 측이 최우선 처리해야 하는 명령)인지. */
    public boolean isEmergency() {
        return this == EMERGENCY_STOP || this == RESET_ESTOP;
    }

    public boolean matches(VehicleCommandTargetSystem targetSystem, VehicleCommandCategory category) {
        return requiredTargetSystem == targetSystem && requiredCategory == category;
    }
}
