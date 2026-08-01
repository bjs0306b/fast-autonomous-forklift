package com.fast.backend.transport.domain;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운반 작업 상태 전이 검증(prompt46.md 19장 25·26번, 15장).
 */
class TaskStatusTest {

    @Test
    void validTransition_happyPath_isAllowed() {
        assertThatCode(() -> {
            TaskStatus.validateTransition(TaskStatus.PENDING, TaskStatus.ASSIGNED);
            TaskStatus.validateTransition(TaskStatus.ASSIGNED, TaskStatus.MOVING_TO_PICKUP);
            TaskStatus.validateTransition(TaskStatus.MOVING_TO_PICKUP, TaskStatus.MEASURING);
            TaskStatus.validateTransition(TaskStatus.MEASURING, TaskStatus.PICKING_UP);
            TaskStatus.validateTransition(TaskStatus.PICKING_UP, TaskStatus.TRANSPORTING);
            TaskStatus.validateTransition(TaskStatus.TRANSPORTING, TaskStatus.PLACING);
            TaskStatus.validateTransition(TaskStatus.PLACING, TaskStatus.COMPLETED);
        }).doesNotThrowAnyException();
    }

    @Test
    void invalidTransition_isBlocked() {
        assertThatThrownBy(() -> TaskStatus.validateTransition(TaskStatus.PENDING, TaskStatus.TRANSPORTING))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TASK_STATUS_TRANSITION);
    }

    @Test
    void terminalStates_allowNoFurtherTransition() {
        assertThat(TaskStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(TaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(TaskStatus.CANCELLED.isTerminal()).isTrue();
        assertThatThrownBy(() -> TaskStatus.validateTransition(TaskStatus.COMPLETED, TaskStatus.ASSIGNED))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> TaskStatus.validateTransition(TaskStatus.FAILED, TaskStatus.PENDING))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancelAndFail_transitionsAreAllowedWhereSpecified() {
        assertThat(TaskStatus.PENDING.canTransitionTo(TaskStatus.CANCELLED)).isTrue();
        assertThat(TaskStatus.ASSIGNED.canTransitionTo(TaskStatus.FAILED)).isTrue();
        assertThat(TaskStatus.PLACING.canTransitionTo(TaskStatus.FAILED)).isTrue();
        // PENDING은 곧바로 FAILED로 갈 수 없다(15장: PENDING → CANCELLED만)
        assertThat(TaskStatus.PENDING.canTransitionTo(TaskStatus.FAILED)).isFalse();
    }
}
