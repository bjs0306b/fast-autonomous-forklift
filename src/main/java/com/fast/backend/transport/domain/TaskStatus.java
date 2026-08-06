package com.fast.backend.transport.domain;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 운반 작업 상태와 <b>허용 상태 전이</b>(prompt46.md 9장·15장).
 *
 * <p>정상 흐름:
 * <pre>
 * PENDING → ASSIGNED → MOVING_TO_PICKUP → MEASURING → PICKING_UP
 *         → TRANSPORTING → PLACING → COMPLETED
 * </pre>
 * 실패·취소:
 * <pre>
 * PENDING   → CANCELLED
 * ASSIGNED  → CANCELLED | FAILED
 * MOVING_TO_PICKUP / MEASURING / PICKING_UP / TRANSPORTING / PLACING → FAILED
 * </pre>
 *
 * <p>{@link #COMPLETED}/{@link #FAILED}/{@link #CANCELLED}는 종료 상태이며 이후 어떤 전이도 허용하지 않는다.
 * 허용되지 않는 전이는 {@link #validateTransition(TaskStatus, TaskStatus)}가
 * {@link ErrorCode#INVALID_TASK_STATUS_TRANSITION}으로 거부한다 — 상태 전이 판단을 enum 한 곳에 모아
 * Service들이 각자 조건문으로 재구현하지 않게 한다.
 */
public enum TaskStatus {

    PENDING,
    ASSIGNED,
    MOVING_TO_PICKUP,
    MEASURING,
    PICKING_UP,
    TRANSPORTING,
    PLACING,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED;

    static {
        Map<TaskStatus, Set<TaskStatus>> map = new EnumMap<>(TaskStatus.class);
        map.put(PENDING, EnumSet.of(ASSIGNED, CANCELLED));
        map.put(ASSIGNED, EnumSet.of(MOVING_TO_PICKUP, CANCELLED, FAILED));
        map.put(MOVING_TO_PICKUP, EnumSet.of(MEASURING, FAILED));
        map.put(MEASURING, EnumSet.of(PICKING_UP, FAILED));
        map.put(PICKING_UP, EnumSet.of(TRANSPORTING, FAILED));
        map.put(TRANSPORTING, EnumSet.of(PLACING, FAILED));
        map.put(PLACING, EnumSet.of(COMPLETED, FAILED));
        map.put(COMPLETED, EnumSet.noneOf(TaskStatus.class));
        map.put(FAILED, EnumSet.noneOf(TaskStatus.class));
        map.put(CANCELLED, EnumSet.noneOf(TaskStatus.class));
        ALLOWED = Collections.unmodifiableMap(map);
    }

    /**
     * 이 작업 상태에서 차량이 <b>화물을 싣고 있다고 볼 수 있는지</b>.
     *
     * <p>차량이 직접 보고하는 {@code vehicle_current_status.has_cargo} 가 비어 있을 때만 쓰는
     * 보조 판단이다. 집기 직전(MOVING_TO_PICKUP·MEASURING)과 집는 중(PICKING_UP)은 아직 실린
     * 상태가 아니라고 본다 — 애매한 구간을 "적재 중"으로 넓히면 화면이 실제보다 앞서 나간다.
     */
    public boolean impliesCargoOnVehicle() {
        return this == TRANSPORTING || this == PLACING;
    }

    /** 종료 상태(추가 전이 불가)인지. */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** {@code this} 상태에서 {@code target}으로 전이할 수 있는지. */
    public boolean canTransitionTo(TaskStatus target) {
        return target != null && ALLOWED.get(this).contains(target);
    }

    /**
     * 전이가 허용되지 않으면 {@link BusinessException}을 던진다. 허용되면 아무 일도 하지 않는다.
     */
    public static void validateTransition(TaskStatus from, TaskStatus to) {
        if (from == null || to == null || !from.canTransitionTo(to)) {
            throw new BusinessException(ErrorCode.INVALID_TASK_STATUS_TRANSITION,
                    "허용되지 않는 작업 상태 전이입니다: " + from + " → " + to);
        }
    }
}
