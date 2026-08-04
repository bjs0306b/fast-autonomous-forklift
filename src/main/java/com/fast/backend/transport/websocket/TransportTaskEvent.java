package com.fast.backend.transport.websocket;

import java.time.OffsetDateTime;

/**
 * 운반 작업 실시간 이벤트 payload(prompt48.md 17장). 기존 프론트 계약이 없어 과한 envelope를 새로 만들지
 * 않고, 차량 이벤트({@code RealtimeEvent})와 유사한 최소 구조만 둔다.
 *
 * <p>{@code eventType} 예: {@code TASK_DISPATCHED}, {@code TASK_STATUS_CHANGED}, {@code TASK_COMPLETED},
 * {@code TASK_FAILED}.
 */
/**
 * @param failureCode 실패 이벤트일 때만 채워진다({@link com.fast.backend.transport.domain.TaskFailureCode}
 *                    이름). 다른 이벤트에서는 {@code null} 이며, 기존 구독자는 모르는 필드를 무시하므로
 *                    토픽·구조 변경 없이 추가된다.
 */
public record TransportTaskEvent(
        String eventType,
        String taskId,
        String status,
        String vehicleId,
        String failureCode,
        OffsetDateTime occurredAt
) {
}
