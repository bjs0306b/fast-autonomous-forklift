package com.fast.backend.transport.websocket;

import java.time.OffsetDateTime;

/**
 * 운반 작업 실시간 이벤트 payload(prompt48.md 17장). 기존 프론트 계약이 없어 과한 envelope를 새로 만들지
 * 않고, 차량 이벤트({@code RealtimeEvent})와 유사한 최소 구조만 둔다.
 *
 * <p>{@code eventType} 예: {@code TASK_DISPATCHED}, {@code TASK_STATUS_CHANGED}, {@code TASK_COMPLETED},
 * {@code TASK_FAILED}.
 */
public record TransportTaskEvent(
        String eventType,
        String taskId,
        String status,
        String vehicleId,
        OffsetDateTime occurredAt
) {
}
