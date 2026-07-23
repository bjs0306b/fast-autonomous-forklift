package com.fast.backend.embedded.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 실물 명령의 내부 추적 상태(prompt29.md 15장). MQTT 발행과 실제 실행을 구분하기 위해 도입했다 —
 * "필요 이상으로 상태를 늘리지 않는다"는 지침에 따라, MCU/ROS2가 보내는
 * {@code EmbeddedCommandResultMessage.result()} 6종(ACCEPTED/IN_PROGRESS/SUCCESS/FAILED/REJECTED/
 * CANCELLED)을 그대로 내부 상태로 재사용하고, 결과를 받기 전 단계만 {@link #PENDING}/{@link #PUBLISHED}/
 * {@link #PUBLISH_FAILED} 3개를 추가했다 — 이름을 새로 짓거나(예: "IN_PROGRESS" 대신 "RUNNING") 의미를
 * 바꾸지 않아 결과 수신 시 별도 매핑 없이 그대로 상태를 옮길 수 있다.
 *
 * <p>상태 전이표:
 * <pre>
 * PENDING     → PUBLISHED, PUBLISH_FAILED
 * PUBLISHED   → ACCEPTED, IN_PROGRESS, SUCCESS, FAILED, REJECTED, CANCELLED
 * ACCEPTED    → IN_PROGRESS, SUCCESS, FAILED, REJECTED, CANCELLED
 * IN_PROGRESS → SUCCESS, FAILED, REJECTED, CANCELLED
 * (SUCCESS/FAILED/REJECTED/CANCELLED/PUBLISH_FAILED는 종료 상태 — 더 이상 전이하지 않는다)
 * </pre>
 */
public enum EmbeddedCommandStatus {
    PENDING,
    PUBLISHED,
    PUBLISH_FAILED,
    ACCEPTED,
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    REJECTED,
    CANCELLED;

    private static final Set<EmbeddedCommandStatus> TERMINAL_RESULTS =
            EnumSet.of(SUCCESS, FAILED, REJECTED, CANCELLED);

    /** 진행 단계 순서(값이 클수록 나중 단계) — 역행 전이를 막는 데만 사용, 종료 상태는 별도 취급. */
    private static final Map<EmbeddedCommandStatus, Integer> PROGRESS_RANK = new EnumMap<>(EmbeddedCommandStatus.class);

    static {
        PROGRESS_RANK.put(PENDING, 0);
        PROGRESS_RANK.put(PUBLISHED, 1);
        PROGRESS_RANK.put(ACCEPTED, 2);
        PROGRESS_RANK.put(IN_PROGRESS, 3);
    }

    /** MCU/ROS2가 보내는 {@code result} 문자열만 인정한다 — PENDING/PUBLISHED/PUBLISH_FAILED는 내부 전용. */
    public static Optional<EmbeddedCommandStatus> fromResultRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            EmbeddedCommandStatus status = EmbeddedCommandStatus.valueOf(raw.trim().toUpperCase());
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

    /** {@code ACCEPTED}/{@code IN_PROGRESS}는 완료로 처리하지 않는다(7장 결과 검증). */
    public boolean isCompleted() {
        return TERMINAL_RESULTS.contains(this);
    }

    /**
     * 이 상태에서 {@code next}로 전이 가능한지 검증한다.
     * <ul>
     *   <li>종료 상태({@link #isTerminal()})에서는 어떤 전이도 허용하지 않는다(역행 방지).</li>
     *   <li>{@code next}가 종료 상태(SUCCESS/FAILED/REJECTED/CANCELLED)면 PENDING을 제외한 모든
     *       비종료 상태에서 곧바로 허용한다 — MCU가 ACCEPTED/IN_PROGRESS 없이 바로 최종 결과를 보낼
     *       수도 있기 때문이다(결과 수신 중간 단계를 강제하지 않음).</li>
     *   <li>{@code next}가 비종료 진행 단계(PUBLISHED/ACCEPTED/IN_PROGRESS)면 {@link #PROGRESS_RANK}상
     *       현재보다 뒤 단계일 때만 허용한다(역행 전이 차단, 예: IN_PROGRESS → ACCEPTED 금지).</li>
     *   <li>{@code PUBLISH_FAILED}는 오직 {@link #PENDING}에서만 전이 가능하다.</li>
     * </ul>
     */
    public boolean canTransitionTo(EmbeddedCommandStatus next) {
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
