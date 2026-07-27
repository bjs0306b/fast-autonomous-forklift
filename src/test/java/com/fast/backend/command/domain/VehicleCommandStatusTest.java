package com.fast.backend.command.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 명령 상태 전이 규칙(prompt32.md 1장 12번 "기존 DB 상태 전이 규칙과 충돌하지 않도록 매핑",
 * "중복 종료 결과 처리")을 고정한다.
 */
class VehicleCommandStatusTest {

    @Test
    void fromResultRaw_acceptsOnlyResultValuesSentByRos2OrMcu() {
        assertThat(VehicleCommandStatus.fromResultRaw("ACCEPTED")).contains(VehicleCommandStatus.ACCEPTED);
        assertThat(VehicleCommandStatus.fromResultRaw("in_progress")).contains(VehicleCommandStatus.IN_PROGRESS);
        assertThat(VehicleCommandStatus.fromResultRaw("SUCCESS")).contains(VehicleCommandStatus.SUCCESS);
        assertThat(VehicleCommandStatus.fromResultRaw("FAILED")).contains(VehicleCommandStatus.FAILED);
        assertThat(VehicleCommandStatus.fromResultRaw("REJECTED")).contains(VehicleCommandStatus.REJECTED);
        assertThat(VehicleCommandStatus.fromResultRaw("CANCELLED")).contains(VehicleCommandStatus.CANCELLED);
    }

    @Test
    void fromResultRaw_rejectsInternalOnlyStatuses() {
        // PENDING/PUBLISHED/PUBLISH_FAILED는 백엔드 내부 추적 상태다 — 외부가 결과로 보낼 수 없다.
        assertThat(VehicleCommandStatus.fromResultRaw("PENDING")).isEmpty();
        assertThat(VehicleCommandStatus.fromResultRaw("PUBLISHED")).isEmpty();
        assertThat(VehicleCommandStatus.fromResultRaw("PUBLISH_FAILED")).isEmpty();
        assertThat(VehicleCommandStatus.fromResultRaw("DONE")).isEmpty();
        assertThat(VehicleCommandStatus.fromResultRaw(null)).isEmpty();
    }

    @Test
    void pending_canOnlyMoveToPublishedOrPublishFailed() {
        assertThat(VehicleCommandStatus.PENDING.canTransitionTo(VehicleCommandStatus.PUBLISHED)).isTrue();
        assertThat(VehicleCommandStatus.PENDING.canTransitionTo(VehicleCommandStatus.PUBLISH_FAILED)).isTrue();
        // 발행도 안 된 명령이 곧바로 성공했다는 결과는 받아들이지 않는다.
        assertThat(VehicleCommandStatus.PENDING.canTransitionTo(VehicleCommandStatus.SUCCESS)).isFalse();
    }

    @Test
    void published_canJumpStraightToAnyTerminalResult() {
        // MCU가 ACCEPTED/IN_PROGRESS 없이 곧바로 최종 결과를 보낼 수 있다.
        assertThat(VehicleCommandStatus.PUBLISHED.canTransitionTo(VehicleCommandStatus.SUCCESS)).isTrue();
        assertThat(VehicleCommandStatus.PUBLISHED.canTransitionTo(VehicleCommandStatus.FAILED)).isTrue();
        assertThat(VehicleCommandStatus.PUBLISHED.canTransitionTo(VehicleCommandStatus.ACCEPTED)).isTrue();
    }

    @Test
    void progressTransitions_cannotGoBackwards() {
        assertThat(VehicleCommandStatus.IN_PROGRESS.canTransitionTo(VehicleCommandStatus.ACCEPTED)).isFalse();
        assertThat(VehicleCommandStatus.ACCEPTED.canTransitionTo(VehicleCommandStatus.PUBLISHED)).isFalse();
        assertThat(VehicleCommandStatus.ACCEPTED.canTransitionTo(VehicleCommandStatus.IN_PROGRESS)).isTrue();
    }

    @Test
    void terminalStatuses_rejectEveryFurtherTransition_whichAlsoBlocksDuplicateResults() {
        // 같은 종료 결과가 중복 수신돼도 이 규칙 하나로 함께 걸러진다(별도 중복 판정 코드 없음).
        for (VehicleCommandStatus terminal : new VehicleCommandStatus[]{
                VehicleCommandStatus.SUCCESS, VehicleCommandStatus.FAILED,
                VehicleCommandStatus.REJECTED, VehicleCommandStatus.CANCELLED,
                VehicleCommandStatus.PUBLISH_FAILED}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (VehicleCommandStatus next : VehicleCommandStatus.values()) {
                assertThat(terminal.canTransitionTo(next))
                        .as("%s -> %s must be rejected", terminal, next)
                        .isFalse();
            }
        }
    }

    @Test
    void publishFailed_isOnlyReachableFromPending() {
        assertThat(VehicleCommandStatus.PUBLISHED.canTransitionTo(VehicleCommandStatus.PUBLISH_FAILED)).isFalse();
        assertThat(VehicleCommandStatus.ACCEPTED.canTransitionTo(VehicleCommandStatus.PUBLISH_FAILED)).isFalse();
    }

    @Test
    void isCompleted_excludesIntermediateAcknowledgements() {
        assertThat(VehicleCommandStatus.SUCCESS.isCompleted()).isTrue();
        assertThat(VehicleCommandStatus.FAILED.isCompleted()).isTrue();
        assertThat(VehicleCommandStatus.ACCEPTED.isCompleted()).isFalse();
        assertThat(VehicleCommandStatus.IN_PROGRESS.isCompleted()).isFalse();
        // 발행 실패는 종료 상태지만 "실행이 완료된 것"은 아니다.
        assertThat(VehicleCommandStatus.PUBLISH_FAILED.isCompleted()).isFalse();
    }
}
