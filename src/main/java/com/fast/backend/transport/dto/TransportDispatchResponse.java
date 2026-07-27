package com.fast.backend.transport.dto;

/**
 * 디스패치 API 응답(prompt48.md 8장). 발행한 명령과 Task의 현재 상태를 함께 돌려준다.
 */
public record TransportDispatchResponse(
        String taskId,
        String commandId,
        String commandStatus,
        String taskStatus,
        String vehicleId
) {
}
