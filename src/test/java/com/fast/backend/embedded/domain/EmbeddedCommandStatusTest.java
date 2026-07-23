package com.fast.backend.embedded.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EmbeddedCommandStatus#canTransitionTo}가 정상 진행·역행 차단·중복 결과 거부·비상정지 종료
 * 상태를 모두 올바르게 처리하는지 검증한다(prompt29.md 15장 상태 전이표).
 */
class EmbeddedCommandStatusTest {

    @Test
    void fromResultRaw_acceptsOnlyMcuResultValues() {
        assertThat(EmbeddedCommandStatus.fromResultRaw("ACCEPTED")).contains(EmbeddedCommandStatus.ACCEPTED);
        assertThat(EmbeddedCommandStatus.fromResultRaw("in_progress")).contains(EmbeddedCommandStatus.IN_PROGRESS);
        assertThat(EmbeddedCommandStatus.fromResultRaw("SUCCESS")).contains(EmbeddedCommandStatus.SUCCESS);
        assertThat(EmbeddedCommandStatus.fromResultRaw("FAILED")).contains(EmbeddedCommandStatus.FAILED);
        assertThat(EmbeddedCommandStatus.fromResultRaw("REJECTED")).contains(EmbeddedCommandStatus.REJECTED);
        assertThat(EmbeddedCommandStatus.fromResultRaw("CANCELLED")).contains(EmbeddedCommandStatus.CANCELLED);
    }

    @Test
    void fromResultRaw_rejectsInternalOnlyStates() {
        // PENDING/PUBLISHED/PUBLISH_FAILED는 MCU가 보낼 수 있는 result 값이 아니다.
        assertThat(EmbeddedCommandStatus.fromResultRaw("PENDING")).isEmpty();
        assertThat(EmbeddedCommandStatus.fromResultRaw("PUBLISHED")).isEmpty();
        assertThat(EmbeddedCommandStatus.fromResultRaw("PUBLISH_FAILED")).isEmpty();
    }

    @Test
    void fromResultRaw_unknownOrBlank_returnsEmpty() {
        assertThat(EmbeddedCommandStatus.fromResultRaw("NONSENSE")).isEmpty();
        assertThat(EmbeddedCommandStatus.fromResultRaw("")).isEmpty();
        assertThat(EmbeddedCommandStatus.fromResultRaw(null)).isEmpty();
    }

    @Test
    void isTerminal_trueForFourResultsAndPublishFailed() {
        assertThat(EmbeddedCommandStatus.SUCCESS.isTerminal()).isTrue();
        assertThat(EmbeddedCommandStatus.FAILED.isTerminal()).isTrue();
        assertThat(EmbeddedCommandStatus.REJECTED.isTerminal()).isTrue();
        assertThat(EmbeddedCommandStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(EmbeddedCommandStatus.PUBLISH_FAILED.isTerminal()).isTrue();
        assertThat(EmbeddedCommandStatus.PENDING.isTerminal()).isFalse();
        assertThat(EmbeddedCommandStatus.PUBLISHED.isTerminal()).isFalse();
        assertThat(EmbeddedCommandStatus.ACCEPTED.isTerminal()).isFalse();
        assertThat(EmbeddedCommandStatus.IN_PROGRESS.isTerminal()).isFalse();
    }

    @Test
    void isCompleted_trueOnlyForFourResults_excludesPublishFailed() {
        // PUBLISH_FAILED는 종료 상태지만 "결과 완료"는 아니다(MQTT 발행 자체가 실패했을 뿐 명령 결과가 아님).
        assertThat(EmbeddedCommandStatus.SUCCESS.isCompleted()).isTrue();
        assertThat(EmbeddedCommandStatus.PUBLISH_FAILED.isCompleted()).isFalse();
    }

    @Test
    void canTransitionTo_pendingToPublished_allowed() {
        assertThat(EmbeddedCommandStatus.PENDING.canTransitionTo(EmbeddedCommandStatus.PUBLISHED)).isTrue();
    }

    @Test
    void canTransitionTo_pendingToPublishFailed_allowed() {
        assertThat(EmbeddedCommandStatus.PENDING.canTransitionTo(EmbeddedCommandStatus.PUBLISH_FAILED)).isTrue();
    }

    @Test
    void canTransitionTo_publishFailedOnlyFromPending() {
        assertThat(EmbeddedCommandStatus.PUBLISHED.canTransitionTo(EmbeddedCommandStatus.PUBLISH_FAILED)).isFalse();
        assertThat(EmbeddedCommandStatus.ACCEPTED.canTransitionTo(EmbeddedCommandStatus.PUBLISH_FAILED)).isFalse();
    }

    @Test
    void canTransitionTo_pendingDirectlyToTerminalResult_rejected() {
        // 결과는 발행(PUBLISHED) 이후에만 도착할 수 있다 — MQTT 발행 시도 전에 결과가 올 수 없다.
        assertThat(EmbeddedCommandStatus.PENDING.canTransitionTo(EmbeddedCommandStatus.SUCCESS)).isFalse();
        assertThat(EmbeddedCommandStatus.PENDING.canTransitionTo(EmbeddedCommandStatus.FAILED)).isFalse();
    }

    @Test
    void canTransitionTo_publishedDirectlyToTerminalResult_allowed() {
        // MCU가 ACCEPTED/IN_PROGRESS 중간 단계 없이 곧바로 최종 결과를 보낼 수도 있다.
        assertThat(EmbeddedCommandStatus.PUBLISHED.canTransitionTo(EmbeddedCommandStatus.SUCCESS)).isTrue();
        assertThat(EmbeddedCommandStatus.PUBLISHED.canTransitionTo(EmbeddedCommandStatus.FAILED)).isTrue();
        assertThat(EmbeddedCommandStatus.PUBLISHED.canTransitionTo(EmbeddedCommandStatus.REJECTED)).isTrue();
        assertThat(EmbeddedCommandStatus.PUBLISHED.canTransitionTo(EmbeddedCommandStatus.CANCELLED)).isTrue();
    }

    @Test
    void canTransitionTo_publishedToAcceptedToInProgress_normalProgression() {
        assertThat(EmbeddedCommandStatus.PUBLISHED.canTransitionTo(EmbeddedCommandStatus.ACCEPTED)).isTrue();
        assertThat(EmbeddedCommandStatus.ACCEPTED.canTransitionTo(EmbeddedCommandStatus.IN_PROGRESS)).isTrue();
        assertThat(EmbeddedCommandStatus.IN_PROGRESS.canTransitionTo(EmbeddedCommandStatus.SUCCESS)).isTrue();
    }

    @Test
    void canTransitionTo_backwardProgressTransition_rejected() {
        // IN_PROGRESS -> ACCEPTED, ACCEPTED -> PUBLISHED 같은 역행은 금지된다.
        assertThat(EmbeddedCommandStatus.IN_PROGRESS.canTransitionTo(EmbeddedCommandStatus.ACCEPTED)).isFalse();
        assertThat(EmbeddedCommandStatus.ACCEPTED.canTransitionTo(EmbeddedCommandStatus.PUBLISHED)).isFalse();
        assertThat(EmbeddedCommandStatus.IN_PROGRESS.canTransitionTo(EmbeddedCommandStatus.PUBLISHED)).isFalse();
    }

    @Test
    void canTransitionTo_sameStateTransition_rejected() {
        assertThat(EmbeddedCommandStatus.ACCEPTED.canTransitionTo(EmbeddedCommandStatus.ACCEPTED)).isFalse();
        assertThat(EmbeddedCommandStatus.PUBLISHED.canTransitionTo(EmbeddedCommandStatus.PUBLISHED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = EmbeddedCommandStatus.class, names = {"SUCCESS", "FAILED", "REJECTED", "CANCELLED", "PUBLISH_FAILED"})
    void canTransitionTo_fromAnyTerminalState_alwaysRejected(EmbeddedCommandStatus terminal) {
        // 종료 상태에서는 어떤 전이도 허용되지 않는다 — 이 메커니즘이 "중복 결과 수신"까지 함께 막는다.
        for (EmbeddedCommandStatus next : EmbeddedCommandStatus.values()) {
            assertThat(terminal.canTransitionTo(next))
                    .as("%s -> %s should be rejected (terminal state)", terminal, next)
                    .isFalse();
        }
    }

    @Test
    void canTransitionTo_duplicateSuccessResult_rejectedViaTerminalCheck() {
        // 이미 SUCCESS인 명령에 다시 SUCCESS(또는 다른 결과)가 와도 전이가 거부된다(중복 결과 방어).
        assertThat(EmbeddedCommandStatus.SUCCESS.canTransitionTo(EmbeddedCommandStatus.SUCCESS)).isFalse();
        assertThat(EmbeddedCommandStatus.SUCCESS.canTransitionTo(EmbeddedCommandStatus.FAILED)).isFalse();
    }
}
