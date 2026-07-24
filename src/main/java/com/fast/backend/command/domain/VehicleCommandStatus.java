package com.fast.backend.command.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 명령의 내부 추적 상태(구 {@code EmbeddedCommandStatus}를 통합 명령 도메인으로 이관).
 *
 * <p>MQTT <b>발행</b>과 실제 <b>실행</b>을 구분하기 위해 도입했다. 확정 규격(prompt32.md 1장 12번)의
 * 결과 상태 6종({@code ACCEPTED/IN_PROGRESS/SUCCESS/FAILED/REJECTED/CANCELLED})을 그대로 내부 상태로
 * 재사용하고, 결과를 받기 전 단계만 {@link #PENDING}/{@link #PUBLISHED}/{@link #PUBLISH_FAILED} 3개를
 * 추가했다 — 이름이나 의미를 바꾸지 않아 결과 수신 시 별도 매핑 없이 그대로 상태를 옮길 수 있다
 * ("기존 DB 상태 전이 규칙과 충돌하지 않도록 매핑한다", 1장 12번).
 *
 * <p>상태 전이표:
 * <pre>
 * PENDING     → PUBLISHED, PUBLISH_FAILED
 * PUBLISHED   → ACCEPTED, IN_PROGRESS, SUCCESS, FAILED, REJECTED, CANCELLED
 * ACCEPTED    → IN_PROGRESS, SUCCESS, FAILED, REJECTED, CANCELLED
 * IN_PROGRESS → SUCCESS, FAILED, REJECTED, CANCELLED
 * (SUCCESS/FAILED/REJECTED/CANCELLED/PUBLISH_FAILED는 종료 상태 — 더 이상 전이하지 않는다)
 * </pre>
 *
 * <p>종료 상태에서 어떤 전이도 허용하지 않으므로, <b>중복 종료 결과 수신</b>도 이 규칙 하나로 함께
 * 걸러진다(별도 중복 판정 코드가 필요 없다).
 */
public enum VehicleCommandStatus {
    PENDING,
    PUBLISHED,
    PUBLISH_FAILED,
    ACCEPTED,
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    REJECTED,
    CANCELLED;

    private static final Set<VehicleCommandStatus> TERMINAL_RESULTS =
            EnumSet.of(SUCCESS, FAILED, REJECTED, CANCELLED);

    /** 진행 단계 순서(값이 클수록 나중 단계) — 역행 전이를 막는 데만 사용, 종료 상태는 별도 취급. */
    private static final Map<VehicleCommandStatus, Integer> PROGRESS_RANK = new EnumMap<>(VehicleCommandStatus.class);

    static {
        PROGRESS_RANK.put(PENDING, 0);
        PROGRESS_RANK.put(PUBLISHED, 1);
        PROGRESS_RANK.put(ACCEPTED, 2);
        PROGRESS_RANK.put(IN_PROGRESS, 3);
    }

    /** ROS2/MCU가 보내는 {@code result} 문자열만 인정한다 — PENDING/PUBLISHED/PUBLISH_FAILED는 내부 전용. */
    public static Optional<VehicleCommandStatus> fromResultRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            VehicleCommandStatus status = VehicleCommandStatus.valueOf(raw.trim().toUpperCase());
            return TERMINAL_RESULTS.contains(status) || status == ACCEPTED || status == IN_PROGRESS
                    ? Optional.of(status)
                    : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean isTerminal() {
        return TERMINAL_RESULTS.contains(this) || this == PUBLISH_FAILED;
    }

    /** {@code ACCEPTED}/{@code IN_PROGRESS}는 완료로 처리하지 않는다. */
    public boolean isCompleted() {
        return TERMINAL_RESULTS.contains(this);
    }

    /**
     * 이 상태에서 {@code next}로 전이 가능한지 검증한다.
     * <ul>
     *   <li>종료 상태({@link #isTerminal()})에서는 어떤 전이도 허용하지 않는다(역행·중복 결과 차단).</li>
     *   <li>{@code next}가 종료 상태면 PENDING을 제외한 모든 비종료 상태에서 곧바로 허용한다 —
     *       수신 측이 ACCEPTED/IN_PROGRESS 없이 바로 최종 결과를 보낼 수도 있기 때문이다.</li>
     *   <li>{@code next}가 비종료 진행 단계면 {@link #PROGRESS_RANK}상 현재보다 뒤 단계일 때만 허용한다
     *       (예: IN_PROGRESS → ACCEPTED 금지).</li>
     *   <li>{@link #PUBLISH_FAILED}는 오직 {@link #PENDING}에서만 전이 가능하다.</li>
     * </ul>
     */
    public boolean canTransitionTo(VehicleCommandStatus next) {
        if (isTerminal()) {
            return false;
        }
        if (next == PUBLISH_FAILED) {
            return this == PENDING;
        }
        if (TERMINAL_RESULTS.contains(next)) {
            return this != PENDING;
        }
        Integer currentRank = PROGRESS_RANK.get(this);
        Integer nextRank = PROGRESS_RANK.get(next);
        return currentRank != null && nextRank != null && nextRank > currentRank;
    }
}
